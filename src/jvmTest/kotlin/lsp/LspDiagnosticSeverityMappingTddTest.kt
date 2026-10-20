package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity as SemanticDiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticSeverity as LspDiagnosticSeverity
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.TextDocumentItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * REVIEW19 / TASK-220 corpus: semantic [SemanticDiagnosticSeverity] → LSP
 * [LspDiagnosticSeverity] mapping used by [LuaLanguageService] publish path.
 *
 * Product mapping lives on the file-level private extension
 * `DiagnosticSeverity.toLspSeverity()` in `LuaLanguageService.kt` (compiled as
 * `LuaLanguageServiceKt.toLspSeverity` / `access$toLspSeverity`):
 *
 * | Semantic severity | LSP severity   |
 * |-------------------|----------------|
 * | ERROR             | Error          |
 * | WARNING           | Warning        |
 * | INFO              | Information    |
 *
 * There is no Hint branch today. The `when` is exhaustive over the three-value
 * semantic enum; an unknown ordinal falls through to
 * [kotlin.NoWhenBranchMatchedException] rather than inventing a severity.
 *
 * End-to-end publish path locks:
 * - Parse recovery diagnostics hard-code LSP Error (safe default for untyped
 *   parse failures).
 * - Checker diagnostics currently default to semantic ERROR and therefore publish
 *   as LSP Error (type mismatch / unknownParam fixtures).
 * - Every published diagnostic from the service has a non-null severity.
 *
 * Test-only. No product edits. Verification is review-owned (TASK-043).
 */
class LspDiagnosticSeverityMappingTddTest {

    // -------------------------------------------------------------------------
    // Direct mapping table (reflective access to private toLspSeverity)
    // -------------------------------------------------------------------------

    @Test
    fun semanticErrorMapsToLspError() {
        assertEquals(LspDiagnosticSeverity.Error, mapSeverity(SemanticDiagnosticSeverity.ERROR))
    }

    @Test
    fun semanticWarningMapsToLspWarning() {
        assertEquals(LspDiagnosticSeverity.Warning, mapSeverity(SemanticDiagnosticSeverity.WARNING))
    }

    @Test
    fun everySemanticSeverityMapsConsistentlyToExpectedLspSeverity() {
        val expected = mapOf(
            SemanticDiagnosticSeverity.ERROR to LspDiagnosticSeverity.Error,
            SemanticDiagnosticSeverity.WARNING to LspDiagnosticSeverity.Warning,
            SemanticDiagnosticSeverity.INFO to LspDiagnosticSeverity.Information
        )

        val actual = SemanticDiagnosticSeverity.entries.associateWith { mapSeverity(it) }

        assertEquals(
            expected,
            actual,
            "semantic→LSP severity table drift: update product + this corpus together"
        )
        assertEquals(
            SemanticDiagnosticSeverity.entries.toSet(),
            actual.keys,
            "mapping must cover every SemanticDiagnosticSeverity entry"
        )
        actual.values.forEach { severity ->
            assertNotNull(severity, "mapped LSP severity must never be null")
        }
    }

    // -------------------------------------------------------------------------
    // Publish-path end-to-end: parse + checker severities
    // -------------------------------------------------------------------------

    @Test
    fun parseRecoveryDiagnosticPublishesAsLspError() {
        val published = openAndCollect(
            uri = "file:///workspace/severity-parse-error.lua",
            source = "local ="
        )

        assertTrue(published.diagnostics.isNotEmpty(), "invalid Lua must publish parse diagnostics")
        published.diagnostics.forEach { diagnostic ->
            assertNotNull(diagnostic.severity, "parse diagnostic severity must be non-null")
            assertEquals(
                LspDiagnosticSeverity.Error,
                diagnostic.severity,
                "parse recovery diagnostics hard-code LSP Error (safe default)"
            )
        }
    }

    @Test
    fun queriedDiagnosticsAgreeWithPublishedSeveritiesForParseError() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val uri = "file:///workspace/severity-query-agree.lua"
        val path = "workspace/severity-query-agree.lua"
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(service, publishDiagnostics = { published += it })

        textDocuments.didOpen(openParams(uri, "local ="))
        val lastPublished = published.last { it.uri == uri }
        val queried = service.diagnostics(path)

        assertEquals(uri, queried.uri)
        assertEquals(
            lastPublished.diagnostics.map { it.severity },
            queried.diagnostics.map { it.severity },
            "queried diagnostics severities must match last publish"
        )
        assertTrue(queried.diagnostics.isNotEmpty())
        assertTrue(queried.diagnostics.all { it.severity == LspDiagnosticSeverity.Error })
    }

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    private fun openAndCollect(uri: String, source: String): PublishDiagnosticsParams {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(service, publishDiagnostics = { published += it })
        textDocuments.didOpen(openParams(uri, source))
        return published.single { it.uri == uri }
    }

    private fun openParams(uri: String, source: String): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(
            TextDocumentItem(uri, "lua", 1, source)
        )
    }

    /**
     * Invokes the private file-level `toLspSeverity` mapper compiled on
     * `LuaLanguageServiceKt` via the synthetic `access$toLspSeverity` bridge.
     */
    private fun mapSeverity(severity: SemanticDiagnosticSeverity): LspDiagnosticSeverity {
        return mapSeverityNullable(severity)
            ?: error("toLspSeverity returned null for $severity")
    }

    private fun mapSeverityNullable(severity: SemanticDiagnosticSeverity?): LspDiagnosticSeverity? {
        val holder = Class.forName("io.github.dingyi222666.luaparser.lsp.LuaLanguageServiceKt")
        val method = holder.getDeclaredMethod(
            "access\$toLspSeverity",
            SemanticDiagnosticSeverity::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(null, severity) as LspDiagnosticSeverity?
    }
}
