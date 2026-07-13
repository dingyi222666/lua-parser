package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaChainedCallTddTest {
    private val androidJar = File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)

    /**
     * TASK-589 product hard-lock: resource_reflection_fixture_chained_static_and_instance_calls_are_typed
     * style corpus. Intermediate static factory / instance returns must stay reflection-backed
     * (never invent chain types without reflected signatures).
     */
    @Test
    fun resource_reflection_fixture_chained_static_and_instance_calls_are_typed() {
        val harness = jvmHarness(
            "main.lua" to """
                local Arrays = luajava.bindClass("java.util.Arrays")
                local Locale = luajava.bindClass("java.util.Locale")
                local Integer = luajava.bindClass("java.lang.Integer")

                local values = Arrays.asList("alpha", "beta")
                local count = values.size()
                local locales = Locale.getAvailableLocales()
                local parsed = Integer.parseInt("42")
                local firstTag = Locale.forLanguageTag("en-US").toLanguageTag()

                return values, count, locales, parsed, firstTag
            """.trimIndent(),
            classes = setOf("java.util.List")
        )

        assertHoverType(harness, "count", "number", occurrence = 2)
        val localesHover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "locales", 2))
        )
        val localesType = localesHover.typeInfo?.displayName.orEmpty()
        assertTrue(
            "java.util.Locale[]" in localesType || localesType.contains("Locale"),
            "Expected Locale[] (or Locale array surface) for getAvailableLocales chain; got $localesType"
        )
        assertNotUnknown(localesType)
        assertHoverType(harness, "parsed", "number", occurrence = 2)
        assertHoverType(harness, "firstTag", "string", occurrence = 2)
        // Intermediate static factory result used by instance chain must remain typed.
        val tagHover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "toLanguageTag"))
        )
        assertEquals(SymbolKind.METHOD, tagHover.symbol?.kind)
        assertCallable(tagHover.typeInfo?.displayName)
    }
    @Test
    fun file_parent_file_name_chain_returns_string() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local name = File("src/main/kotlin").getParentFile().getName()
                return name
            """.trimIndent()
        )

        assertHoverType(harness, "name", "string", occurrence = 2)
        assertNoDiagnostics(harness)
    }
    @Test
    fun java_bean_properties_do_not_hide_direct_methods() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local parent = File("src/main/kotlin").parentFile
                local name = parent.name
                local methodParent = File("src/main/kotlin").getParentFile()
                local methodName = methodParent.getName()
                return name, methodName
            """.trimIndent()
        )

        assertHoverType(harness, "name", "string", occurrence = 2)
        assertHoverType(harness, "methodName", "string", occurrence = 2)
        val hover = assertNotNull(harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "getName")))
        assertEquals(SymbolKind.METHOD, hover.symbol?.kind)
        assertCallable(hover.typeInfo?.displayName)
        assertNoDiagnostics(harness)
    }
    @Test
    fun boolean_is_getter_surfaces_as_lua_property() {
        val harness = jvmHarness(
            "main.lua" to """
                local ArrayList = luajava.bindClass("java.util.ArrayList")
                local list = ArrayList()
                local empty = list.empty
                local methodEmpty = list.isEmpty()
                return empty, methodEmpty
            """.trimIndent()
        )

        assertHoverType(harness, "empty", "boolean", occurrence = 2)
        assertHoverType(harness, "methodEmpty", "boolean", occurrence = 2)
        assertNoDiagnostics(harness)
    }
    @Test
    fun listener_setter_accepts_lua_function_callback() {
        val harness = jvmHarness(
            "main.lua" to """
                local Holder = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}ListenerHolder")
                local holder = Holder()
                local assigned = holder.setValueListener(function(value)
                    return nil
                end)
                return assigned
            """.trimIndent()
        )

        assertHoverType(harness, "assigned", "nil", occurrence = 2)
        assertNoDiagnostics(harness)
    }
    @Test
    fun overloaded_getter_does_not_create_property_alias() {
        val harness = jvmHarness(
            "main.lua" to """
                local Bean = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}OverloadedGetterBean")
                local bean = Bean()
                local direct = bean.getCode()
                local property = bean.code
                return direct, property
            """.trimIndent()
        )

        assertHoverType(harness, "direct", "string", occurrence = 2)
        assertInvalidMemberDiagnostic(harness, "code")
    }
    @Test
    fun paths_get_static_factory_to_file_get_name_returns_string() {
        val harness = jvmHarness(
            "main.lua" to """
                local Paths = luajava.bindClass("java.nio.file.Paths")
                local name = Paths.get("build.gradle.kts").toFile().getName()
                return name
            """.trimIndent(),
            classes = setOf("java.nio.file.Path", "java.io.File")
        )

        assertHoverType(harness, "name", "string", occurrence = 2)
        assertNoDiagnostics(harness)
    }
    @Test
    fun string_builder_append_string_overload_return_allows_to_string_chain() {
        val harness = jvmHarness(
            "main.lua" to """
                local StringBuilder = luajava.bindClass("java.lang.StringBuilder")
                local text = StringBuilder().append("prefix").append("suffix").toString()
                return text
            """.trimIndent()
        )

        assertHoverType(harness, "text", "string", occurrence = 2)
    }
    @Test
    fun completion_after_file_instance_includes_javabean_property_aliases() {
        // Keep local names distinct from member needles so completion is requested on the
        // Java member expression (TASK-177), not the lexical local binding.
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local result = File("src/main/kotlin").parentFile
                return result
            """.trimIndent()
        )

        // Readable JavaBean aliases are exported as fields without hiding direct getters.
        assertCompletionAt(harness, "parentFile", "parentFile", CompletionItemKind.FIELD)
        assertCompletionAt(harness, "parentFile", "name", CompletionItemKind.FIELD)
        assertCompletionAt(harness, "parentFile", "getParentFile", CompletionItemKind.METHOD)
        assertCompletionAt(harness, "parentFile", "getName", CompletionItemKind.METHOD)
    }
    @Test
    fun hover_on_chain_method_reports_callable() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local parent = File("src/main/kotlin").getParentFile()
                return parent
            """.trimIndent()
        )

        val hover = assertNotNull(harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "getParentFile")))
        assertEquals(SymbolKind.METHOD, hover.symbol?.kind)
        assertCallable(hover.typeInfo?.displayName)
    }
    @Test
    fun invalid_member_after_file_constructor_reports_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local value = File("src/main/kotlin").definitelyMissing()
                return value
            """.trimIndent()
        )

        assertInvalidMemberDiagnostic(harness, "definitelyMissing")
    }
    @Test
    fun android_uri_parse_builder_chain_returns_string_when_android_jar_available() {
        withAndroidHarness(
            "main.lua" to """
                local Uri = luajava.bindClass("android.net.Uri")
                local path = Uri.parse("content://example/root").buildUpon().path("child").build().getPath()
                return path
            """.trimIndent(),
            classes = setOf("android.net.Uri", "android.net.Uri\$Builder")
        ) { harness ->
            // occurrence 2 is Uri.Builder.path(...); occurrence 3 is the return local result.
            assertHoverType(harness, "path", "string", occurrence = 3)
            assertNoDiagnostics(harness)
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
        block: (WorkspaceSemanticHarness) -> Unit
    ) {
        if (!androidJar.isFile) {
            return
        }
        block(
            jvmHarness(
                *files,
                classes = classes,
                metadata = mapOf(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path)
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

    private fun assertHoverType(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expected: String,
        occurrence: Int = 1
    ) {
        val hover = assertNotNull(harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", needle, occurrence)))
        assertEquals(expected, hover.typeInfo?.displayName)
        assertNotUnknown(hover.typeInfo?.displayName)
    }

    private fun assertHoverTypeAllowingUnknown(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expected: String,
        occurrence: Int = 1
    ) {
        val hover = assertNotNull(harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", needle, occurrence)))
        assertEquals(expected, hover.typeInfo?.displayName)
    }

    private fun assertCompletionAt(
        harness: WorkspaceSemanticHarness,
        memberNeedle: String,
        label: String,
        kind: CompletionItemKind,
        occurrence: Int = 1
    ) {
        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", memberNeedle, occurrence)
        )
        assertCompletion(completions, label, kind)
    }

    private fun assertCompletion(completions: List<CompletionItem>, label: String, kind: CompletionItemKind) {
        assertTrue(
            completions.any { it.label == label && it.kind == kind },
            "Expected completion $label of kind $kind; actual: ${completions.map { "${it.label}:${it.kind}" }}."
        )
    }

    private fun assertCallable(displayName: String?) {
        assertTrue(
            displayName.orEmpty().contains("fun("),
            "Expected callable type, got $displayName."
        )
        assertNotUnknown(displayName)
    }

    private fun assertNotUnknown(displayName: String?) {
        assertFalse(displayName.isNullOrBlank(), "Expected a modeled Java chain type, got no type.")
        assertFalse(displayName == "unknown", "Expected a modeled Java chain type, got unknown.")
    }

    private fun assertNoDiagnostics(harness: WorkspaceSemanticHarness) {
        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))
        assertTrue(
            diagnostics.isEmpty(),
            "Expected no diagnostics for valid Java chain; actual diagnostics: ${diagnostics.map { it.message }}."
        )
    }

    private fun assertInvalidMemberDiagnostic(harness: WorkspaceSemanticHarness, member: String) {
        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))
        assertTrue(
            diagnostics.containsInvalidMember(member),
            "Expected invalid Java member diagnostic for $member; actual diagnostics: ${diagnostics.map { it.message }}."
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

    class MutableJavaBean {
        fun getTitle(): String = "initial"

        fun setTitle(value: String) {
            storedTitle = value
        }

        private var storedTitle: String = "initial"
    }

    class ListenerHolder {
        fun setValueListener(listener: ValueListener) {
            this.listener = listener
        }

        fun setAction(action: Action) {
            this.action = action
        }

        private var listener: ValueListener? = null
        private var action: Action? = null
    }

    interface ValueListener {
        fun onValue(value: String)
    }

    interface Action {
        fun run()
    }

    class OverloadedGetterBean {
        fun getCode(): String = "zero"

        fun getCode(index: Int): String = index.toString()
    }

    class WriteOnlyBean {
        fun setToken(value: String) {
            storedToken = value
        }

        private var storedToken: String = ""
    }

    class MixedBooleanGetterBean {
        fun getActive(): Boolean = true

        fun isActive(): String = "not-a-boolean-bean-getter"
    }

    class ContainerHolder {
        fun setStringArray(values: Array<String>): Int = values.size

        fun setStringList(values: List<String>): Int = values.size

        fun setStringMap(values: Map<String, String>): Int = values.size

        fun setRawList(values: List<*>): Int = values.size
    }
}
