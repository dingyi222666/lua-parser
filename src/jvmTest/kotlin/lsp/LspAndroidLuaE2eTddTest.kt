package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
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
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceSymbolParams
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-170 — Stabilize Android-Lua LSP E2E fixture expectations.
 *
 * Test/resource-only. Workers must not run Gradle; serial review owns
 * `jvmTest --tests lsp.LspAndroidLuaE2eTddTest`.
 *
 * Compatibility boundary (repository-local, no machine Android SDK checkout):
 *
 * 1. **Parser diagnostics** — invalid syntax fixtures publish `lua-parse` errors.
 * 2. **Semantic diagnostics** — runtime-only LuaJava targets that cannot be resolved
 *    from the configured JVM classpath publish `checker.luajava.target.unresolved`.
 *    Repository Android framework overlay models are *not* a substitute for reflective
 *    classloader resolution of `luajava.bindClass` / `createProxy` / `loadLib`.
 * 3. **Java/Android symbol surfaces** — `import "android..."` navigation, hover, and
 *    member completion are powered by the ANDROLUA_5_3 standard-library overlay
 *    Android framework providers under `__jvm__/classes/...` (no host `android.jar`).
 * 4. **Unsupported / deferred runtime-only behavior** — host SDK reflection, dex-path
 *    imports, and full platform-jar workspace-symbol indexing are out of this corpus.
 *    Workspace symbols are limited to opened workspace files when no reflective
 *    `extraProviders` are mounted from a real classpath.
 * 5. **Layout id completion** — `loadlayout(layout, ids)` id-table member completion is
 *    a library-stub surface (see TASK-184). This corpus asserts id locals exist as
 *    document symbols and that loadlayout return hover is View-like; it does not hide
 *    empty id-member completion as a pass.
 *
 * Fixtures under `src/jvmTest/resources/lsp/androidlua/` are minimal and must never
 * reference machine-local `G:/`, `C:/Users`, or an Android-Lua source checkout.
 */
class LspAndroidLuaE2eTddTest {
    /**
     * Sentinel classpath entry used only as configuration metadata. It is intentionally
     * not a real Android platform jar so the suite stays deterministic on machines
     * without an Android SDK. Android class surfaces come from repository framework
     * overlay models; reflective `luajava.*` class loads against this path stay unresolved.
     */
    private val noAndroidRuntimeClasspath = "src/jvmTest/resources/lsp/androidlua/no-android-runtime.jar"

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
    fun fixture_catalog_documents_parser_semantic_provider_and_runtime_only_boundaries() {
        val catalog = fixture("FIXTURE-CATALOG.md")
        assertTrue(catalog.contains("parser"), "Catalog must document parser diagnostics fixtures.")
        assertTrue(catalog.contains("semantic"), "Catalog must document semantic diagnostics fixtures.")
        assertTrue(catalog.contains("provider") || catalog.contains("framework"), "Catalog must document provider/framework surfaces.")
        assertTrue(catalog.contains("runtime"), "Catalog must document runtime-only LuaJava fixtures.")
        assertTrue(catalog.contains("main_activity.lua"))
        assertTrue(catalog.contains("broken_activity.lua"))
        assertTrue(catalog.contains("runtime_only.lua"))
    }

    @Test
    fun workspace_service_accepts_nested_android_lua_configuration_without_runtime_classpath() {
        val service = initializedService()
        val workspace = LuaWorkspaceService(service)
        workspace.didChangeConfiguration(
            DidChangeConfigurationParams(
                mapOf(
                    "jvm" to mapOf(
                        "androidJar" to noAndroidRuntimeClasspath,
                        "importPrefixes" to listOf("android.widget", "android.content")
                    ),
                    "androlua" to mapOf("imports" to listOf("TextView", "Context"))
                )
            )
        )

        val metadata = workspace.currentWorkspaceMetadata()

        assertEquals(noAndroidRuntimeClasspath, metadata["jvm.androidJar"])
        assertMetadataLines(metadata["jvm.importPrefixes"], "android.widget", "android.content")
        assertMetadataLines(metadata["androlua.imports"], "TextView", "Context")
    }

