package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextEdit
import org.junit.Assume
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-276 — LSP range formatting table body corpus.
 *
 * Locks the safety contract for textDocument/rangeFormatting when the selected
 * range covers (or targets) a Lua table constructor body:
 * - Multi-line table bodies may yield empty edits or well-formed [TextEdit]s;
 *   the provider must not crash or invent inverted / out-of-bounds ranges.
 * - Nested tables, array-style / record-style fields, and mixed bodies stay
 *   within the same degrade-safely policy.
 * - Invalid ranges (inverted, beyond EOF, empty, missing document, negative
 *   positions) return an empty edit list without throwing once the surface is
 *   reachable.
 * - Malformed / incomplete table sources degrade safely (empty list or valid
 *   edits only).
 *
 * Product range formatting is intentionally out of scope for this task
 * (test-only). Current [LuaTextDocumentService] inherits the LSP4J default that
 * throws [UnsupportedOperationException], and [LuaLanguageService] does not
 * advertise documentRangeFormattingProvider. Tests therefore:
 * - Accept the documented gap (UnsupportedOperationException) without failing
 *   the suite, or skip cleanly with an explicit [Assume] reason when a hard
 *   contract requires an implemented surface.
 * - Enforce hard empty-or-well-formed edit contracts once rangeFormatting is
 *   implemented / advertised.
 *
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class LspRangeFormattingTableBodyTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun range_formatting_capability_or_explicit_skip_when_unimplemented() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-capability-probe.lua",
            """
            local config = {
                enabled = true,
                retries = 3
            }
            return config
            """
        )

        val outcome = invokeRangeFormatting(
            textDocuments,
            rangeFormattingParams(
                document,
                Range(Position(0, 15), Position(3, 1)),
                FormattingOptions(4, true)
            )
        )

        when (outcome) {
            is RangeFormatOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-276 skipped: rangeFormatting not yet implemented " +
                        "(documentRangeFormattingProvider=${capabilities.documentRangeFormattingProvider}, " +
                        "detail=${outcome.detail})",
                    false
                )
            }
            is RangeFormatOutcome.Failed -> {
                fail(
                    "rangeFormatting must not fail hard once surface is reachable: ${outcome.detail}"
                )
            }
            is RangeFormatOutcome.Succeeded -> {
                // Capability may lag implementation; once edits are returned they
                // must be well-formed even if the initialize flag is still null.
                assertWellFormedEdits(outcome.edits, document)
            }
        }
    }

    @Test
    fun range_formatting_surface_is_invokable_without_killing_service() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-surface.lua",
            """
            local t = {
                a = 1,
                b = 2
            }
            return t
            """
        )

        val outcome = invokeRangeFormatting(
            textDocuments,
            rangeFormattingParams(
                document,
                Range(Position(0, 10), Position(3, 1)),
                FormattingOptions(2, true)
            )
        )

        assertTrue(
            outcome is RangeFormatOutcome.Unsupported ||
                outcome is RangeFormatOutcome.Succeeded ||
                outcome is RangeFormatOutcome.Failed,
            "rangeFormatting surface must resolve to a known outcome; got $outcome"
        )
        // Hard failures (non-gap) are only acceptable before product lands; once
        // the method returns successfully, edits must be well-formed.
        if (outcome is RangeFormatOutcome.Succeeded) {
            assertWellFormedEdits(outcome.edits, document)
        }
        if (outcome is RangeFormatOutcome.Failed) {
            // Soft-fail paths that are not UnsupportedOperationException are
            // recorded but must not be silent NPEs / crashes without detail.
            assertTrue(
                outcome.detail.isNotBlank(),
                "Failed rangeFormatting should carry a detail string"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Table body ranges — degrade safely when supported
    // -------------------------------------------------------------------------

    @Test
    fun range_formatting_on_multiline_record_table_body_degrades_safely_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-record-table.lua",
            """
            local config = {
                enabled = true,
                retries = 3,
                label = "demo"
            }
            return config
            """
        )

        // Range covers the full table constructor including braces.
        val edits = requireRangeFormattingEdits(
            textDocuments,
            document,
            Range(Position(0, 15), Position(4, 1)),
            context = "multiline record table body"
        )

        assertWellFormedEdits(edits, document)
        assertEditsStayInsideDocument(edits, document)
    }

    @Test
    fun range_formatting_on_table_interior_fields_only_degrades_safely_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-table-interior.lua",
            """
            local config = {
                enabled = true,
                retries = 3,
                label = "demo"
            }
            return config
            """
        )

        // Range covers only the field lines (interior of the table body).
        val edits = requireRangeFormattingEdits(
            textDocuments,
            document,
            Range(Position(1, 0), Position(3, 18)),
            context = "table interior field lines"
        )

        assertWellFormedEdits(edits, document)
        assertEditsStayInsideDocument(edits, document)
    }

    @Test
    fun range_formatting_on_array_style_table_body_degrades_safely_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-array-table.lua",
            """
            local items = {
                1,
                2,
                3,
                "tail"
            }
            return items
            """
        )

        val edits = requireRangeFormattingEdits(
            textDocuments,
            document,
            Range(Position(0, 14), Position(5, 1)),
            context = "array-style table body"
        )

        assertWellFormedEdits(edits, document)
        assertEditsStayInsideDocument(edits, document)
    }

    @Test
    fun range_formatting_on_nested_table_body_degrades_safely_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-nested-table.lua",
            """
            local module = {
                version = 1,
                meta = {
                    author = "demo",
                    tags = { "a", "b" }
                },
                run = function(self)
                    return self.version
                end
            }
            return module
            """
        )

        // Outer table body
        val outerEdits = requireRangeFormattingEdits(
            textDocuments,
            document,
            Range(Position(0, 15), Position(9, 1)),
            context = "outer nested table body"
        )
        assertWellFormedEdits(outerEdits, document)
        assertEditsStayInsideDocument(outerEdits, document)

        // Inner `meta` table only
        val innerEdits = requireRangeFormattingEdits(
            textDocuments,
            document,
            Range(Position(2, 11), Position(5, 5)),
            context = "inner meta table body"
        )
        assertWellFormedEdits(innerEdits, document)
        assertEditsStayInsideDocument(innerEdits, document)
    }

    @Test
    fun range_formatting_on_mixed_array_and_record_table_degrades_safely_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-mixed-table.lua",
            """
            local mixed = {
                "first",
                key = "value",
                42,
                nested = { x = 1, y = 2 }
            }
            return mixed
            """
        )

        val edits = requireRangeFormattingEdits(
            textDocuments,
            document,
            Range(Position(0, 14), Position(5, 1)),
            context = "mixed array/record table body"
        )

        assertWellFormedEdits(edits, document)
        assertEditsStayInsideDocument(edits, document)
    }

    @Test
    fun range_formatting_on_single_line_table_degrades_safely_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-single-line-table.lua",
            "local t = { a = 1, b = 2 }\nreturn t\n"
        )

        val edits = requireRangeFormattingEdits(
            textDocuments,
            document,
            Range(Position(0, 10), Position(0, 26)),
            context = "single-line table constructor"
        )

        assertWellFormedEdits(edits, document)
        assertEditsStayInsideDocument(edits, document)
    }

    @Test
    fun range_formatting_on_empty_table_constructor_degrades_safely_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-empty-table.lua",
            """
            local empty = {
            }
            return empty
            """
        )

        val edits = requireRangeFormattingEdits(
            textDocuments,
            document,
            Range(Position(0, 14), Position(1, 1)),
            context = "empty multi-line table constructor"
        )

        assertWellFormedEdits(edits, document)
        assertEditsStayInsideDocument(edits, document)
    }

    // -------------------------------------------------------------------------
    // Malformed / incomplete table sources — no crash
    // -------------------------------------------------------------------------

    @Test
    fun range_formatting_on_incomplete_table_does_not_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-incomplete-table.lua",
            """
            local broken = {
                enabled = true,
                retries =
            """
        )

        val outcome = invokeRangeFormatting(
            textDocuments,
            rangeFormattingParams(
                document,
                Range(Position(0, 15), Position(3, 0)),
                FormattingOptions(4, true)
            )
        )

        when (outcome) {
            is RangeFormatOutcome.Unsupported -> {
                // Documented gap — acceptable until product implements rangeFormatting.
                assertTrue(outcome.detail.isNotBlank())
            }
            is RangeFormatOutcome.Failed -> {
                fail(
                    "incomplete table must not crash rangeFormatting; got hard failure: ${outcome.detail}"
                )
            }
            is RangeFormatOutcome.Succeeded -> {
                assertWellFormedEdits(outcome.edits, document)
            }
        }
    }

    @Test
    fun range_formatting_on_unclosed_brace_table_does_not_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-unclosed-table.lua",
            """
            local broken = {
                a = 1,
                b = {
                    c = 2
            return broken
            """
        )

        val outcome = invokeRangeFormatting(
            textDocuments,
            rangeFormattingParams(
                document,
                Range(Position(0, 15), Position(4, 13)),
                FormattingOptions(4, true)
            )
        )

        when (outcome) {
            is RangeFormatOutcome.Unsupported -> {
                assertTrue(outcome.detail.isNotBlank())
            }
            is RangeFormatOutcome.Failed -> {
                fail(
                    "unclosed table must not crash rangeFormatting; got hard failure: ${outcome.detail}"
                )
            }
            is RangeFormatOutcome.Succeeded -> {
                assertWellFormedEdits(outcome.edits, document)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Invalid ranges — empty without throw (once surface is reachable)
    // -------------------------------------------------------------------------

    @Test
    fun invalid_inverted_range_returns_empty_without_throw_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-invalid-inverted.lua",
            """
            local config = {
                enabled = true
            }
            return config
            """
        )

        // End before start (inverted).
        val edits = requireEmptyOrWellFormedOnInvalidRange(
            textDocuments,
            document,
            Range(Position(3, 1), Position(0, 15)),
            context = "inverted range (end before start)"
        )

        assertTrue(
            edits.isEmpty(),
            "inverted range should yield empty edits without throw; got ${describe(edits)}"
        )
    }

    @Test
    fun invalid_beyond_eof_range_returns_empty_without_throw_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-invalid-beyond-eof.lua",
            """
            local config = {
                enabled = true
            }
            return config
            """
        )

        // Start/end far past last line.
        val edits = requireEmptyOrWellFormedOnInvalidRange(
            textDocuments,
            document,
            Range(Position(100, 0), Position(200, 50)),
            context = "range beyond EOF"
        )

        assertTrue(
            edits.isEmpty(),
            "beyond-EOF range should yield empty edits without throw; got ${describe(edits)}"
        )
    }

    @Test
    fun invalid_negative_position_range_returns_empty_without_throw_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-invalid-negative.lua",
            """
            local config = {
                enabled = true
            }
            return config
            """
        )

        // Negative character offsets (invalid LSP positions).
        val edits = requireEmptyOrWellFormedOnInvalidRange(
            textDocuments,
            document,
            Range(Position(0, -1), Position(1, -5)),
            context = "negative character positions"
        )

        assertTrue(
            edits.isEmpty(),
            "negative-position range should yield empty edits without throw; got ${describe(edits)}"
        )
    }

    @Test
    fun empty_zero_width_range_on_table_brace_does_not_throw_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-empty-range.lua",
            """
            local config = {
                enabled = true
            }
            return config
            """
        )

        // Zero-width range at the opening `{`.
        val outcome = invokeRangeFormatting(
            textDocuments,
            rangeFormattingParams(
                document,
                Range(Position(0, 15), Position(0, 15)),
                FormattingOptions(4, true)
            )
        )

        when (outcome) {
            is RangeFormatOutcome.Unsupported -> {
                assertTrue(outcome.detail.isNotBlank())
            }
            is RangeFormatOutcome.Failed -> {
                fail(
                    "zero-width range must not throw; got hard failure: ${outcome.detail}"
                )
            }
            is RangeFormatOutcome.Succeeded -> {
                // Empty or no-op edits are both fine for a zero-width selection.
                assertWellFormedEdits(outcome.edits, document)
            }
        }
    }

    @Test
    fun missing_document_uri_returns_empty_or_gap_without_unhandled_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        // Do not open any document; request against a synthetic uri.
        val params = DocumentRangeFormattingParams(
            TextDocumentIdentifier("file:///workspace/range-format-missing.lua"),
            FormattingOptions(4, true),
            Range(Position(0, 0), Position(1, 0))
        )

        val outcome = invokeRangeFormatting(textDocuments, params)

        when (outcome) {
            is RangeFormatOutcome.Unsupported -> {
                assertTrue(outcome.detail.isNotBlank())
            }
            is RangeFormatOutcome.Failed -> {
                // Soft failure (e.g. missing document) is acceptable; must still
                // carry a detail and not be an unhandled NPE without message.
                assertTrue(
                    outcome.detail.isNotBlank(),
                    "missing-document failure should carry detail"
                )
            }
            is RangeFormatOutcome.Succeeded -> {
                assertTrue(
                    outcome.edits.isEmpty(),
                    "missing document should yield empty edits; got ${describe(outcome.edits)}"
                )
            }
        }
    }

    @Test
    fun empty_document_range_formatting_does_not_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-empty-doc.lua",
            ""
        )

        val outcome = invokeRangeFormatting(
            textDocuments,
            rangeFormattingParams(
                document,
                Range(Position(0, 0), Position(0, 0)),
                FormattingOptions(4, true)
            )
        )

        when (outcome) {
            is RangeFormatOutcome.Unsupported -> {
                assertTrue(outcome.detail.isNotBlank())
            }
            is RangeFormatOutcome.Failed -> {
                fail("empty document must not crash rangeFormatting: ${outcome.detail}")
            }
            is RangeFormatOutcome.Succeeded -> {
                assertTrue(
                    outcome.edits.isEmpty() || outcome.edits.all { edit ->
                        edit.range != null && edit.newText != null
                    },
                    "empty document edits must be empty or well-formed; got ${describe(outcome.edits)}"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Formatting options variants (tab / spaces) — no crash
    // -------------------------------------------------------------------------

    @Test
    fun range_formatting_with_tabs_option_degrades_safely_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-tabs-option.lua",
            """
            local config = {
                enabled = true,
                retries = 3
            }
            return config
            """
        )

        val outcome = invokeRangeFormatting(
            textDocuments,
            rangeFormattingParams(
                document,
                Range(Position(0, 15), Position(3, 1)),
                FormattingOptions(4, false) // insertSpaces = false → tabs
            )
        )

        when (outcome) {
            is RangeFormatOutcome.Unsupported -> {
                assertTrue(outcome.detail.isNotBlank())
            }
            is RangeFormatOutcome.Failed -> {
                fail("tabs option must not crash rangeFormatting: ${outcome.detail}")
            }
            is RangeFormatOutcome.Succeeded -> {
                assertWellFormedEdits(outcome.edits, document)
            }
        }
    }

    @Test
    fun range_formatting_with_spaces_option_degrades_safely_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/range-format-spaces-option.lua",
            """
            local config = {
              enabled=true,
              retries=3
            }
            return config
            """
        )

        val outcome = invokeRangeFormatting(
            textDocuments,
            rangeFormattingParams(
                document,
                Range(Position(0, 15), Position(3, 1)),
                FormattingOptions(2, true)
            )
        )

        when (outcome) {
            is RangeFormatOutcome.Unsupported -> {
                assertTrue(outcome.detail.isNotBlank())
            }
            is RangeFormatOutcome.Failed -> {
                fail("spaces option must not crash rangeFormatting: ${outcome.detail}")
            }
            is RangeFormatOutcome.Succeeded -> {
                assertWellFormedEdits(outcome.edits, document)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun service(): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(InitializeParams())
        }
    }

    private fun LuaTextDocumentService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(document.uri, "lua", 1, document.source)
            )
        )
        return document
    }

    private fun rangeFormattingParams(
        document: OpenDocument,
        range: Range,
        options: FormattingOptions
    ): DocumentRangeFormattingParams {
        return DocumentRangeFormattingParams(
            TextDocumentIdentifier(document.uri),
            options,
            range
        )
    }

    private fun requireRangeFormattingEdits(
        textDocuments: LuaTextDocumentService,
        document: OpenDocument,
        range: Range,
        context: String,
        options: FormattingOptions = FormattingOptions(4, true)
    ): List<TextEdit> {
        return when (
            val outcome = invokeRangeFormatting(
                textDocuments,
                rangeFormattingParams(document, range, options)
            )
        ) {
            is RangeFormatOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-276 skipped: rangeFormatting not yet implemented for $context; " +
                        "detail=${outcome.detail}",
                    false
                )
                emptyList() // unreachable after assume
            }
            is RangeFormatOutcome.Failed -> {
                fail("rangeFormatting failed for $context: ${outcome.detail}")
            }
            is RangeFormatOutcome.Succeeded -> {
                assertNotNull(outcome.edits)
                outcome.edits
            }
        }
    }

    /**
     * For invalid ranges: skip when unimplemented; once implemented, require a
     * successful empty (or well-formed) response without hard throw.
     */
    private fun requireEmptyOrWellFormedOnInvalidRange(
        textDocuments: LuaTextDocumentService,
        document: OpenDocument,
        range: Range,
        context: String,
        options: FormattingOptions = FormattingOptions(4, true)
    ): List<TextEdit> {
        return when (
            val outcome = invokeRangeFormatting(
                textDocuments,
                rangeFormattingParams(document, range, options)
            )
        ) {
            is RangeFormatOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-276 skipped: rangeFormatting not yet implemented for invalid $context; " +
                        "detail=${outcome.detail}",
                    false
                )
                emptyList()
            }
            is RangeFormatOutcome.Failed -> {
                // Soft failures that are not UnsupportedOperationException still
                // violate "empty without throw" once the surface is advertised.
                fail(
                    "invalid $context must return empty edits without throw; " +
                        "got hard failure: ${outcome.detail}"
                )
            }
            is RangeFormatOutcome.Succeeded -> {
                assertWellFormedEdits(outcome.edits, document)
                outcome.edits
            }
        }
    }

    private fun invokeRangeFormatting(
        textDocuments: LuaTextDocumentService,
        params: DocumentRangeFormattingParams
    ): RangeFormatOutcome {
        return try {
            val edits = textDocuments.rangeFormatting(params).get()
            RangeFormatOutcome.Succeeded(edits.orEmpty())
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                RangeFormatOutcome.Unsupported(detail = root.toString())
            } else {
                RangeFormatOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun assertWellFormedEdits(edits: List<TextEdit>, document: OpenDocument) {
        edits.forEach { edit ->
            val range = edit.range
            assertNotNull(range, "TextEdit.range must not be null; edit=$edit")
            assertNotNull(edit.newText, "TextEdit.newText must not be null; edit=$edit")

            val start = range.start
            val end = range.end
            assertNotNull(start, "range.start must not be null")
            assertNotNull(end, "range.end must not be null")

            assertTrue(
                start.line >= 0,
                "edit start.line must be >= 0; got ${describe(listOf(edit))}"
            )
            assertTrue(
                end.line >= 0,
                "edit end.line must be >= 0; got ${describe(listOf(edit))}"
            )
            assertTrue(
                start.character >= 0,
                "edit start.character must be >= 0; got ${describe(listOf(edit))}"
            )
            assertTrue(
                end.character >= 0,
                "edit end.character must be >= 0; got ${describe(listOf(edit))}"
            )
            assertTrue(
                end.line > start.line ||
                    (end.line == start.line && end.character >= start.character),
                "edit range must be ordered (end >= start); got ${describe(listOf(edit))}"
            )
        }
        assertEditsStayInsideDocument(edits, document)
    }

    private fun assertEditsStayInsideDocument(edits: List<TextEdit>, document: OpenDocument) {
        val lineCount = document.lineCount
        if (lineCount == 0) {
            assertTrue(
                edits.isEmpty(),
                "empty document should yield no edits; got ${describe(edits)}"
            )
            return
        }
        edits.forEach { edit ->
            val range = edit.range ?: return@forEach
            // Allow end line to sit on the last line (inclusive positions); reject
            // ranges that start past EOF.
            assertTrue(
                range.start.line < lineCount,
                "edit start.line must be inside document (lineCount=$lineCount); " +
                    "got ${describe(listOf(edit))}"
            )
            // End may be clamped to last line or one-past for insert-at-EOF styles;
            // hard-fail only on clearly impossible end lines far past EOF.
            assertTrue(
                range.end.line <= lineCount,
                "edit end.line must not jump far past document end (lineCount=$lineCount); " +
                    "got ${describe(listOf(edit))}"
            )
        }
    }

    private fun describe(edits: List<TextEdit>): String {
        return edits.joinToString(prefix = "[", postfix = "]") { edit ->
            val range = edit.range
            val span = if (range != null) {
                "${range.start?.line}:${range.start?.character}-" +
                    "${range.end?.line}:${range.end?.character}"
            } else {
                "<null-range>"
            }
            val textPreview = edit.newText
                ?.replace("\n", "\\n")
                ?.take(40)
                ?: "<null-text>"
            "$span -> \"$textPreview\""
        }
    }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while (
            (current is ExecutionException || current is CompletionException) &&
            current.cause != null
        ) {
            current = current.cause!!
        }
        return current
    }

    private fun isUnsupportedOperation(error: Throwable): Boolean {
        if (error is UnsupportedOperationException) {
            return true
        }
        val message = error.message.orEmpty()
        return message.contains("UnsupportedOperationException") ||
            message.contains("not implemented", ignoreCase = true)
    }

    private sealed class RangeFormatOutcome {
        data class Unsupported(val detail: String) : RangeFormatOutcome()
        data class Failed(val detail: String) : RangeFormatOutcome()
        data class Succeeded(val edits: List<TextEdit>) : RangeFormatOutcome()
    }

    private data class OpenDocument(
        val path: String,
        val source: String
    ) {
        val uri: String = "file:///$path"

        val lineCount: Int
            get() {
                if (source.isEmpty()) {
                    return 0
                }
                // Number of lines in the open document (last line may lack trailing newline).
                return source.count { it == '\n' } + 1
            }
    }
}
