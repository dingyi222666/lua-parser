package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LSP publish diagnostics policy (adversarial-audit wave A):
 *
 * 1. `checker.local.unused` publishes as LSP Information instead of being dropped, but
 *    only while the analyzed snapshot matches the open buffer. Documents with parse
 *    recovery errors still hard-lock to Error-only publishes (unused-local Information
 *    stays suppressed there).
 * 2. `checker.luajava.target.unresolved` publishes as Warning: hosts without the target
 *    class on the classpath (no android.jar etc.) still run valid code on-device, so a
 *    hard Error would flag valid `luajava.bindClass` programs.
 * 3. Published diagnostics never carry zero-width ranges: null / zero-width semantic
 *    ranges fall back to a 1-character anchor on the first token of the first non-blank
 *    line, while real spans are preserved untouched.
 *
 * Test-only; exercises the publish policy, not the semantic model internals.
 */
class LspDiagnosticPolicyTddTest {

    @Test
    fun did_open_unused_local_publishes_information_diagnostic() {
        val service = initializedService()

        val published = service.didOpen(
            openParams("file:///workspace/unused-local-info.lua", "local unusedValue = 1\nreturn 1")
        )

        val unused = published.diagnostics.filter { it.code?.left == "checker.local.unused" }
        assertTrue(
            unused.isNotEmpty(),
            "Expected unused-local diagnostic to publish; actual: ${describe(published.diagnostics)}."
        )
        val diagnostic = unused.single()
        assertEquals(DiagnosticSeverity.Information, diagnostic.severity)
        assertEquals("Unused local 'unusedValue'.", diagnostic.message)
        val range = assertNotNull(diagnostic.range, "unused-local must publish a real span")
        assertEquals(0, range.start.line)
        assertEquals(6, range.start.character)
        assertTrue(
            range.end.line > range.start.line ||
                (range.end.line == range.start.line && range.end.character > range.start.character),
            "unused-local span must stay non-zero-width; actual: $range."
        )

        // Pull path agrees with the published multiset while the buffer is analyzed.
        val queried = service.diagnostics("workspace/unused-local-info.lua")
        assertTrue(
            queried.diagnostics.any { it.code?.left == "checker.local.unused" },
            "Pull query should surface the same unused-local Information diagnostic; " +
                "actual: ${describe(queried.diagnostics)}."
        )
    }

    @Test
    fun parse_error_document_hard_locks_publish_to_error_only() {
        val service = initializedService()

        val published = service.didOpen(
            openParams("file:///workspace/unused-local-hardlock.lua", "local unusedValue = 1\nlocal =")
        )

        assertTrue(
            published.diagnostics.isNotEmpty(),
            "invalid Lua should still publish parse diagnostics"
        )
        assertTrue(
            published.diagnostics.all { it.severity == DiagnosticSeverity.Error },
            "invalid sources must hard-lock to Error-only; actual: ${describe(published.diagnostics)}."
        )
        assertTrue(
            published.diagnostics.none { it.code?.left == "checker.local.unused" },
            "unused-local Information must stay suppressed while parse errors publish."
        )
    }

    @Test
    fun luajava_unresolved_target_publishes_as_warning() {
        val service = initializedService()

        val published = service.didOpen(
            openParams(
                "file:///workspace/luajava-target-warning.lua",
                "local cls = luajava.bindClass(\"missing.DoesNotExist\")\nreturn cls"
            )
        )

        val unresolved = published.diagnostics.filter { it.code?.left == "checker.luajava.target.unresolved" }
        assertTrue(
            unresolved.isNotEmpty(),
            "Expected unresolved LuaJava target diagnostic; actual: ${describe(published.diagnostics)}."
        )
        val diagnostic = unresolved.single()
        assertEquals(DiagnosticSeverity.Warning, diagnostic.severity)
        assertTrue(
            diagnostic.message.contains("missing.DoesNotExist"),
            "Message should name the unresolved target; actual: ${diagnostic.message}."
        )
    }

    private fun initializedService(): LuaLanguageService {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        return service
    }

    private fun openParams(uri: String, text: String): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, text))
    }

    private fun describe(diagnostics: List<org.eclipse.lsp4j.Diagnostic>): String {
        return diagnostics.map { diagnostic ->
            "${diagnostic.severity}/${diagnostic.code?.left}: ${diagnostic.message}"
        }.toString()
    }
}
