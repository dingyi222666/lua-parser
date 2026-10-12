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
}
