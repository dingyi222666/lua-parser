package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-214 / TASK-247 — Java static field read modeling corpus.
 *
 * Acceptance (test-only; review-owned verification):
 * - Static field reads on bound classes resolve for at least two JDK examples.
 * - Missing fields remain unknown without crashing the semantic pipeline.
 * - Completions for static fields on `luajava.bindClass` targets use
 *   [CompletionItemKind.FIELD], coherent with hover [SymbolKind.FIELD].
 *
 * Corpus focuses on `luajava.bindClass` static field access (not instance chains).
 * CompletionProvider is in TASK-247 scope for member completion kind. No Gradle from workers.
 *
 * Cursor must land on the member identifier (not a same-named local) so member
 * completions surface out/err together as FIELD.
 */
class JavaStaticFieldReadTddTest {

    // ------------------------------------------------------------------
    // Positive: at least two JDK static field reads resolve
    // ------------------------------------------------------------------

    @Test
    fun locale_root_static_field_hover_reports_locale_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local root = Locale.ROOT
                return root
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "ROOT"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        assertEquals("java.util.Locale", hover.typeInfo?.displayName)
        assertNotUnknown(hover.typeInfo?.displayName)
    }
    @Test
    fun integer_max_value_static_field_hover_reports_number() {
        val harness = jvmHarness(
            "main.lua" to """
                local Integer = luajava.bindClass("java.lang.Integer")
                local max = Integer.MAX_VALUE
                return max
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "MAX_VALUE"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        assertEquals("number", hover.typeInfo?.displayName)
        assertNotUnknown(hover.typeInfo?.displayName)
    }
    @Test
    fun system_out_static_field_hover_reports_print_stream() {
        val harness = jvmHarness(
            "main.lua" to """
                local System = luajava.bindClass("java.lang.System")
                local out = System.out
                return out
            """.trimIndent()
        )

        // Occurrence 2 is System.out (occurrence 1 is the local name `out`).
        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "out", occurrence = 2))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        assertEquals("java.io.PrintStream", hover.typeInfo?.displayName)
        assertNotUnknown(hover.typeInfo?.displayName)
    }
    @Test
    fun static_field_definition_points_to_class_provider() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local separator = File.separator
                return separator
            """.trimIndent()
        )

        val definitions = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "separator")
        )
        assertEquals(
            listOf(harness.path("__jvm__/classes/java/io/File.lua")),
            definitions.map { it.path }
        )
    }
    @Test
    fun missing_static_field_on_bound_class_is_unknown_without_crash() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local missing = Locale.definitelyNotAStaticField
                return missing
            """.trimIndent()
        )

        // Pipeline must complete (queries.hover / diagnostics must not throw).
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "definitelyNotAStaticField")
        )
        val display = hover?.typeInfo?.displayName
        assertTrue(
            display == null || display == "unknown",
            "Missing static field should be unknown/untyped, got '$display'."
        )

        // Local binding of a missing field should also stay unknown.
        val localHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "missing", occurrence = 2)
        )
        val localDisplay = localHover?.typeInfo?.displayName
        assertTrue(
            localDisplay == null || localDisplay == "unknown",
            "Local bound from missing static field should be unknown, got '$localDisplay'."
        )

        // Diagnostics path must also complete without throwing.
        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))
        assertNotNull(diagnostics)
    }
    @Test
    fun bind_class_alias_still_resolves_static_field() {
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local File = bindClass("java.io.File")
                local separator = File.separator
                return separator
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "separator"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        assertEquals("string", hover.typeInfo?.displayName)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun jvmHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            engine = JvmWorkspaceEngine()
        )
    }

    private fun assertCompletion(completions: List<CompletionItem>, label: String, kind: CompletionItemKind) {
        assertTrue(
            completions.any { it.label == label && it.kind == kind },
            "Expected completion $label of kind $kind; actual: ${completions.map { "${it.label}:${it.kind}" }}."
        )
    }


    private fun assertNotUnknown(displayName: String?) {
        assertFalse(displayName.isNullOrBlank(), "Expected a modeled Java static field type, got no type.")
        assertFalse(displayName == "unknown", "Expected a modeled Java static field type, got unknown.")
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
}
