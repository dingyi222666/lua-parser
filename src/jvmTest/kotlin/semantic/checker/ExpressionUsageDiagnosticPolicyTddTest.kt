package semantic.checker

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Semantic-model diagnostic policy for ExpressionUsageChecker (adversarial-audit wave A):
 *
 * 1. Unused value locals report with INFO severity (editor hint, not a defect). The LSP
 *    publish surface maps this to lsp Information; the model is the source of truth.
 * 2. `checker.luajava.target.unresolved` reports WARNING, not ERROR: hosts without the
 *    target class on the classpath (no android.jar etc.) still run valid code on-device.
 * 3. Index-path member-missing diagnostics name the offending index key, matching the
 *    member-path message shape (`Unknown Java member '<key>' on <base>.`).
 *
 * Harness mirrors sibling interop suites (WorkspaceSemanticHarness + JvmWorkspaceEngine);
 * `luajava.bindClass` resolves host JDK classes reflectively, so `java.lang.System` is a
 * stable Java surface and `missing.DoesNotExist` a stable unresolved target.
 */
class ExpressionUsageDiagnosticPolicyTddTest {

    @Test
    fun unused_local_diagnostic_uses_info_severity() {
        val harness = harness("local unusedValue = 1\nreturn 1")

        val unused = diagnostics(harness).filter { it.code == "checker.local.unused" }

        assertTrue(
            unused.isNotEmpty(),
            "Expected unused-local diagnostic on executable chunk; actual: ${describe(diagnostics(harness))}."
        )
        val diagnostic = unused.single()
        assertEquals(DiagnosticSeverity.INFO, diagnostic.severity)
        assertEquals("Unused local 'unusedValue'.", diagnostic.message)
    }

    @Test
    fun used_local_emits_no_unused_local_diagnostic() {
        val harness = harness("local usedValue = 1\nreturn usedValue")

        val unused = diagnostics(harness).filter { it.code == "checker.local.unused" }

        assertTrue(
            unused.isEmpty(),
            "Read local must not report unused; actual: ${describe(diagnostics(harness))}."
        )
    }

    @Test
    fun luajava_unresolved_target_diagnostic_uses_warning_severity() {
        val harness = harness("local cls = luajava.bindClass(\"missing.DoesNotExist\")\nreturn cls")

        val unresolved = diagnostics(harness).filter { it.code == "checker.luajava.target.unresolved" }

        assertTrue(
            unresolved.isNotEmpty(),
            "Expected unresolved LuaJava target diagnostic; actual: ${describe(diagnostics(harness))}."
        )
        val diagnostic = unresolved.single()
        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity)
        assertTrue(
            diagnostic.message.contains("missing.DoesNotExist"),
            "Message should name the unresolved target; actual: ${diagnostic.message}."
        )
    }

    @Test
    fun index_path_member_diagnostic_includes_index_key() {
        val harness = harness(
            "local System = luajava.bindClass(\"java.lang.System\")\n" +
                "return System[\"definitelyNotAStaticMember\"]"
        )

        val memberMissing = diagnostics(harness).filter { it.code == "checker.member.missing" }

        assertTrue(
            memberMissing.isNotEmpty(),
            "Expected index-path member-missing diagnostic; actual: ${describe(diagnostics(harness))}."
        )
        val message = memberMissing.single().message
        assertTrue(
            message.contains("definitelyNotAStaticMember"),
            "Index key must appear in the message; actual: $message."
        )
        assertTrue(
            message.contains("System"),
            "Base type name must appear in the message; actual: $message."
        )
        assertEquals(DiagnosticSeverity.ERROR, memberMissing.single().severity)
    }

    private fun harness(source: String): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            "main.lua" to source,
            metadata = mapOf(
                io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider.CLASSES_METADATA_KEY to
                    "java.lang.System"
            ),
            engine = JvmWorkspaceEngine()
        )
    }

    private fun diagnostics(harness: WorkspaceSemanticHarness) =
        harness.queries.diagnostics(harness.path("main.lua"))

    private fun describe(diagnostics: List<io.github.dingyi222666.luaparser.semantic.api.Diagnostic>): String {
        return diagnostics.map { diagnostic -> "${diagnostic.severity}/${diagnostic.code}: ${diagnostic.message}" }
            .toString()
    }
}
