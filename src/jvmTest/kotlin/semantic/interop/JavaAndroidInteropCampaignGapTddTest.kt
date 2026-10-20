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
    fun android_resource_fixture_resolves_text_view_constructor_when_android_jar_available() {
        withAndroidHarness("android_view_campaign.lua" to resourceText("android_view_campaign.lua")) { harness ->
            assertHoverType(harness, "android_view_campaign.lua", "textView", "android.widget.TextView", occurrence = 4)
            assertCallableMember(harness, "android_view_campaign.lua", "textView.setText")
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
