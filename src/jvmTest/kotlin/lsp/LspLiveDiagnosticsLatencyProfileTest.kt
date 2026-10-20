package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Latency profile for the live-diagnostics checker path under the DEVICE
 * configuration: framework `android.dex` + project `classes.dex` mounted via
 * `jvm.androidDex` / `jvm.classpath`, and the sample app's real project
 * fixtures. The device E2E fails this flow (checker >10s); on the fast JVM the
 * same workload should be attributable per stage.
 */
class LspLiveDiagnosticsLatencyProfileTest {

    private val repoRoot = File(System.getProperty("user.dir"))
    private val androidDex = File(repoRoot, "android/sample/src/main/assets/android.dex")
    private val projectDex = File(repoRoot, "android/sample/src/main/assets/project/libs/classes.dex")
    private val projectDir = File(repoRoot, "android/sample/src/main/assets/project")

    private fun deviceConfiguration(): Map<String, Any> {
        if (!androidDex.isFile) {
            System.err.println("LSP-PROFILE: android.dex fixture missing — device dex path untestable here")
            return emptyMap()
        }
        return buildMap {
            put("jvm.androidDex", androidDex.absolutePath)
            if (projectDex.isFile) put("jvm.classpath", projectDex.absolutePath)
        }
    }

    private fun fixtureSource(name: String): String =
        File(projectDir, name).readText()