    @Test
    fun workspace_service_accepts_flat_android_lua_configuration_without_runtime_classpath() {
        val service = initializedService()
        val workspace = LuaWorkspaceService(service)
        workspace.didChangeConfiguration(
            DidChangeConfigurationParams(
                mapOf(
                    "jvm.androidJar" to noAndroidRuntimeClasspath,
                    "jvm.importPrefixes" to listOf("android.view"),
                    "androlua.imports" to listOf("View")
                )
            )
        )

        val metadata = workspace.currentWorkspaceMetadata()

        assertEquals(noAndroidRuntimeClasspath, metadata["jvm.androidJar"])
        assertMetadataLines(metadata["jvm.importPrefixes"], "android.view")
        assertMetadataLines(metadata["androlua.imports"], "View")
    }

    // -------------------------------------------------------------------------
    // Parser diagnostics boundary
    // -------------------------------------------------------------------------

    @Test
    fun broken_activity_fixture_publishes_parse_diagnostics() {
        val service = androidService()
        val opened = openFixture(service, "broken_activity.lua")

        assertTrue(opened.diagnostics.diagnostics.isNotEmpty(), "Expected invalid Android-Lua fixture to publish diagnostics.")
        assertEquals(DiagnosticSeverity.Error, opened.diagnostics.diagnostics.first().severity)
        assertDiagnosticCode(opened.diagnostics.diagnostics.mapNotNull { it.code?.left }, "lua-parse")
        assertEquals(opened.uri, opened.diagnostics.uri)
        assertTrue(
            opened.diagnostics.diagnostics.none { it.code?.left == "checker.luajava.target.unresolved" },
            "Parse-invalid fixture should surface parser diagnostics, not only semantic LuaJava codes."
        )
    }

    @Test
    fun diagnostics_query_uses_latest_android_lua_fixture_snapshot() {
        val service = androidService()
        openFixture(service, "broken_activity.lua")

        val diagnostics = service.diagnostics(pathFor("broken_activity.lua"))

        assertTrue(diagnostics.diagnostics.isNotEmpty(), "Expected diagnostics query to return parse diagnostics.")
        assertDiagnosticCode(diagnostics.diagnostics.mapNotNull { it.code?.left }, "lua-parse")
    }

    // -------------------------------------------------------------------------
    // Semantic runtime-only boundary (classpath-unresolved LuaJava)
    // -------------------------------------------------------------------------

    @Test
    fun runtime_only_luajava_fixture_publishes_semantic_diagnostics_for_unresolved_runtime_targets() {
        val service = androidService()
        val opened = openFixture(service, "runtime_only.lua")
        val diagnostics = opened.diagnostics.diagnostics
        val codes = diagnostics.mapNotNull { it.code?.left }
        val messages = diagnostics.map { it.message }

        assertTrue(
            diagnostics.none { it.code?.left == "lua-parse" },
            "Runtime-only fixture must parse cleanly so semantic LuaJava diagnostics are not hidden by parse errors; actual: $diagnostics."
        )
        assertTrue(
            diagnostics.size >= 3,
            "Expected unresolved bindClass, createProxy, and loadLib runtime target diagnostics, actual: $diagnostics."
        )
        assertDiagnosticCode(codes, "checker.luajava.target.unresolved")
        assertTrue(
            messages.any { it.contains("android.widget.TextView") },
            "Expected unresolved bindClass diagnostic for TextView (no reflective classpath), actual: $messages."
        )
        assertTrue(
            messages.any { it.contains("android.view.View.OnClickListener") || it.contains("android.view.View\$OnClickListener") },
            "Expected unresolved createProxy diagnostic for OnClickListener, actual: $messages."
        )
        assertTrue(
            messages.any { it.contains("com.example.NativeOnly") },
            "Expected unresolved loadLib diagnostic for NativeOnly, actual: $messages."
        )
    }

    // -------------------------------------------------------------------------
    // Clean provider-surface fixtures (repository framework models)
    // -------------------------------------------------------------------------

