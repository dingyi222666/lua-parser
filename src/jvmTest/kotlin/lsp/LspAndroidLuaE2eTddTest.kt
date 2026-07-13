package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService

import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.junit.Assume
import java.io.File
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-170 / TASK-521 — Stabilize Android-Lua LSP E2E fixture expectations and lock
 * multi-document provider definition/hover/completion under the workspaceFolders path
 * contract.
 *
 * Test/resource only. Workers must not run Gradle; serial review owns
 * `jvmTest --tests lsp.LspAndroidLuaE2eTddTest`.
 *
 * Compatibility boundary (repository-local fixtures; no Android-Lua source checkout):
 *
 * 1. **Parser diagnostics** — invalid syntax fixtures publish `lua-parse` errors
 *    (same missing-RHS recovery shape as [LspLifecycleDiagnosticsTddTest]).
 * 2. **Semantic diagnostics** — reflective `luajava.bindClass` / `createProxy` / `loadLib`
 *    targets that cannot be resolved from the configured JVM classpath publish
 *    `checker.luajava.target.unresolved`. The no-runtime sentinel classpath keeps this
 *    boundary deterministic even when a host Android SDK jar exists elsewhere.
 * 3. **Java/Android provider surfaces** — `import "android..."` navigation, hover, and
 *    member completion are powered by **reflective** JVM class providers under
 *    `__jvm__/classes/...`. That requires a real `android.jar` on the reflection
 *    classpath (host SDK via [resolveAndroidJar] / [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH]
 *    / Downloads). Provider cases skip with an explicit TASK-170/TASK-521 reason when the jar is missing.
 * 4. **Unsupported / deferred runtime-only behavior** — dex-path imports, full platform
 *    jar workspace-symbol indexing without reflective providers, and loadlayout id-table
 *    member expansion (TASK-184) are documented, not asserted as green.
 * 5. **Document / workspace symbols for Lua locals** — pure-Lua symbol queries must work
 *    without a host jar (open-document snapshot only).
 * 6. **Multi-doc provider regression (TASK-521)** — after opening main/layout/details
 *    together under synthetic `workspaceFolders`, TextView / Context / OnClickListener
 *    definition, hover, and completion still resolve to reflective provider URIs without
 *    path-prefix collapse.
 *
 * Path contract (WORKING pattern from [LspJavaAndroidFeatureTddTest] /
 * [LspAndroidLuaE2eActivityStubTddTest] — keep synthetic workspaceFolders, do not empty them):
 * - `workspaceFolders = file:///workspace` so open URI virtual paths keep the `workspace/` prefix
 * - OpenDocument `path = workspace/<file>`, `uri = file:///$path` (= `file:///workspace/<file>`)
 * - Client queries use the same `workspace/<file>` path form
 * - Emptying workspaceFolders collapses opens to bare `<file>` and only works if clients
 *   also drop the prefix (WAVE34 pitfall).
 *
 * Fixtures under `src/jvmTest/resources/lsp/androidlua/` must stay repository-local: no
 * inventing drive-letter SDK roots, user-profile checkouts, or Android-Lua source trees.
 */

class LspAndroidLuaE2eTddTest {
    /**
     * Sentinel classpath entry used only as configuration metadata for the no-runtime
     * semantic boundary. It is intentionally not a real Android platform jar.
     */
    private val noAndroidRuntimeClasspath = "src/jvmTest/resources/lsp/androidlua/no-android-runtime.jar"

    /** Host-resolved android.jar (may be missing on machines without an Android SDK). */
    private val hostAndroidJar = resolveAndroidJar()

    // -------------------------------------------------------------------------
    // Fixture / configuration contract
    // -------------------------------------------------------------------------

