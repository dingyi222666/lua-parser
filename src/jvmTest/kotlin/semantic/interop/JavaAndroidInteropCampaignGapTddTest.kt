package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaAndroidInteropCampaignGapTddTest {
    private val androidJar = File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)

    @Test
    fun resource_alias_fixture_records_luajava_helper_loads() {
        val harness = jvmHarness("luajava_alias_campaign.lua" to resourceText("luajava_alias_campaign.lua"))

        val loads = jvmClassLoads(harness, "luajava_alias_campaign.lua")

        assertTrue(loads.any { it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL && it.target == "java.io.File" })
        assertTrue(loads.any { it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL && it.target == "java.io.File" })
        assertTrue(loads.any { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL && it.target == "java.lang.Runnable" })
        assertTrue(loads.any { it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL && it.target == "java.lang.System" })
    }

    @Test
    fun resource_alias_fixture_new_instance_hover_reports_file() {
        val harness = jvmHarness("luajava_alias_campaign.lua" to resourceText("luajava_alias_campaign.lua"))

        assertHoverType(harness, "luajava_alias_campaign.lua", "newFile", "java.io.File", occurrence = 3)
        assertHoverType(harness, "luajava_alias_campaign.lua", "fileName", "string", occurrence = 2)
    }

    @Test
    fun resource_alias_fixture_proxy_member_definition_points_to_runnable_provider() {
        val harness = jvmHarness("luajava_alias_campaign.lua" to resourceText("luajava_alias_campaign.lua"))

        val definitions = harness.queries.gotoDefinition(
            harness.path("luajava_alias_campaign.lua"),
            memberPosition(harness, "luajava_alias_campaign.lua", "runnable.run")
        )

        assertEquals(listOf(harness.path("__jvm__/classes/java/lang/Runnable.lua")), definitions.map { it.path })
        assertCallableMember(harness, "luajava_alias_campaign.lua", "runnable.run")
    }

    @Test
    fun resource_alias_fixture_load_lib_static_method_is_callable() {
        val harness = jvmHarness("luajava_alias_campaign.lua" to resourceText("luajava_alias_campaign.lua"))

        assertCallableHover(harness, "luajava_alias_campaign.lua", "currentTimeMillis", occurrence = 2)
    }

    @Test
    fun new_instance_alias_file_parent_chain_returns_string() {
        val harness = jvmHarness(
            "main.lua" to """
                local newInstance = luajava.newInstance
                local make = newInstance
                local name = make("java.io.File", "src/main/kotlin/Main.kt").getParentFile().getName()
                return name
            """.trimIndent()
        )

        assertHoverType(harness, "main.lua", "name", "string", occurrence = 2)
    }

    @Test
    fun bind_class_inner_map_entry_static_method_is_callable() {
        val harness = jvmHarness(
            "main.lua" to """
                local Entry = luajava.bindClass("java.util.Map${'$'}Entry")
                local comparing = Entry.comparingByKey
                return comparing
            """.trimIndent()
        )

        assertProviderPath(harness, "java.util.Map\$Entry")
        assertCallableHover(harness, "main.lua", "comparing", occurrence = 2)
    }

    @Test
    fun create_proxy_intersection_completion_includes_each_interface_method() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", "java.util.Comparator", {})
                local compare = proxy.compare
                return compare
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            memberPosition(harness, "main.lua", "proxy.compare")
        )

        assertCompletion(completions, "compare", CompletionItemKind.METHOD)
        assertCompletion(completions, "run", CompletionItemKind.METHOD)
    }

    @Test
    fun bind_class_reflection_array_return_reports_locale_array() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local locales = Locale.getAvailableLocales()
                return locales
            """.trimIndent()
        )

        assertHoverTypeContains(harness, "main.lua", "locales", "java.util.Locale[]", occurrence = 2)
    }

    @Test
    fun bind_class_primitive_array_return_reports_number_array() {
        val harness = jvmHarness(
            "main.lua" to """
                local String = luajava.bindClass("java.lang.String")
                local bytes = String("abc").getBytes()
                return bytes
            """.trimIndent()
        )

        assertHoverType(harness, "main.lua", "bytes", "number[]", occurrence = 2)
    }

    @Test
    fun load_lib_static_locale_field_reports_locale_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local root = luajava.loadLib("java.util.Locale", "ROOT")
                return root
            """.trimIndent()
        )

        assertHoverType(harness, "main.lua", "root", "java.util.Locale", occurrence = 2)
    }

    @Test
    fun resource_reflection_fixture_chained_static_and_instance_calls_are_typed() {
        val harness = jvmHarness(
            "reflection_campaign.lua" to resourceText("reflection_campaign.lua"),
            classes = setOf("java.util.List")
        )

        assertHoverType(harness, "reflection_campaign.lua", "count", "number", occurrence = 2)
        assertHoverTypeContains(harness, "reflection_campaign.lua", "locales", "java.util.Locale[]", occurrence = 2)
        assertHoverType(harness, "reflection_campaign.lua", "parsed", "number", occurrence = 2)
        assertHoverType(harness, "reflection_campaign.lua", "firstTag", "string", occurrence = 2)
    }

    @Test
    fun resource_reflection_fixture_completion_after_list_result_includes_size() {
        val harness = jvmHarness(
            "reflection_campaign.lua" to resourceText("reflection_campaign.lua"),
            classes = setOf("java.util.List")
        )

        val completions = harness.queries.completions(
            harness.path("reflection_campaign.lua"),
            memberPosition(harness, "reflection_campaign.lua", "values.size")
        )

        assertCompletion(completions, "size", CompletionItemKind.METHOD)
        assertCompletion(completions, "isEmpty", CompletionItemKind.METHOD)
    }

    @Test
    fun missing_reflection_member_reports_invalid_member_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local String = luajava.bindClass("java.lang.String")
                local value = String("abc").missingInteropMember()
                return value
            """.trimIndent()
        )

        assertInvalidMemberDiagnostic(harness, "main.lua", "missingInteropMember")
    }

    @Test
    fun luajava_helper_fixture_exposes_array_context_and_override_completions() {
        val harness = jvmHarness("luajava_helper_campaign.lua" to resourceText("luajava_helper_campaign.lua"))

        val completions = harness.queries.completions(
            harness.path("luajava_helper_campaign.lua"),
            memberPosition(harness, "luajava_helper_campaign.lua", "luajava.createArray")
        )

        assertCompletion(completions, "createArray", CompletionItemKind.METHOD)
        assertCompletion(completions, "newArray", CompletionItemKind.METHOD)
        assertCompletion(completions, "astable", CompletionItemKind.METHOD)
        assertCompletion(completions, "getContext", CompletionItemKind.METHOD)
        assertCompletion(completions, "override", CompletionItemKind.METHOD)
    }

    @Test
    fun luajava_get_context_helper_returns_android_lua_context() {
        // Inline fixture keeps a real `context.getLuaDir` member site for completion probes
        // (resource luajava_helper_campaign.lua has no trailing member access).
        val harness = jvmHarness(
            "main.lua" to """
                require "import"
                local context = luajava.getContext()
                local dir = context.getLuaDir
                return context, dir
            """.trimIndent()
        )

        // TASK-575 hard-lock: getContext() result is non-unknown AndroidLuaContext when AndroLua overlay is active.
        assertHoverTypeContains(harness, "main.lua", "context", "AndroidLuaContext", occurrence = 2)

        // Completions after getContext on the typed local must include overlay-documented members.
        val completions = harness.queries.completions(
            harness.path("main.lua"),
            memberPosition(harness, "main.lua", "context.getLuaDir")
        )
        val labels = completions.map { it.label }.toSet()
        val overlayMembers = listOf("getLuaDir", "getLuaPath", "getContext", "setContentView", "getSystemService")
        assertTrue(
            overlayMembers.any { it in labels },
            "Expected AndroidLuaContext overlay members after getContext; got $labels"
        )
        // Do not invent jar-only android.content.Context APIs without stubs/jar.
        val inventedJarOnly = listOf("getAssets", "getPackageManager", "startActivity", "registerReceiver")
        assertTrue(
            inventedJarOnly.none { it in labels },
            "Must not invent android.jar-only members without stubs/jar; got $labels"
        )

        // When android.jar is present, completion still must include the overlay surface (never drop it).
        if (androidJar.isFile) {
            withAndroidHarness(
                "main.lua" to """
                    require "import"
                    local context = luajava.getContext()
                    local dir = context.getLuaDir
                    return context, dir
                """.trimIndent()
            ) { androidHarness ->
                assertHoverTypeContains(androidHarness, "main.lua", "context", "AndroidLuaContext", occurrence = 2)
                val androidCompletions = androidHarness.queries.completions(
                    androidHarness.path("main.lua"),
                    memberPosition(androidHarness, "main.lua", "context.getLuaDir")
                )
                val androidLabels = androidCompletions.map { it.label }.toSet()
                assertTrue(
                    overlayMembers.any { it in androidLabels },
                    "With android.jar present, expected overlay context members after getContext; got $androidLabels"
                )
            }
        }
    }

    @Test
    fun android_resource_fixture_resolves_text_view_constructor_when_android_jar_available() {
        withAndroidHarness("android_view_campaign.lua" to resourceText("android_view_campaign.lua")) { harness ->
            assertHoverType(harness, "android_view_campaign.lua", "textView", "android.widget.TextView", occurrence = 4)
            assertCallableMember(harness, "android_view_campaign.lua", "textView.setText")
        }
    }

    @Test
    fun android_resource_fixture_wildcard_view_definition_when_android_jar_available() {
        withAndroidHarness("android_view_campaign.lua" to resourceText("android_view_campaign.lua")) { harness ->
            val definitions = harness.queries.gotoDefinition(
                harness.path("android_view_campaign.lua"),
                positionIn(harness, "android_view_campaign.lua", "View.VISIBLE")
            )
            val hover = assertNotNull(
                harness.queries.hover(
                    harness.path("android_view_campaign.lua"),
                    memberPosition(harness, "android_view_campaign.lua", "View.VISIBLE")
                )
            )

            assertEquals(listOf(harness.path("__jvm__/classes/android/view/View.lua")), definitions.map { it.path })
            assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
            assertEquals("number", hover.typeInfo?.displayName)
        }
    }

    @Test
    fun android_create_proxy_listener_member_definition_when_android_jar_available() {
        withAndroidHarness(
            "main.lua" to """
                local listener = luajava.createProxy("android.view.View.OnClickListener", {})
                local click = listener.onClick
                return click
            """.trimIndent(),
            classes = setOf("android.view.View\$OnClickListener")
        ) { harness ->
            val definitions = harness.queries.gotoDefinition(
                harness.path("main.lua"),
                memberPosition(harness, "main.lua", "listener.onClick")
            )

            assertEquals(listOf(harness.path("__jvm__/classes/android/view/View\$OnClickListener.lua")), definitions.map { it.path })
            assertCallableMember(harness, "main.lua", "listener.onClick")
        }
    }

    @Test
    fun android_uri_builder_resource_chain_returns_string_when_android_jar_available() {
        withAndroidHarness(
            "android_uri_campaign.lua" to resourceText("android_uri_campaign.lua"),
            classes = setOf("android.net.Uri", "android.net.Uri\$Builder")
        ) { harness ->
            assertHoverType(harness, "android_uri_campaign.lua", "path", "string", occurrence = 2)
            assertNoDiagnostics(harness, "android_uri_campaign.lua")
        }
    }

    @Test
    fun android_inner_class_prefix_metadata_resolves_listener_when_android_jar_available() {
        withAndroidHarness(
            "main.lua" to """
                import "OnClickListener"
                local listenerClass = OnClickListener
                return listenerClass
            """.trimIndent(),
            metadata = mapOf(JvmWorkspaceConfiguration.IMPORT_PREFIXES_METADATA_KEY to "android.view.View")
        ) { harness ->
            assertProviderPath(harness, "android.view.View\$OnClickListener")
            assertEquals(
                listOf(harness.path("__jvm__/classes/android/view/View\$OnClickListener.lua")),
                harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "OnClickListener", occurrence = 2)).map { it.path }
            )
        }
    }

    private fun jvmHarness(
        vararg files: Pair<String, String>,
        classes: Set<String> = emptySet(),
        metadata: Map<String, String> = emptyMap()
    ): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = metadataWithClasses(metadata, classes),
            engine = JvmWorkspaceEngine()
        )
    }

    private fun withAndroidHarness(
        vararg files: Pair<String, String>,
        classes: Set<String> = emptySet(),
        metadata: Map<String, String> = emptyMap(),
        block: (WorkspaceSemanticHarness) -> Unit
    ) {
        if (!androidJar.isFile) {
            return
        }
        block(
            jvmHarness(
                *files,
                classes = classes,
                metadata = metadata + (JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path)
            )
        )
    }

    private fun metadataWithClasses(metadata: Map<String, String>, classes: Set<String>): Map<String, String> {
        if (classes.isEmpty()) {
            return metadata
        }
        val mergedClasses = (
            metadata[JvmClassModuleProvider.CLASSES_METADATA_KEY]
                .orEmpty()
                .split(',', ';', '\n')
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty) +
                classes.asSequence()
        ).toCollection(linkedSetOf())
        return metadata + (JvmClassModuleProvider.CLASSES_METADATA_KEY to mergedClasses.joinToString("\n"))
    }

    private fun resourceText(name: String): String {
        val path = "/semantic/campaign-java-android/$name"
        val stream = javaClass.getResourceAsStream(path)
            ?: error("Missing TASK-102 fixture resource $path")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun jvmClassLoads(harness: WorkspaceSemanticHarness, path: String): List<DocumentFacts.JvmClassLoadFact> {
        return harness.snapshot.files.getValue(harness.path(path)).documentFacts?.jvmClassLoads.orEmpty()
    }

    private fun assertProviderPath(harness: WorkspaceSemanticHarness, className: String) {
        val path = harness.path("__jvm__/classes/${className.replace('.', '/')}.lua")
        assertTrue(
            path in harness.snapshot.extraProviders,
            "Expected provider $path for $className; actual providers: ${harness.snapshot.extraProviders.keys.map { it.value }}."
        )
    }

    private fun assertHoverType(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        expected: String,
        occurrence: Int = 1
    ) {
        val hover = assertNotNull(harness.queries.hover(harness.path(path), harness.positionOf(path, needle, occurrence)))
        assertEquals(expected, hover.typeInfo?.displayName)
        assertNotUnknown(hover.typeInfo?.displayName)
    }

    private fun assertHoverTypeContains(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        expectedText: String,
        occurrence: Int = 1
    ) {
        val hover = assertNotNull(harness.queries.hover(harness.path(path), harness.positionOf(path, needle, occurrence)))
        val actual = hover.typeInfo?.displayName.orEmpty()
        assertTrue(
            expectedText in actual,
            "Expected hover type for $needle in $path to contain '$expectedText', got '$actual'."
        )
        assertNotUnknown(actual)
    }

    private fun assertCallableHover(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        occurrence: Int = 1
    ) {
        val hover = assertNotNull(harness.queries.hover(harness.path(path), harness.positionOf(path, needle, occurrence)))
        assertCallable(hover.typeInfo?.displayName)
    }

    private fun assertCallableMember(
        harness: WorkspaceSemanticHarness,
        path: String,
        memberAccess: String
    ) {
        val hover = assertNotNull(harness.queries.hover(harness.path(path), memberPosition(harness, path, memberAccess)))
        assertEquals(SymbolKind.METHOD, hover.symbol?.kind)
        assertCallable(hover.typeInfo?.displayName)
    }

    private fun assertCompletion(completions: List<CompletionItem>, label: String, kind: CompletionItemKind) {
        assertTrue(
            completions.any { it.label == label && it.kind == kind },
            "Expected completion $label of kind $kind; actual: ${completions.map { "${it.label}:${it.kind}" }}."
        )
    }

    private fun assertCallable(displayName: String?) {
        val text = displayName.orEmpty()
        assertTrue(
            looksCallable(text),
            "Expected callable type, got $displayName."
        )
        assertNotUnknown(displayName)
    }

    /**
     * Product JVM static helpers may render as monomorphic `fun(...)` or generic
     * overload sets like `fun<K, V>(...): ... & fun<K: Comparable, V>(): ...`
     * (e.g. Map.Entry.comparingByKey). Accept any FunctionType-compatible display.
     */
    private fun looksCallable(displayName: String): Boolean {
        return displayName.contains("fun(") ||
            displayName.contains("fun<") ||
            Regex("""fun\s*<[^>]+>\s*\(""").containsMatchIn(displayName)
    }

    private fun assertNotUnknown(displayName: String?) {
        assertFalse(displayName.isNullOrBlank(), "Expected a modeled Java/Android interop type, got no type.")
        assertFalse(displayName == "unknown", "Expected a modeled Java/Android interop type, got unknown.")
    }

    private fun assertNoDiagnostics(harness: WorkspaceSemanticHarness, path: String) {
        val diagnostics = harness.queries.diagnostics(harness.path(path))
        assertTrue(
            diagnostics.isEmpty(),
            "Expected no diagnostics for valid Java/Android interop fixture; actual diagnostics: ${diagnostics.map { it.message }}."
        )
    }

    private fun assertInvalidMemberDiagnostic(harness: WorkspaceSemanticHarness, path: String, member: String) {
        val diagnostics = harness.queries.diagnostics(harness.path(path))
        assertTrue(
            diagnostics.containsInvalidMember(member),
            "Expected invalid Java/Android member diagnostic for $member; actual diagnostics: ${diagnostics.map { it.message }}."
        )
    }

    private fun List<Diagnostic>.containsInvalidMember(member: String): Boolean {
        return any { diagnostic ->
            diagnostic.message.contains(member) &&
                (diagnostic.message.contains("member", ignoreCase = true) ||
                    diagnostic.message.contains("method", ignoreCase = true) ||
                    diagnostic.message.contains("field", ignoreCase = true) ||
                    diagnostic.message.contains("unknown", ignoreCase = true) ||
                    diagnostic.message.contains("unresolved", ignoreCase = true) ||
                    diagnostic.message.contains("not found", ignoreCase = true))
        }
    }

    private fun memberPosition(harness: WorkspaceSemanticHarness, path: String, memberAccess: String): Position {
        val dotIndex = memberAccess.indexOf('.')
        check(dotIndex >= 0) { "Expected member access with dot, got $memberAccess." }
        return positionIn(harness, path, memberAccess, offset = dotIndex + 1)
    }

    private fun positionIn(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        offset: Int = 0
    ): Position {
        val source = harness.files.getValue(harness.path(path))
        val index = source.indexOf(needle)
        check(index >= 0) { "Missing '$needle' in $path." }
        return positionAt(source, index + offset)
    }

    private fun positionAt(source: String, index: Int): Position {
        var line = 1
        var column = 1
        for (i in 0 until index) {
            if (source[i] == '\n') {
                line += 1
                column = 1
            } else {
                column += 1
            }
        }
        return Position(line, column)
    }
}