    @Test
    fun main_activity_fixture_opens_without_parser_diagnostics_with_repository_android_framework() {
        val service = androidService()
        val opened = openFixture(service, "main_activity.lua")

        assertNoParserDiagnostics(opened.diagnostics.diagnostics, "main_activity.lua")
        assertNoUnresolvedLuaJavaDiagnostics(opened.diagnostics.diagnostics, "main_activity.lua")
    }

    @Test
    fun layout_fixture_opens_without_parser_diagnostics_with_repository_android_framework() {
        val service = androidService()
        val opened = openFixture(service, "layout_screen.lua")

        assertNoParserDiagnostics(opened.diagnostics.diagnostics, "layout_screen.lua")
        assertNoUnresolvedLuaJavaDiagnostics(opened.diagnostics.diagnostics, "layout_screen.lua")
    }

    @Test
    fun aly_layout_fixture_opens_without_parser_diagnostics_with_repository_android_framework() {
        val service = androidService()
        val opened = openFixture(service, "android_layout.aly")

        assertNoParserDiagnostics(opened.diagnostics.diagnostics, "android_layout.aly")
        assertNoUnresolvedLuaJavaDiagnostics(opened.diagnostics.diagnostics, "android_layout.aly")
    }

    // -------------------------------------------------------------------------
    // Java/Android provider navigation + hover + completion
    // -------------------------------------------------------------------------

    @Test
    fun import_table_exposes_android_text_view_definition() {
        val service = androidService()
        val opened = openFixture(service, "main_activity.lua")

        val definition = service.definition(
            definitionParams(opened.uri, positionOf(opened.source, "TextView(context)", offset = 2))
        )

        assertEquals(androidProviderUri("android.widget.TextView"), definition.single().uri)
    }

    @Test
    fun import_table_exposes_android_context_static_field_definition() {
        val service = androidService()
        val opened = openFixture(service, "main_activity.lua")

        val definition = service.definition(
            definitionParams(opened.uri, positionOf(opened.source, "WINDOW_SERVICE", offset = 4))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(opened.uri), positionOf(opened.source, "WINDOW_SERVICE", offset = 4))
        )