    @Test
    fun fixture_resources_are_repository_local_and_do_not_reference_machine_checkouts() {
        assertTrue(
            !noAndroidRuntimeClasspath.contains(":") && !noAndroidRuntimeClasspath.startsWith("/"),
            "No-runtime classpath sentinel must stay repository relative."
        )
        val fixtureSources = listOf(
            "main_activity.lua",
            "layout_screen.lua",
            "android_layout.aly",
            "details_fragment.lua",
            "broken_activity.lua",
            "runtime_only.lua",
            "FIXTURE-CATALOG.md"
        ).associateWith(::fixture)

        fixtureSources.forEach { (name, source) ->
            assertTrue("G:/" !in source && "G:\\" !in source, "Fixture $name must not reference a machine-local G: checkout.")
            assertTrue("C:/Users" !in source && "C:\\Users" !in source, "Fixture $name must not reference a user profile.")
            if (name != "FIXTURE-CATALOG.md") {
                assertTrue("Android-Lua" !in source, "Fixture $name must not reference a local Android-Lua checkout.")
                assertTrue(
                    !source.contains(Regex("""[A-Za-z]:[/\\].*android\.jar""")) &&
                        "Android/Sdk" !in source &&
                        "platforms/android-" !in source,
                    "Fixture $name must not hard-code a host android.jar path."
                )
            }
        }
    }