    private fun initializedService(deferred: Boolean): LuaLanguageService {
        val service = LuaLanguageService(deferDocumentAnalysis = deferred)
        service.initialize(
            InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
            }
        )
        LuaWorkspaceService(service).didChangeConfiguration(
            org.eclipse.lsp4j.DidChangeConfigurationParams(deviceConfiguration())
        )
        return service
    }

    private fun open(service: LuaLanguageService, uri: String, source: String) {
        service.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source))
        )
    }

    @Test
    fun direct_checker_pass_timing_under_device_dex_configuration() {
        val service = initializedService(deferred = false)
        val files = listOf("main.lua", "mods/dingyi.lua")
        files.forEach { name ->
            open(service, "file:///workspace/$name", fixtureSource(name))
        }
        service.flushBackgroundRebuild()
        service.awaitWorkspaceReady()

        val snapshotField = LuaLanguageService::class.java
            .getDeclaredField("snapshot").apply { isAccessible = true }
        val snapshot = snapshotField.get(service) as WorkspaceSnapshot
        val mainPath = snapshot.files.keys.first { it.value.endsWith("main.lua") }
        val semantic = assertNotNull(snapshot.files.getValue(mainPath).semanticFile)

        repeat(3) { attempt ->
            val started = System.currentTimeMillis()
            val pipeline = SemanticPipeline()
            val resolver = WorkspaceModuleResolver(snapshot, deferDependencyGlobals = true)
            val checking = semantic.snapshot.copy(
                workspaceContext = semantic.snapshot.workspaceContext.copy(
                    workspaceResolver = resolver,
                    resolveImportedSymbol = null,
                    resolveImportTarget = null
                ).withWorkspaceImportEffects()
            )
            val checked = pipeline.finishDiagnostics(checking)
            System.err.println(
                "LSP-PROFILE: direct-check attempt=$attempt " +
                    "took=${System.currentTimeMillis() - started}ms diagnostics=${checked.checker.diagnostics.size}"
            )
        }
        service.close()
    }

    /**
     * Device E2E `dynamicallyImportedDexCompletesJavaObjectsInsideLuaTables`
     * mirror under the deferred-analysis service mode: a runtime dex written next
     * to the project, imported by absolute path, must complete its members.
     */
    @Test
    fun dynamic_dex_import_activates_member_completion_under_deferred_analysis() {
        val runtimeDex = File(repoRoot, "android/sample/src/androidTest/assets/runtime-greeter.dex")
        if (!runtimeDex.isFile || deviceConfiguration().isEmpty()) {
            println("LSP-PROFILE: dynamic-dex fixture unavailable — skipping")
            return
        }
        val service = initializedService(deferred = true)
        val source = "import '${runtimeDex.absolutePath}'\n" +
            "local Greeter=luajava.bindClass('fixture.dynamic.Greeter')\nGreeter."
        open(service, "file:///workspace/dynamic_dex.lua", source)
        service.flushBackgroundRebuild()
        service.awaitWorkspaceReady()

        // Probe: what type did the bindClass local resolve to?
        run {
            val snapshotField = LuaLanguageService::class.java
                .getDeclaredField("snapshot").apply { isAccessible = true }
            val snapshot = snapshotField.get(service) as WorkspaceSnapshot
            val dynamicPath = snapshot.files.keys.first { it.value.endsWith("dynamic_dex.lua") }
            val semantic = assertNotNull(snapshot.files.getValue(dynamicPath).semanticFile)
            val model = semantic.model
            val symbol = model.getSymbolAt(io.github.dingyi222666.luaparser.parser.ast.node.Position(3, 1))
            System.err.println(
                "LSP-PROFILE: Greeter symbol=" + symbol?.name +
                    " typeKey=" + symbol?.type?.typeKey +
                    " declared=" + symbol?.declaredType?.typeKey
            )
        }

        // Line 3 (1-based), column after "Greeter." = "Greeter.".length + 1
        // LSP 0-based: parser line 3 -> LSP line 2; caret after "Greeter." -> character 8.
        val completions = service.completion("file:///workspace/dynamic_dex.lua", 2, 8)
        val labels = completions.items.map { it.label }
        System.err.println("LSP-PROFILE: dynamic-dex completion labels=$labels")
        check("create" in labels && "libraryMarker" in labels) {
            "dynamic dex members must complete; got $labels"
        }
        service.close()
    }

    /**
     * Device E2E `openCompleteEditSwitchFormatSaveAndRecreate` cold gate: the FIRST
     * completion after a content change. On the emulator this completion takes
     * 8-9.6s server-side (the per-await budget is 10s). Measure the same flow on
     * the JVM under the device dex configuration.
     */
    @Test
    fun first_completion_after_content_change_latency() {
        if (deviceConfiguration().isEmpty()) {
            println("LSP-PROFILE: android.dex fixture missing — skipping")
            return
        }
        val service = initializedService(deferred = true)
        open(service, "file:///workspace/main.lua", fixtureSource("main.lua"))
        open(service, "file:///workspace/mods/dingyi.lua", fixtureSource("mods/dingyi.lua"))
        service.flushBackgroundRebuild()
        service.awaitWorkspaceReady()

        // Warm reference: completion before the edit.
        val warmStarted = System.currentTimeMillis()
        service.completion("file:///workspace/main.lua", 0, 0)
        System.err.println("LSP-PROFILE: warm-completion=" + (System.currentTimeMillis() - warmStarted) + "ms")

        // Content change (mirror of the E2E setText edit), then the FIRST completion.
        val main = fixtureSource("main.lua")
        service.didChange(
            org.eclipse.lsp4j.DidChangeTextDocumentParams(
                org.eclipse.lsp4j.VersionedTextDocumentIdentifier("file:///workspace/main.lua", 2),
                listOf(org.eclipse.lsp4j.TextDocumentContentChangeEvent(main + "\nlocal afterEditValue = 1\n"))
            )
        )
        // The device's expensive completion runs against the REFRESHED snapshot
        // (the live-analysis rebuild published new binders; every declaration
        // derivation re-runs lazily inside the first query). Wait for the refresh
        // to settle first, then measure.
        service.flushBackgroundRebuild()
        service.awaitWorkspaceReady()
        val coldStarted = System.currentTimeMillis()
        val cold = service.completion("file:///workspace/main.lua", 0, 0)
        val coldMs = System.currentTimeMillis() - coldStarted
        System.err.println(
            "LSP-PROFILE: cold-first-completion=" + coldMs + "ms items=" + cold.items.size
        )
        service.close()
    }

    /**
     * Device evidence: `finishDiagnostics` on the dex-import document measured
     * 24.1s on the emulator and hogged the single diagnostics worker (the
     * checker-warning E2E timed out waiting behind it). Mirror the exact
     * diagnostics-worker shape here and decompose the cost: import resolution
     * (mount + module surface) vs the checker pass itself.
     */
    @Test
    fun dynamic_dex_import_checker_cost_decomposition() {
        val runtimeDex = File(repoRoot, "android/sample/src/androidTest/assets/runtime-greeter.dex")
        if (!runtimeDex.isFile || deviceConfiguration().isEmpty()) {
            println("LSP-PROFILE: dynamic-dex fixture unavailable — skipping")
            return
        }
        val service = initializedService(deferred = true)
        val source = "import '${runtimeDex.absolutePath}'\n" +
            "local Greeter=luajava.bindClass('fixture.dynamic.Greeter')\nGreeter."
        open(service, "file:///workspace/dynamic_dex_check.lua", source)
        service.flushBackgroundRebuild()
        service.awaitWorkspaceReady()

        val snapshotField = LuaLanguageService::class.java
            .getDeclaredField("snapshot").apply { isAccessible = true }
        val snapshot = snapshotField.get(service) as WorkspaceSnapshot
        val dynamicPath = snapshot.files.keys.first { it.value.endsWith("dynamic_dex_check.lua") }
        val semantic = assertNotNull(snapshot.files.getValue(dynamicPath).semanticFile)
        val pipeline = SemanticPipeline()

        repeat(2) { attempt ->
            // Phase 1: import resolution alone (mount + module export surface).
            val resolveStarted = System.currentTimeMillis()
            val resolved = semantic.snapshot.workspaceContext.resolveImportTarget
                ?.invoke(runtimeDex.absolutePath)
            val resolveMs = System.currentTimeMillis() - resolveStarted
            // Phase 2: the full diagnostics-worker check shape.
            val checkStarted = System.currentTimeMillis()
            val resolver = WorkspaceModuleResolver(snapshot, deferDependencyGlobals = true)
            val checking = semantic.snapshot.copy(
                workspaceContext = semantic.snapshot.workspaceContext.copy(
                    workspaceResolver = resolver,
                    resolveImportedSymbol = null,
                    resolveImportTarget = null
                ).withWorkspaceImportEffects()
            )
            val checked = pipeline.finishDiagnostics(checking)
            val checkMs = System.currentTimeMillis() - checkStarted
            System.err.println(
                "LSP-PROFILE: dex-import-check attempt=$attempt resolve=${resolveMs}ms " +
                    "resolved=${resolved != null} check=${checkMs}ms diagnostics=${checked.checker.diagnostics.size}"
            )
        }
        service.close()
    }
}