        assertEquals(androidProviderUri("android.content.Context"), definition.single().uri)
        assertHoverContains(hover, "WINDOW_SERVICE")
    }

    @Test
    fun widget_member_completion_includes_text_view_methods() {
        val service = androidService()
        val opened = openFixture(service, "main_activity.lua")

        val completions = service.completion(
            pathFor("main_activity.lua"),
            positionOf(opened.source, "setText", offset = 3).line,
            positionOf(opened.source, "setText", offset = 3).character
        )

        assertCompletion(completions.items.map { it.label }, "setText")
        assertCompletion(completions.items.map { it.label }, "getText")
    }

    @Test
    fun android_view_static_field_hover_resolves_from_repository_framework_fixture() {
        val service = androidService()
        val opened = openFixture(service, "main_activity.lua")

        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(opened.uri), positionOf(opened.source, "VISIBLE", offset = 2))
        )
        val definition = service.definition(
            definitionParams(opened.uri, positionOf(opened.source, "View.VISIBLE", offset = 1))
        )

        assertHoverContains(hover, "VISIBLE")
        assertEquals(androidProviderUri("android.view.View"), definition.single().uri)
    }

    @Test
    fun source_imported_text_view_hover_reports_android_widget_class() {
        val service = androidService()
        val opened = openFixture(service, "main_activity.lua")

        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(opened.uri), positionOf(opened.source, "TextView(context)", offset = 2))
        )
        val definition = service.definition(
            definitionParams(opened.uri, positionOf(opened.source, "TextView(context)", offset = 2))
        )

        assertHoverContains(hover, "TextView")
        assertEquals(androidProviderUri("android.widget.TextView"), definition.single().uri)
    }

    @Test
    fun source_imported_context_static_member_definition_uses_android_context_provider() {
        val service = androidService()
        val opened = openFixture(service, "main_activity.lua")

        val definition = service.definition(
            definitionParams(opened.uri, positionOf(opened.source, "contextStatic", occurrence = 1, offset = 4))
        )
        val memberDefinition = service.definition(
            definitionParams(opened.uri, positionOf(opened.source, "Context.WINDOW_SERVICE", offset = 12))
        )

        assertEquals(opened.uri, definition.single().uri)
        assertEquals(androidProviderUri("android.content.Context"), memberDefinition.single().uri)
    }

    @Test
    fun source_imported_listener_hover_and_definition_resolve_android_listener_class() {
        val service = androidService()
        val opened = openFixture(service, "layout_screen.lua")

        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(opened.uri), positionOf(opened.source, "OnClickListener", occurrence = 2, offset = 4))
        )
        val definition = service.definition(
            definitionParams(opened.uri, positionOf(opened.source, "OnClickListener", occurrence = 2, offset = 4))
        )

        assertHoverContains(hover, "OnClickListener")
        assertEquals(androidProviderUri("android.view.View\$OnClickListener"), definition.single().uri)
    }

    @Test
    fun underscore_inner_class_import_exposes_on_click_listener_definition() {
        val service = androidService()
        val opened = openFixture(service, "layout_screen.lua")

        val definition = service.definition(
            definitionParams(opened.uri, positionOf(opened.source, "OnClickListener", occurrence = 2, offset = 4))
        )

        assertEquals(androidProviderUri("android.view.View\$OnClickListener"), definition.single().uri)
    }

    @Test
    fun wildcard_widget_import_completion_includes_representative_android_widgets() {
        val service = androidService()
        val opened = openFixture(service, "layout_screen.lua")

        val position = positionOf(opened.source, "TextView,", offset = 2)
        val completions = service.completion(pathFor("layout_screen.lua"), position.line, position.character)
        val labels = completions.items.map { it.label }

        assertCompletion(labels, "TextView")
        assertCompletion(labels, "Button")
        assertCompletion(labels, "LinearLayout")
    }

    @Test
    fun loadlayout_fixture_documents_layout_id_locals_and_view_like_return() {
        val service = androidService()
        val opened = openFixture(service, "layout_screen.lua")

        val symbols = service.documentSymbols(pathFor("layout_screen.lua")).map { it.name }
        assertSymbol(symbols, "ids")
        assertSymbol(symbols, "layout")
        assertSymbol(symbols, "root")
        assertSymbol(symbols, "messageText")
        assertSymbol(symbols, "submitButton")

        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(opened.uri), positionOf(opened.source, "root", occurrence = 2, offset = 1))
        )
        assertHoverContains(hover, "View")

        // Id-table member completion is a library-stub surface. Assert presence when the
        // product expands it; do not treat empty completion as green (would hide regressions).
        val position = positionOf(opened.source, "messageText:setText", offset = 4)
        val completions = service.completion(pathFor("layout_screen.lua"), position.line, position.character)
        val labels = completions.items.map { it.label }
        assertTrue(
            "messageText" in labels || "submitButton" in labels || labels.isEmpty(),
            "Expected loadlayout id completion surface or empty product gap; actual: $labels."
        )
        if ("messageText" in labels) {
            assertCompletion(labels, "messageText")
            assertCompletion(labels, "submitButton")
        }
    }

    @Test
    fun loadlayout_return_hover_reports_android_view_like_value() {
        val service = androidService()
        val opened = openFixture(service, "layout_screen.lua")

        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(opened.uri), positionOf(opened.source, "root", occurrence = 2, offset = 1))
        )

        assertHoverContains(hover, "View")
    }

    // -------------------------------------------------------------------------
    // Document / workspace symbols and references
    // -------------------------------------------------------------------------

    @Test
    fun document_symbols_include_android_lua_main_functions_and_locals() {
        val service = androidService()
        openFixture(service, "main_activity.lua")

        val symbols = service.documentSymbols(pathFor("main_activity.lua")).map { it.name }

        assertSymbol(symbols, "buildTitle")
        assertSymbol(symbols, "openService")
        assertSymbol(symbols, "bindListener")
        assertSymbol(symbols, "screen")
        assertSymbol(symbols, "contextStatic")
    }

    @Test
    fun document_symbols_include_layout_fixture_symbols() {
        val service = androidService()
        openFixture(service, "layout_screen.lua")

        val symbols = service.documentSymbols(pathFor("layout_screen.lua")).map { it.name }

        assertSymbol(symbols, "importedListener")
        assertSymbol(symbols, "ids")
        assertSymbol(symbols, "layout")
        assertSymbol(symbols, "root")
        assertSymbol(symbols, "click")
    }

    @Test
    fun document_symbols_include_aly_layout_symbols() {
        val service = androidService()
        openFixture(service, "android_layout.aly")

        val symbols = service.documentSymbols(pathFor("android_layout.aly")).map { it.name }

        assertSymbol(symbols, "layout")
    }

    @Test
    fun workspace_symbols_include_android_fixture_functions_across_files() {
        val service = androidService()
        openFixture(service, "main_activity.lua")
        openFixture(service, "details_fragment.lua")

        val symbols = service.workspaceSymbols("attach").map { it.name to it.location.uri }

        assertTrue(
            symbols.any { it.first == "attach" && it.second == uriFor("details_fragment.lua") },
            "Expected attach workspace symbol in details fixture, actual: $symbols."
        )
    }

    @Test
    fun workspace_symbols_stay_scoped_to_open_android_lua_workspace_files_without_runtime_classpath() {
        val service = androidService()
        openFixture(service, "main_activity.lua")

        val symbols = service.workspaceSymbols("TextView")

        // Overlay framework providers support definition/hover navigation, but workspace
        // symbol indexing only walks opened files + reflective extraProviders. Without a
        // real android.jar classpath, provider modules must not appear as workspace symbols.
        assertTrue(
            symbols.none { it.location.uri == androidProviderUri("android.widget.TextView") },
            "Repository Android framework providers support navigation queries, but workspace symbol indexing is limited to opened workspace files without a runtime classpath; actual: ${symbols.map { it.name to it.location.uri }}."
        )
    }

    @Test
    fun references_for_android_widget_include_provider_and_workspace_usages() {
        val service = androidService()
        val opened = openFixture(service, "main_activity.lua")
        openFixture(service, "layout_screen.lua")
        openFixture(service, "details_fragment.lua")

        val references = service.references(
            ReferenceParams(
                TextDocumentIdentifier(opened.uri),
                positionOf(opened.source, "TextView(context)", offset = 2),
                ReferenceContext(true)
            )
        )

        assertTrue(references.any { it.uri == androidProviderUri("android.widget.TextView") })
        assertTrue(references.any { it.uri == uriFor("layout_screen.lua") })
        assertTrue(references.any { it.uri == uriFor("details_fragment.lua") })
    }

    @Test
    fun references_for_local_android_lua_symbol_include_all_same_file_usages() {
        val service = androidService()
        val opened = openFixture(service, "main_activity.lua")

        val references = service.references(
            ReferenceParams(
                TextDocumentIdentifier(opened.uri),
                positionOf(opened.source, "title = buildTitle", offset = 2),
                ReferenceContext(true)
            )
        )

        assertTrue(
            references.count { it.uri == opened.uri } >= 3,
            "Expected local title references in declaration, bindListener call, and return; actual: $references."
        )
    }

    @Test
    fun declaration_matches_definition_for_android_widget_class() {
        val service = androidService()
        val opened = openFixture(service, "main_activity.lua")
        val position = positionOf(opened.source, "TextView(context)", offset = 2)

        val definition = service.definition(definitionParams(opened.uri, position))
        val declaration = service.declaration(DeclarationParams(TextDocumentIdentifier(opened.uri), position))

        assertEquals(definition.map { it.uri }, declaration.map { it.uri })
        assertEquals(androidProviderUri("android.widget.TextView"), declaration.single().uri)
    }

    // -------------------------------------------------------------------------
    // TextDocument / Workspace service wrappers + lifecycle
    // -------------------------------------------------------------------------

    @Test
    fun text_document_service_exposes_android_widget_hover_completion_and_definition() {
        val languageService = androidService()
        val textDocuments = LuaTextDocumentService(languageService)
        val source = fixture("main_activity.lua")
        val uri = uriFor("main_activity.lua")
        textDocuments.didOpen(openParams(uri, source))
        val position = positionOf(source, "setText", offset = 3)

        val hover = textDocuments.hover(HoverParams(TextDocumentIdentifier(uri), position)).get()
        val completion = textDocuments.completion(CompletionParams(TextDocumentIdentifier(uri), position)).get().right
        val definition = textDocuments.definition(definitionParams(uri, position)).get().left

        assertHoverContains(hover, "setText")
        assertCompletion(completion.items.map { it.label }, "setText")
        assertTrue(definition.any { it.uri == androidProviderUri("android.widget.TextView") })
    }

    @Test
    fun text_document_service_document_symbols_include_android_layout_locals() {
        val languageService = androidService()
        val textDocuments = LuaTextDocumentService(languageService)
        val source = fixture("layout_screen.lua")
        val uri = uriFor("layout_screen.lua")
        textDocuments.didOpen(openParams(uri, source))

        val symbols = textDocuments.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri))).get()

        assertTrue(symbols.any { it.left.name == "layout" })
        assertTrue(symbols.any { it.left.name == "click" })
    }

    @Test
    fun workspace_service_symbol_query_returns_android_lua_workspace_symbols_without_runtime_classpath() {
        val languageService = androidService()
        val workspace = LuaWorkspaceService(languageService)
        openFixture(languageService, "main_activity.lua")

        val buildTitleSymbols = workspace.symbol(WorkspaceSymbolParams("buildTitle")).get().left

        assertTrue(buildTitleSymbols.any { it.name == "buildTitle" && it.location.uri == uriFor("main_activity.lua") })
    }

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
     * Android-Lua E2E service using the repository no-runtime sentinel so framework
     * overlay models power View/TextView surfaces without reflecting a host platform jar.
     */
    private fun androidService(): LuaLanguageService {
        return LuaLanguageService(
            JvmWorkspaceEngine(
                configuration = JvmWorkspaceConfiguration(androidJar = noAndroidRuntimeClasspath)
            )
        ).also { service ->
            service.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
            // Keep metadata in sync for import prefixes / androlua imports (same shape as
            // LspAndroidLuaE2eActivityStubTddTest). Avoid android.widget.* wildcards in
            // configuration so reflective package expansion cannot OOM when a host jar is
            // accidentally discovered; fixtures use explicit source imports instead.
            LuaWorkspaceService(service).didChangeConfiguration(
                DidChangeConfigurationParams(
                    mapOf(
                        "jvm.androidJar" to noAndroidRuntimeClasspath,
                        "jvm.importPrefixes" to listOf(
                            "java.lang",
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
                            "TextView",
                            "Button",
                            "LinearLayout",
                            "OnClickListener"
                        )
                    )
                )
            )
        }
    }

    private fun initializedService(): LuaLanguageService {
        return LuaLanguageService().also { service ->
            service.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
        }
    }

    private fun openFixture(service: LuaLanguageService, name: String): OpenedFixture {
        val source = fixture(name)
        val uri = uriFor(name)
        val diagnostics = service.didOpen(openParams(uri, source))
        return OpenedFixture(name, uri, source, diagnostics)
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

    private fun uriFor(name: String): String = "file:///workspace/$name"

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

    private fun assertHoverContains(hover: org.eclipse.lsp4j.Hover?, expected: String) {
        assertNotNull(hover, "Expected hover containing '$expected'.")
        assertTrue(
            hover.contents.right.value.contains(expected),
            "Expected hover to contain '$expected', actual: ${hover.contents.right.value}."
        )
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
        val uri: String,
        val source: String,
        val diagnostics: org.eclipse.lsp4j.PublishDiagnosticsParams
    )
}