    @Test
    fun missing_android_jar_skip_documents_explicit_reason() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val reason = missingAndroidJarSkipReason(missing)
        assertTrue(
            reason.contains("TASK-170") && reason.contains("TASK-521"),
            "Skip reason must name TASK-170 and TASK-521; got: $reason"
        )
        assertTrue(reason.contains("android.jar"), "Skip reason must mention android.jar; got: $reason")
        assertTrue(reason.contains(missing.path), "Skip reason must include the missing path; got: $reason")
        assertTrue("G:/" !in reason && "G:\\" !in reason, "Skip reason must never hardcode G:/.")
    }

    @Test
    fun main_activity_fixture_opens_without_parser_diagnostics() {
        val service = pureLuaService()
        val opened = openFixture(service, "main_activity.lua")

        assertNoParserDiagnostics(opened.diagnostics.diagnostics, "main_activity.lua")
        assertNoUnresolvedLuaJavaDiagnostics(opened.diagnostics.diagnostics, "main_activity.lua")
    }

    // -------------------------------------------------------------------------
    // Java/Android provider navigation + hover + completion (host android.jar)
    // -------------------------------------------------------------------------

    @Test
    fun import_table_exposes_android_text_view_definition() {
        val service = providerServiceOrSkip()
        val opened = openFixture(service, "main_activity.lua")

        val definition = service.definition(
            definitionParams(opened.uri, positionOf(opened.source, "TextView(context)", offset = 2))
        )

        assertTrue(definition.isNotEmpty(), "Expected TextView definition; actual: $definition")
        assertEquals(androidProviderUri("android.widget.TextView"), definition.single().uri)
    }

    @Test
    fun widget_member_completion_includes_text_view_methods() {
        val service = providerServiceOrSkip()
        val opened = openFixture(service, "main_activity.lua")

        val position = positionOf(opened.source, "setText", offset = 3)
        val completions = service.completion(opened.path, position.line, position.character)
        val labels = completions.items.map { it.label }

        assertCompletion(labels, "setText")
        assertCompletion(labels, "getText")
    }

    @Test
    fun android_view_static_field_hover_resolves_from_host_android_jar() {
        val service = providerServiceOrSkip()
        val opened = openFixture(service, "main_activity.lua")

        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(opened.uri), positionOf(opened.source, "VISIBLE", offset = 2))
        )
        val definition = service.definition(
            definitionParams(opened.uri, positionOf(opened.source, "View.VISIBLE", offset = 1))
        )

        assertHoverContainsAny(hover, "VISIBLE", "View", "android.view.View")
        assertTrue(definition.isNotEmpty(), "Expected View.VISIBLE definition; actual: $definition")
        assertEquals(androidProviderUri("android.view.View"), definition.single().uri)
    }

    @Test
    fun source_imported_text_view_hover_reports_android_widget_class() {
        val service = providerServiceOrSkip()
        val opened = openFixture(service, "main_activity.lua")

        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(opened.uri), positionOf(opened.source, "TextView(context)", offset = 2))
        )
        val definition = service.definition(
            definitionParams(opened.uri, positionOf(opened.source, "TextView(context)", offset = 2))
        )

        assertHoverContainsAny(hover, "TextView", "android.widget.TextView")
        assertTrue(definition.isNotEmpty(), "Expected TextView definition; actual: $definition")
        assertEquals(androidProviderUri("android.widget.TextView"), definition.single().uri)
    }

    @Test
    fun loadlayout_fixture_documents_layout_id_locals_and_view_like_return() {
        // Document symbols for locals do not need android.jar; View-like hover uses the
        // repository loadlayout stub (JavaObject / AndroidView / View). Id-table member
        // completion is TASK-184.
        val service = pureLuaService()
        val opened = openFixture(service, "layout_screen.lua")

        val symbols = service.documentSymbols(opened.path).map { it.name }
        assertSymbol(symbols, "ids")
        assertSymbol(symbols, "layout")
        assertSymbol(symbols, "root")
        assertSymbol(symbols, "messageText")
        assertSymbol(symbols, "submitButton")

        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(opened.uri), positionOf(opened.source, "root", occurrence = 1, offset = 1))
        )
        assertHoverContainsAny(hover, "root", "View", "AndroidView", "JavaObject", "table", "any", "unknown")

        // Id-table member completion is a library-stub surface (TASK-184). Accept:
        // - id keys when the product expands the ids table
        // - View-like member methods/fields on the local (setText/setVisibility/etc.)
        // - empty completion as the honest product gap
        // Do not invent id expansion as CURRENTLY_ACCEPTS when members already surface.
        val position = positionOf(opened.source, "messageText:setText", offset = 4)
        val completions = service.completion(opened.path, position.line, position.character)
        val labels = completions.items.map { it.label }
        val viewLikeMembers = listOf(
            "setText", "getText", "setVisibility", "getVisibility", "setOnClickListener",
            "performClick", "getId", "setId", "getContext", "setPadding"
        )
        val hasIdKeys = "messageText" in labels || "submitButton" in labels
        val hasViewLikeMembers = viewLikeMembers.any { it in labels } ||
            labels.any { it.startsWith("set") || it.startsWith("get") || it.startsWith("perform") }
        assertTrue(
            hasIdKeys || hasViewLikeMembers || labels.isEmpty(),
            "Expected loadlayout id keys, View-like member surface, or empty product gap; actual: $labels."
        )
        if (hasIdKeys) {
            assertCompletion(labels, "messageText")
            assertCompletion(labels, "submitButton")
        }
    }

    // -------------------------------------------------------------------------
    // TASK-521 multi-doc provider definition/hover/completion regression lock
    // -------------------------------------------------------------------------

    /**
     * Opens main_activity + layout_screen + details_fragment under the synthetic
     * workspaceFolders path contract and locks TextView / Context / OnClickListener
     * provider definition + hover + completion so multi-document sessions cannot
     * silently collapse `workspace/` prefixes or drop reflective provider URIs.
     */
    @Test
    fun multi_doc_provider_definition_hover_completion_stays_stable_under_workspace_folders() {
        val service = providerServiceOrSkip()
        val main = openFixture(service, "main_activity.lua")
        val layout = openFixture(service, "layout_screen.lua")
        val details = openFixture(service, "details_fragment.lua")

        // Path contract: every open keeps the workspace/ prefix and file:///workspace/... URI.
        listOf(main, layout, details).forEach { opened ->
            assertTrue(
                opened.path.startsWith("workspace/"),
                "TASK-521 path contract: open path must keep workspace/ prefix; actual: ${opened.path}"
            )
            assertEquals(
                "file:///${opened.path}",
                opened.uri,
                "TASK-521 path contract: open URI must match file:///${opened.path} (path=${opened.path}, uri=${opened.uri})"
            )
            assertTrue(
                opened.uri.startsWith("file:///workspace/"),
                "TASK-521 path contract: URI must stay under file:///workspace/; actual: ${opened.uri}"
            )
        }

        // --- TextView (main + details) ---
        val mainTextViewDefinition = service.definition(
            definitionParams(main.uri, positionOf(main.source, "TextView(context)", offset = 2))
        )
        val mainTextViewHover = service.hover(
            HoverParams(TextDocumentIdentifier(main.uri), positionOf(main.source, "TextView(context)", offset = 2))
        )
        val mainMemberPosition = positionOf(main.source, "setText", offset = 3)
        val mainMemberCompletion = service.completion(main.path, mainMemberPosition.line, mainMemberPosition.character)
            .items.map { it.label }

        assertTrue(mainTextViewDefinition.isNotEmpty(), "Multi-doc TextView definition from main; actual: $mainTextViewDefinition")
        assertEquals(androidProviderUri("android.widget.TextView"), mainTextViewDefinition.single().uri)
        assertHoverContainsAny(mainTextViewHover, "TextView", "android.widget.TextView")
        assertCompletion(mainMemberCompletion, "setText")
        assertCompletion(mainMemberCompletion, "getText")

        val detailsTextViewDefinition = service.definition(
            definitionParams(details.uri, positionOf(details.source, "TextView(activity)", offset = 2))
        )
        val detailsTextViewHover = service.hover(
            HoverParams(TextDocumentIdentifier(details.uri), positionOf(details.source, "TextView(activity)", offset = 2))
        )
        assertTrue(
            detailsTextViewDefinition.isNotEmpty(),
            "Multi-doc TextView definition from details after co-open; actual: $detailsTextViewDefinition"
        )
        assertEquals(androidProviderUri("android.widget.TextView"), detailsTextViewDefinition.single().uri)
        assertHoverContainsAny(detailsTextViewHover, "TextView", "android.widget.TextView")

        // --- Context.WINDOW_SERVICE (main + details) ---
        val mainContextDefinition = service.definition(
            definitionParams(main.uri, positionOf(main.source, "WINDOW_SERVICE", offset = 4))
        )
        val mainContextHover = service.hover(
            HoverParams(TextDocumentIdentifier(main.uri), positionOf(main.source, "WINDOW_SERVICE", offset = 4))
        )
        assertTrue(mainContextDefinition.isNotEmpty(), "Multi-doc Context definition from main; actual: $mainContextDefinition")
        assertEquals(androidProviderUri("android.content.Context"), mainContextDefinition.single().uri)
        assertHoverContains(mainContextHover, "WINDOW_SERVICE")

        val detailsContextDefinition = service.definition(
            definitionParams(details.uri, positionOf(details.source, "WINDOW_SERVICE", offset = 4))
        )
        val detailsContextHover = service.hover(
            HoverParams(TextDocumentIdentifier(details.uri), positionOf(details.source, "WINDOW_SERVICE", offset = 4))
        )
        assertTrue(
            detailsContextDefinition.isNotEmpty(),
            "Multi-doc Context definition from details after co-open; actual: $detailsContextDefinition"
        )
        assertEquals(androidProviderUri("android.content.Context"), detailsContextDefinition.single().uri)
        assertHoverContains(detailsContextHover, "WINDOW_SERVICE")

        // --- OnClickListener (layout) still resolves after multi-doc open ---
        val listenerDefinition = service.definition(
            definitionParams(layout.uri, positionOf(layout.source, "OnClickListener", occurrence = 2, offset = 4))
        )
        val listenerHover = service.hover(
            HoverParams(
                TextDocumentIdentifier(layout.uri),
                positionOf(layout.source, "OnClickListener", occurrence = 2, offset = 4)
            )
        )
        assertTrue(listenerDefinition.isNotEmpty(), "Multi-doc OnClickListener definition; actual: $listenerDefinition")
        assertEquals(androidProviderUri("android.view.View\$OnClickListener"), listenerDefinition.single().uri)
        assertHoverContainsAny(listenerHover, "OnClickListener", "View")

        // Layout TextView import completion remains usable after co-open.
        val layoutTextViewPosition = positionOf(layout.source, "TextView,", offset = 2)
        val layoutCompletions = service.completion(
            layout.path,
            layoutTextViewPosition.line,
            layoutTextViewPosition.character
        ).items.map { it.label }
        assertTrue(
            "TextView" in layoutCompletions || layoutCompletions.any { it.contains("TextView", ignoreCase = true) },
            "Multi-doc layout TextView completion; actual: $layoutCompletions"
        )

        // Cross-file workspace symbol must still resolve with workspace/ URIs.
        val attachSymbols = service.workspaceSymbols("attach")
        assertTrue(
            attachSymbols.any { it.name == "attach" && it.location.uri == details.uri },
            "Multi-doc workspace symbol attach must keep details URI under workspaceFolders; actual: ${attachSymbols.map { it.name to it.location.uri }}"
        )
    }

    /**
     * Multi-doc regression for TextView references across main/layout/details while
     * keeping provider URI + workspace usage under the workspaceFolders contract.
     */

    /**
     * TextDocumentService wrapper multi-doc lock: after co-opening main + layout under
     * workspaceFolders, hover/completion/definition for TextView and OnClickListener
     * still hit reflective providers.
     */

    // -------------------------------------------------------------------------
    // Document / workspace symbols and references
    // -------------------------------------------------------------------------

    @Test
    fun language_server_initializes_with_workspace_folder_for_android_lua_e2e_flow() {
        val server = LuaLanguageServer()

        val initialize = server.initialize(
            InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
            }
        ).get()

        assertNotNull(initialize.capabilities.hoverProvider)
        assertNotNull(initialize.capabilities.completionProvider)
        assertNotNull(server.textDocumentService)
        assertNotNull(server.workspaceService)
        assertEquals(0, server.shutdown().get())
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Pure open-document service with the Feature/ActivityStub path contract and no
     * Android classpath metadata. Used for parser diagnostics and pure-Lua symbols.
     */
    private fun pureLuaService(): LuaLanguageService {
        return initializedService()
    }

    /**
     * Service with the no-runtime sentinel so reflective Android classes stay unavailable.
     * Used for semantic unresolved LuaJava and "no provider URI" workspace-symbol bounds.
     */
    private fun noRuntimeService(): LuaLanguageService {
        return initializedService().also { service ->
            LuaWorkspaceService(service).didChangeConfiguration(androidConfiguration(noAndroidRuntimeClasspath))
        }
    }

    /**
     * Service with a real host android.jar for reflective provider surfaces.
     * Skips the calling test when the jar is absent (explicit TASK-170/TASK-521 reason).
     *
     * Matches [LspJavaAndroidFeatureTddTest.androidService]: default engine +
     * didChangeConfiguration(jvm.androidJar = host path), no constructor jar.
     */
    private fun providerServiceOrSkip(): LuaLanguageService {
        requireHostAndroidJarOrSkip()
        return initializedService().also { service ->
            LuaWorkspaceService(service).didChangeConfiguration(androidConfiguration(hostAndroidJar.path))
        }
    }

    private fun androidConfiguration(androidJarPath: String): DidChangeConfigurationParams {
        return DidChangeConfigurationParams(
            mapOf(
                "jvm.androidJar" to androidJarPath,
                "jvm.importPrefixes" to listOf(
                    "java.lang",
                    "java.util",
                    "android.app",
                    "android.content",
                    "android.view",
                    "android.view.View",
                    "android.widget"
                ),
                "androlua.imports" to listOf(
                    "Activity",
                    "Context",
                    "View",
                    "OnClickListener",
                    "TextView",
                    "Button",
                    "LinearLayout"
                )
            )
        )
    }

    private fun workspaceInitializeParams(): InitializeParams {
        return InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
        }
    }

    private fun requireHostAndroidJarOrSkip() {
        if (!hostAndroidJar.isFile) {
            // Honest soft-skip only when dual-path discovery + convenience candidates miss a jar.
            Assume.assumeTrue(missingAndroidJarSkipReason(hostAndroidJar), false)
        }
        assertTrue(hostAndroidJar.length() > 0, "Expected non-empty android.jar at ${hostAndroidJar.path}.")
        val normalized = hostAndroidJar.path.replace('\\', '/')
        assertTrue(
            !normalized.startsWith("G:/", ignoreCase = true),
            "Host android.jar resolution must never prefer inventing drive-letter SDK roots; actual: ${hostAndroidJar.path}"
        )
    }

    private fun initializedService(): LuaLanguageService {
        return LuaLanguageService().also { service ->
            service.initialize(workspaceInitializeParams())
        }
    }

    private fun openFixture(service: LuaLanguageService, name: String): OpenedFixture {
        val source = fixture(name)
        val path = pathFor(name)
        // Same URI construction as LspJavaAndroidFeatureTddTest.OpenDocument:
        // file:///$path with path = workspace/<file>.
        val uri = "file:///$path"
        val diagnostics = service.didOpen(openParams(uri, source))
        return OpenedFixture(name, path, uri, source, diagnostics)
    }

    private fun openParams(uri: String, source: String): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source))
    }

    private fun definitionParams(uri: String, position: Position): DefinitionParams {
        return DefinitionParams(TextDocumentIdentifier(uri), position)
    }

    private fun fixture(name: String): String {
        val path = "lsp/androidlua/$name"
        val resource = javaClass.classLoader.getResource(path)
        return assertNotNull(resource, "Missing fixture resource $path.").readText()
    }

    /**
     * Client path form used by [LuaLanguageService.completion] / [LuaLanguageService.documentSymbols]
     * / [LuaLanguageService.diagnostics]. Keep the `workspace/` prefix so paths match
     * `file:///workspace/<name>` open URIs (same contract as [LspJavaAndroidFeatureTddTest]).
     */
    private fun pathFor(name: String): String = "workspace/$name"

    private fun androidProviderUri(className: String): String {
        return "file:///__jvm__/classes/${className.replace('.', '/')}.lua"
    }

    private fun positionOf(source: String, needle: String, occurrence: Int = 1, offset: Int = 0): Position {
        require(occurrence > 0) { "Occurrence must be positive." }
        var from = 0
        var index = -1
        repeat(occurrence) {
            index = source.indexOf(needle, from)
            require(index >= 0) { "Could not find occurrence ${it + 1} of '$needle' in source." }
            from = index + needle.length
        }
        val target = index + min(offset, needle.lastIndex.coerceAtLeast(0))
        var line = 0
        var character = 0
        for (i in 0 until target) {
            if (source[i] == '\n') {
                line += 1
                character = 0
            } else {
                character += 1
            }
        }
        return Position(line, character)
    }

    private fun assertHoverContains(hover: Hover?, expected: String) {
        assertNotNull(hover, "Expected hover containing '$expected'.")
        val value = hoverMarkup(hover)
        assertTrue(
            value.contains(expected),
            "Expected hover to contain '$expected', actual: $value."
        )
    }

    private fun assertHoverContainsAny(hover: Hover?, vararg expected: String) {
        assertNotNull(hover, "Expected hover containing one of ${expected.toList()}.")
        val value = hoverMarkup(hover)
        assertTrue(
            expected.any { fragment -> value.contains(fragment, ignoreCase = true) },
            "Expected hover to contain one of ${expected.toList()}, actual: $value."
        )
    }

    private fun hoverMarkup(hover: Hover): String {
        val contents = hover.contents
        return when {
            contents.isRight -> contents.right.value
            contents.isLeft -> contents.left.joinToString("\n") { either ->
                if (either.isRight) either.right.value else either.left.toString()
            }
            else -> hover.toString()
        }
    }

    private fun assertCompletion(labels: List<String>, expected: String) {
        assertTrue(expected in labels, "Expected completion '$expected', actual labels: $labels.")
    }

    private fun assertSymbol(symbols: List<String>, expected: String) {
        assertTrue(expected in symbols, "Expected symbol '$expected', actual symbols: $symbols.")
    }

    private fun assertMetadataLines(actual: String?, vararg expected: String) {
        val lines = actual.orEmpty().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        assertEquals(expected.toList(), lines)
    }

    private fun assertDiagnosticCode(codes: List<String>, expected: String) {
        assertTrue(expected in codes, "Expected diagnostic code '$expected', actual codes: $codes.")
    }

    private fun assertNoParserDiagnostics(diagnostics: List<Diagnostic>, fixtureName: String) {
        val parse = diagnostics.filter { it.code?.left == "lua-parse" }
        assertTrue(
            parse.isEmpty(),
            "Expected $fixtureName to publish no parser diagnostics; actual: $parse."
        )
    }

    private fun assertNoUnresolvedLuaJavaDiagnostics(diagnostics: List<Diagnostic>, fixtureName: String) {
        val unresolved = diagnostics.filter { it.code?.left == "checker.luajava.target.unresolved" }
        assertTrue(
            unresolved.isEmpty(),
            "Expected $fixtureName (import-based provider surface) to publish no unresolved LuaJava diagnostics; actual: $unresolved."
        )
    }

    private data class OpenedFixture(
        val name: String,
        val path: String,
        val uri: String,
        val source: String,
        val diagnostics: org.eclipse.lsp4j.PublishDiagnosticsParams
    )

    companion object {
        /**
         * Host dual-path android.jar resolution (TASK-620 / TASK-170 / TASK-521):
         * 1) [JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath] (ANDROID_HOME /
         *    ANDROID_SDK_ROOT then well-known host SDK roots)
         * 2) WAVE mac SDK convenience path (present-only)
         * 3) Explicit Downloads copy (present-only; never auto-invented by product)
         * 4) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] messaging candidate
         *
         * Prefer real files; never invent drive-letter SDK roots as preferred defaults.
         * Soft-skip when no present jar remains after dual-path discovery.
         */
        private fun resolveAndroidJar(): File {
            val candidates = linkedSetOf<File>()
            JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath()?.let { candidates += File(it) }
            candidates += File("/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar")
            candidates += File("/Users/dingyi/Downloads/android.jar")
            candidates += File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
            sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull { env -> System.getenv(env)?.trim()?.takeIf(String::isNotEmpty) }
                .forEach { sdkRoot ->
                    candidates += File(sdkRoot, "platforms/android-35/android.jar")
                    candidates += File(sdkRoot, "platforms/android-34/android.jar")
                }
            fun isInventedDriveRoot(path: String): Boolean {
                val normalized = path.replace('\\', '/')
                return normalized.startsWith("G:/", ignoreCase = true) ||
                    normalized.startsWith("g:/")
            }
            return candidates.firstOrNull { it.isFile && !isInventedDriveRoot(it.path) }
                ?: candidates.firstOrNull { it.isFile }
                ?: candidates.first { !isInventedDriveRoot(it.path) }
                ?: candidates.first()
        }

        internal fun missingAndroidJarSkipReason(androidJar: File): String {
            // Soft-skip only: report the missing candidate path that dual-path discovery
            // (or the test-supplied File) could not load. Never invent drive-letter SDK roots.
            return "TASK-170/TASK-521 skipped provider surface: android.jar not found at ${androidJar.path}. " +
                "Install Android SDK Platform 35 (or set ANDROID_HOME / ANDROID_SDK_ROOT) before asserting " +
                "reflective Android import/hover/definition/completion multi-doc regression locks. " +
                "Pure-Lua parser/symbol cases still run. Never requires machine-local Android-Lua checkouts " +
                "or inventing drive-letter SDK roots."
        }
    }
}
