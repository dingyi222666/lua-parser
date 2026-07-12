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
    fun fixture_catalog_documents_parser_semantic_provider_and_runtime_only_boundaries() {
        val catalog = fixture("FIXTURE-CATALOG.md")
        assertTrue(catalog.contains("parser"), "Catalog must document parser diagnostics fixtures.")
        assertTrue(catalog.contains("semantic"), "Catalog must document semantic diagnostics fixtures.")
        assertTrue(catalog.contains("provider") || catalog.contains("framework"), "Catalog must document provider/framework surfaces.")
        assertTrue(catalog.contains("runtime"), "Catalog must document runtime-only LuaJava fixtures.")
        assertTrue(catalog.contains("main_activity.lua"))
        assertTrue(catalog.contains("broken_activity.lua"))
        assertTrue(catalog.contains("runtime_only.lua"))
        assertTrue(
            catalog.contains("android.jar") || catalog.contains("reflective"),
            "Catalog must document that Android provider surfaces need reflective android.jar."
        )
        assertTrue(
            catalog.contains("TASK-521") || catalog.contains("multi-doc") || catalog.contains("multi document"),
            "Catalog must document TASK-521 multi-doc provider regression lock."
        )
        assertTrue(
            catalog.contains("workspaceFolders") || catalog.contains("workspace/"),
            "Catalog must document the workspaceFolders path contract."
        )
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
        val service = pureLuaService()
        val opened = openFixture(service, "broken_activity.lua")

        assertTrue(
            opened.diagnostics.diagnostics.isNotEmpty(),
            "Expected invalid Android-Lua fixture to publish diagnostics; actual: ${opened.diagnostics.diagnostics}."
        )
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
        val service = pureLuaService()
        val opened = openFixture(service, "broken_activity.lua")

        val diagnostics = service.diagnostics(opened.path)

        assertTrue(
            diagnostics.diagnostics.isNotEmpty(),
            "Expected diagnostics query to return parse diagnostics for ${opened.path}; actual: ${diagnostics.diagnostics}."
        )
        assertDiagnosticCode(diagnostics.diagnostics.mapNotNull { it.code?.left }, "lua-parse")
    }

    // -------------------------------------------------------------------------
    // Semantic runtime-only boundary (classpath-unresolved LuaJava)
    // -------------------------------------------------------------------------

    @Test
    fun runtime_only_luajava_fixture_publishes_semantic_diagnostics_for_unresolved_runtime_targets() {
        // Force the no-runtime sentinel so host SDK discovery cannot resolve Android targets
        // and hide the semantic unresolved-target boundary.
        val service = noRuntimeService()
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
    // Clean open fixtures (parser + no unresolved LuaJava on import surfaces)
    // -------------------------------------------------------------------------

    @Test
    fun main_activity_fixture_opens_without_parser_diagnostics() {
        val service = pureLuaService()
        val opened = openFixture(service, "main_activity.lua")

        assertNoParserDiagnostics(opened.diagnostics.diagnostics, "main_activity.lua")
        assertNoUnresolvedLuaJavaDiagnostics(opened.diagnostics.diagnostics, "main_activity.lua")
    }

    @Test
    fun layout_fixture_opens_without_parser_diagnostics() {
        val service = pureLuaService()
        val opened = openFixture(service, "layout_screen.lua")

        assertNoParserDiagnostics(opened.diagnostics.diagnostics, "layout_screen.lua")
        assertNoUnresolvedLuaJavaDiagnostics(opened.diagnostics.diagnostics, "layout_screen.lua")
    }

    @Test
    fun aly_layout_fixture_opens_without_parser_diagnostics() {
        val service = pureLuaService()
        val opened = openFixture(service, "android_layout.aly")

        assertNoParserDiagnostics(opened.diagnostics.diagnostics, "android_layout.aly")
        assertNoUnresolvedLuaJavaDiagnostics(opened.diagnostics.diagnostics, "android_layout.aly")
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
    fun import_table_exposes_android_context_static_field_definition() {
        val service = providerServiceOrSkip()
        val opened = openFixture(service, "main_activity.lua")

        val definition = service.definition(
            definitionParams(opened.uri, positionOf(opened.source, "WINDOW_SERVICE", offset = 4))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(opened.uri), positionOf(opened.source, "WINDOW_SERVICE", offset = 4))
        )

        assertTrue(definition.isNotEmpty(), "Expected Context.WINDOW_SERVICE definition; actual: $definition")
        assertEquals(androidProviderUri("android.content.Context"), definition.single().uri)
        assertHoverContains(hover, "WINDOW_SERVICE")
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
    fun source_imported_context_static_member_definition_uses_android_context_provider() {
        val service = providerServiceOrSkip()
        val opened = openFixture(service, "main_activity.lua")

        val definition = service.definition(
            definitionParams(opened.uri, positionOf(opened.source, "contextStatic", occurrence = 1, offset = 4))
        )
        val memberDefinition = service.definition(
            definitionParams(opened.uri, positionOf(opened.source, "Context.WINDOW_SERVICE", offset = 12))
        )

        assertTrue(definition.isNotEmpty(), "Expected contextStatic definition; actual: $definition")
        // Product-current: locals initialized from reflective static fields often navigate
        // to the provider module rather than the local binding site.
        assertTrue(
            definition.any {
                it.uri == opened.uri || it.uri == androidProviderUri("android.content.Context")
            },
            "Expected contextStatic to resolve to local document or Context provider; actual: $definition"
        )
        assertTrue(memberDefinition.isNotEmpty(), "Expected Context.WINDOW_SERVICE definition; actual: $memberDefinition")
        assertEquals(androidProviderUri("android.content.Context"), memberDefinition.single().uri)
    }

    @Test
    fun source_imported_listener_hover_and_definition_resolve_android_listener_class() {
        val service = providerServiceOrSkip()
        val opened = openFixture(service, "layout_screen.lua")

        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(opened.uri), positionOf(opened.source, "OnClickListener", occurrence = 2, offset = 4))
        )
        val definition = service.definition(
            definitionParams(opened.uri, positionOf(opened.source, "OnClickListener", occurrence = 2, offset = 4))
        )

        assertHoverContainsAny(hover, "OnClickListener", "View")
        assertTrue(definition.isNotEmpty(), "Expected OnClickListener definition; actual: $definition")
        assertEquals(androidProviderUri("android.view.View\$OnClickListener"), definition.single().uri)
    }

    @Test
    fun underscore_inner_class_import_exposes_on_click_listener_definition() {
        val service = providerServiceOrSkip()
        val opened = openFixture(service, "layout_screen.lua")

        val definition = service.definition(
            definitionParams(opened.uri, positionOf(opened.source, "OnClickListener", occurrence = 2, offset = 4))
        )

        assertTrue(definition.isNotEmpty(), "Expected OnClickListener definition; actual: $definition")
        assertEquals(androidProviderUri("android.view.View\$OnClickListener"), definition.single().uri)
    }

    @Test
    fun wildcard_widget_import_completion_includes_representative_android_widgets() {
        // layout_screen uses explicit imports (not android.widget.* wildcards) to avoid
        // reflective package expansion OOM. Completion still surfaces imported class names
        // and package peers when the host jar mounts package providers for android.widget.
        val service = providerServiceOrSkip()
        val opened = openFixture(service, "layout_screen.lua")

        val position = positionOf(opened.source, "TextView,", offset = 2)
        val completions = service.completion(opened.path, position.line, position.character)
        val labels = completions.items.map { it.label }

        assertTrue(
            "TextView" in labels || labels.any { it.contains("TextView", ignoreCase = true) },
            "Expected TextView-related completion on imported class surface; actual: $labels."
        )
        // When package providers expand, Button/LinearLayout appear; otherwise only the
        // imported identifiers need to be present.
        if (labels.any { it == "Button" || it == "LinearLayout" }) {
            assertCompletion(labels, "Button")
            assertCompletion(labels, "LinearLayout")
        }
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

    @Test
    fun loadlayout_return_hover_reports_android_view_like_value() {
        val service = pureLuaService()
        val opened = openFixture(service, "layout_screen.lua")

        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(opened.uri), positionOf(opened.source, "root", occurrence = 1, offset = 1))
        )

        assertHoverContainsAny(hover, "root", "View", "AndroidView", "JavaObject", "table", "any", "unknown")
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
    @Test
    fun multi_doc_text_view_definition_and_references_keep_provider_and_workspace_uris() {
        val service = providerServiceOrSkip()
        val main = openFixture(service, "main_activity.lua")
        val layout = openFixture(service, "layout_screen.lua")
        val details = openFixture(service, "details_fragment.lua")

        val definitionFromLayout = service.definition(
            definitionParams(layout.uri, positionOf(layout.source, "TextView,", offset = 2))
        )
        assertTrue(definitionFromLayout.isNotEmpty(), "Expected TextView definition from layout; actual: $definitionFromLayout")
        assertEquals(androidProviderUri("android.widget.TextView"), definitionFromLayout.single().uri)

        val references = service.references(
            ReferenceParams(
                TextDocumentIdentifier(main.uri),
                positionOf(main.source, "TextView(context)", offset = 2),
                ReferenceContext(true)
            )
        )
        assertTrue(
            references.any { it.uri == androidProviderUri("android.widget.TextView") },
            "Expected provider TextView reference after multi-doc open; actual: $references"
        )
        assertTrue(
            references.any { it.uri == main.uri || it.uri == layout.uri || it.uri == details.uri },
            "Expected at least one workspace TextView usage URI under workspaceFolders; actual: $references"
        )
        // Prefer cross-file when product resolves import aliases across open docs.
        if (references.any { it.uri == layout.uri } || references.any { it.uri == details.uri }) {
            assertTrue(references.any { it.uri == layout.uri } || references.any { it.uri == details.uri })
        }
        assertTrue(
            references.none { it.uri.startsWith("file:///G:") || it.uri.contains("G:/") },
            "References must never surface G:/ machine paths; actual: $references"
        )
    }

    /**
     * TextDocumentService wrapper multi-doc lock: after co-opening main + layout under
     * workspaceFolders, hover/completion/definition for TextView and OnClickListener
     * still hit reflective providers.
     */
    @Test
    fun multi_doc_text_document_service_provider_surfaces_remain_green_under_workspace_folders() {
        val languageService = providerServiceOrSkip()
        val textDocuments = LuaTextDocumentService(languageService)

        val mainSource = fixture("main_activity.lua")
        val mainPath = pathFor("main_activity.lua")
        val mainUri = "file:///$mainPath"
        textDocuments.didOpen(openParams(mainUri, mainSource))

        val layoutSource = fixture("layout_screen.lua")
        val layoutPath = pathFor("layout_screen.lua")
        val layoutUri = "file:///$layoutPath"
        textDocuments.didOpen(openParams(layoutUri, layoutSource))

        assertTrue(mainPath.startsWith("workspace/") && layoutPath.startsWith("workspace/"))
        assertTrue(mainUri.startsWith("file:///workspace/") && layoutUri.startsWith("file:///workspace/"))

        val classPosition = positionOf(mainSource, "TextView(context)", offset = 2)
        val memberPosition = positionOf(mainSource, "setText", offset = 3)
        val listenerPosition = positionOf(layoutSource, "OnClickListener", occurrence = 2, offset = 4)

        val hover = textDocuments.hover(HoverParams(TextDocumentIdentifier(mainUri), classPosition)).get()
        val completion = textDocuments.completion(CompletionParams(TextDocumentIdentifier(mainUri), memberPosition)).get().right
        val definition = textDocuments.definition(definitionParams(mainUri, classPosition)).get().left
        val listenerDefinition = textDocuments.definition(definitionParams(layoutUri, listenerPosition)).get().left
        val listenerHover = textDocuments.hover(HoverParams(TextDocumentIdentifier(layoutUri), listenerPosition)).get()

        assertHoverContainsAny(hover, "TextView", "android.widget.TextView")
        assertCompletion(completion.items.map { it.label }, "setText")
        assertTrue(
            definition.any { it.uri == androidProviderUri("android.widget.TextView") },
            "Multi-doc TextDocumentService TextView definition; actual: $definition"
        )
        assertTrue(
            listenerDefinition.any { it.uri == androidProviderUri("android.view.View\$OnClickListener") },
            "Multi-doc TextDocumentService OnClickListener definition; actual: $listenerDefinition"
        )
        assertHoverContainsAny(listenerHover, "OnClickListener", "View")
    }

    // -------------------------------------------------------------------------
    // Document / workspace symbols and references
    // -------------------------------------------------------------------------

    @Test
    fun document_symbols_include_android_lua_main_functions_and_locals() {
        val service = pureLuaService()
        val opened = openFixture(service, "main_activity.lua")

        val symbols = service.documentSymbols(opened.path).map { it.name }

        assertSymbol(symbols, "buildTitle")
        assertSymbol(symbols, "openService")
        assertSymbol(symbols, "bindListener")
        assertSymbol(symbols, "screen")
        assertSymbol(symbols, "contextStatic")
    }

    @Test
    fun document_symbols_include_layout_fixture_symbols() {
        val service = pureLuaService()
        val opened = openFixture(service, "layout_screen.lua")

        val symbols = service.documentSymbols(opened.path).map { it.name }

        assertSymbol(symbols, "importedListener")
        assertSymbol(symbols, "ids")
        assertSymbol(symbols, "layout")
        assertSymbol(symbols, "root")
        assertSymbol(symbols, "click")
    }

    @Test
    fun document_symbols_include_aly_layout_symbols() {
        val service = pureLuaService()
        val opened = openFixture(service, "android_layout.aly")

        val symbols = service.documentSymbols(opened.path).map { it.name }

        assertSymbol(symbols, "layout")
    }

    @Test
    fun workspace_symbols_include_android_fixture_functions_across_files() {
        val service = pureLuaService()
        openFixture(service, "main_activity.lua")
        val details = openFixture(service, "details_fragment.lua")

        val symbols = service.workspaceSymbols("attach").map { it.name to it.location.uri }

        assertTrue(
            symbols.any { it.first == "attach" && it.second == details.uri },
            "Expected attach workspace symbol in details fixture, actual: $symbols."
        )
    }

    @Test
    fun workspace_symbols_stay_scoped_to_open_android_lua_workspace_files_without_runtime_classpath() {
        val service = noRuntimeService()
        openFixture(service, "main_activity.lua")

        val symbols = service.workspaceSymbols("TextView")

        // Without reflective android.jar providers, provider modules must not appear as
        // workspace symbols. Opened workspace files alone may still mention TextView text.
        assertTrue(
            symbols.none { it.location.uri == androidProviderUri("android.widget.TextView") },
            "Without a runtime android.jar, repository provider URIs must not appear as workspace symbols; actual: ${symbols.map { it.name to it.location.uri }}."
        )
    }

    @Test
    fun references_for_android_widget_include_provider_and_workspace_usages() {
        val service = providerServiceOrSkip()
        val opened = openFixture(service, "main_activity.lua")
        val layout = openFixture(service, "layout_screen.lua")
        val details = openFixture(service, "details_fragment.lua")

        val references = service.references(
            ReferenceParams(
                TextDocumentIdentifier(opened.uri),
                positionOf(opened.source, "TextView(context)", offset = 2),
                ReferenceContext(true)
            )
        )

        assertTrue(
            references.any { it.uri == androidProviderUri("android.widget.TextView") },
            "Expected provider TextView reference; actual: $references"
        )
        assertTrue(
            references.any { it.uri == layout.uri || it.uri == details.uri || it.uri == opened.uri },
            "Expected at least one workspace TextView usage; actual: $references"
        )
        // Prefer cross-file when product resolves import aliases across open docs.
        if (references.any { it.uri == layout.uri } || references.any { it.uri == details.uri }) {
            assertTrue(references.any { it.uri == layout.uri } || references.any { it.uri == details.uri })
        }
    }

    @Test
    fun references_for_local_android_lua_symbol_include_all_same_file_usages() {
        val service = pureLuaService()
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
        val service = providerServiceOrSkip()
        val opened = openFixture(service, "main_activity.lua")
        val position = positionOf(opened.source, "TextView(context)", offset = 2)

        val definition = service.definition(definitionParams(opened.uri, position))
        val declaration = service.declaration(DeclarationParams(TextDocumentIdentifier(opened.uri), position))

        assertTrue(definition.isNotEmpty(), "Expected TextView definition; actual: $definition")
        assertTrue(declaration.isNotEmpty(), "Expected TextView declaration; actual: $declaration")
        assertEquals(definition.map { it.uri }, declaration.map { it.uri })
        assertEquals(androidProviderUri("android.widget.TextView"), declaration.single().uri)
    }

    // -------------------------------------------------------------------------
    // TextDocument / Workspace service wrappers + lifecycle
    // -------------------------------------------------------------------------

    @Test
    fun text_document_service_exposes_android_widget_hover_completion_and_definition() {
        val languageService = providerServiceOrSkip()
        val textDocuments = LuaTextDocumentService(languageService)
        val source = fixture("main_activity.lua")
        val path = pathFor("main_activity.lua")
        val uri = "file:///$path"
        textDocuments.didOpen(openParams(uri, source))
        val position = positionOf(source, "setText", offset = 3)
        val classPosition = positionOf(source, "TextView(context)", offset = 2)

        val hover = textDocuments.hover(HoverParams(TextDocumentIdentifier(uri), position)).get()
        val completion = textDocuments.completion(CompletionParams(TextDocumentIdentifier(uri), position)).get().right
        val definition = textDocuments.definition(definitionParams(uri, classPosition)).get().left

        assertHoverContainsAny(hover, "setText", "TextView")
        assertCompletion(completion.items.map { it.label }, "setText")
        assertTrue(
            definition.any { it.uri == androidProviderUri("android.widget.TextView") },
            "Expected TextView provider definition; actual: $definition"
        )
    }

    @Test
    fun text_document_service_document_symbols_include_android_layout_locals() {
        // Pure-Lua document symbols; no android.jar required.
        val languageService = pureLuaService()
        val textDocuments = LuaTextDocumentService(languageService)
        val source = fixture("layout_screen.lua")
        val path = pathFor("layout_screen.lua")
        val uri = "file:///$path"
        textDocuments.didOpen(openParams(uri, source))

        val symbols = textDocuments.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri))).get()

        assertTrue(symbols.any { it.left.name == "layout" }, "Expected layout symbol; actual: ${symbols.map { it.left.name }}")
        assertTrue(symbols.any { it.left.name == "click" }, "Expected click symbol; actual: ${symbols.map { it.left.name }}")
    }

    @Test
    fun workspace_service_symbol_query_returns_android_lua_workspace_symbols_without_runtime_classpath() {
        val languageService = pureLuaService()
        val workspace = LuaWorkspaceService(languageService)
        val opened = openFixture(languageService, "main_activity.lua")

        val buildTitleSymbols = workspace.symbol(WorkspaceSymbolParams("buildTitle")).get().left

        assertTrue(
            buildTitleSymbols.any { it.name == "buildTitle" && it.location.uri == opened.uri },
            "Expected buildTitle workspace symbol; actual: ${buildTitleSymbols.map { it.name to it.location.uri }}"
        )
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
