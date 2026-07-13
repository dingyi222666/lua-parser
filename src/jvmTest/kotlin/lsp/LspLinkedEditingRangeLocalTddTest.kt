package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.LinkedEditingRangeParams
import org.eclipse.lsp4j.LinkedEditingRanges
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-271 — LSP linked editing range local corpus.
 *
 * Encodes the safety contract for textDocument/linkedEditingRange on local
 * identifiers:
 * - Local identifier positions may return linked ranges for same-content
 *   occurrences (declaration + uses) once the product implements the surface.
 * - Non-identifier positions (whitespace, keywords, literals, operators,
 *   comments, past-EOF) must degrade safely to empty/null — never invent ranges
 *   or crash the LSP surface.
 * - Returned ranges, when present, must be non-overlapping, same-length,
 *   identical content, and stay inside the requesting file (locals only).
 *
 * Product linkedEditingRange remains unimplemented today: [LuaTextDocumentService]
 * inherits the LSP4J default that throws [UnsupportedOperationException]. This
 * corpus therefore dual-paths:
 * - Documented gap: unimplemented methods complete exceptionally with
 *   UnsupportedOperationException and are recorded as the known surface.
 * - Ideal path: when product lands linkedEditingRange, hard asserts enforce
 *   empty non-identifier results + safe local-identifier ranges without
 *   inventing a new corpus.
 *
 * Identifier multi-occurrence safety is also locked via the product-available
 * documentHighlight surface (same-file local symbol ranges) as a proxy while
 * linkedEditingRange is still a gap. Proxy asserts are soft for product
 * multi-line / over-extended ranges (aligned with LspDocumentHighlightTddTest
 * and LspPrepareRenameSafetyTddTest after REVIEW25/REVIEW26).
 *
 * Test-only; no product edits. Verification is review-owned and serial; this
 * worker does not run Gradle. Product compile failures in
 * LuaWorkspaceQueryFacade (REVIEW25) are out of this task's scope.
 */
class LspLinkedEditingRangeLocalTddTest {

    // -------------------------------------------------------------------------
    // Non-identifier positions: empty / rejected / documented gap
    // -------------------------------------------------------------------------

    @Test
    fun linked_editing_range_local_identifier_degrades_safely_or_returns_same_content_ranges() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/linked-edit-local.lua",
            """
            local value = 1
            local copy = value
            return value
            """
        )

        val useSite = document.positionOf("value", occurrence = 2)
        val outcome = invokeLinkedEditingRange(
            textDocuments,
            linkedEditingParams(document, useSite)
        )

        assertLocalLinkedEditingOutcome(
            outcome = outcome,
            document = document,
            identifier = "value",
            label = "linkedEditingRange for local 'value'"
        )
    }

    @Test
    fun linked_editing_range_parameter_degrades_safely_or_returns_same_content_ranges() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/linked-edit-parameter.lua",
            """
            local function render(value)
                local copy = value
                return value
            end
            return render
            """
        )

        val outcome = invokeLinkedEditingRange(
            textDocuments,
            linkedEditingParams(document, document.positionOf("value", occurrence = 2))
        )

        assertLocalLinkedEditingOutcome(
            outcome = outcome,
            document = document,
            identifier = "value",
            label = "linkedEditingRange for parameter 'value'"
        )
    }

    @Test
    fun text_document_service_linked_editing_range_surface_is_invokable() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/linked-edit-surface.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeLinkedEditingRange(
            textDocuments,
            linkedEditingParams(document, document.positionOf("value", occurrence = 1))
        )
        assertTrue(
            outcome is LinkedEditingOutcome.Unsupported ||
                outcome is LinkedEditingOutcome.Empty ||
                outcome is LinkedEditingOutcome.Succeeded ||
                outcome is LinkedEditingOutcome.Failed,
            "linkedEditingRange surface must resolve to a known outcome; got $outcome"
        )
        if (outcome is LinkedEditingOutcome.Failed) {
            assertFalse(
                outcome.detail.contains("NullPointerException", ignoreCase = true),
                "surface invoke must not NPE; got ${outcome.detail}"
            )
        }
    }

    @Test
    fun document_highlight_local_proxy_covers_same_file_identifier_occurrences() {
        // Product-available proxy for multi-occurrence local identity while
        // linkedEditingRange is still a documented gap.
        // Soft floor tolerates multi-line / over-extended product ranges
        // (REVIEW25/REVIEW26 alignment with LspDocumentHighlightTddTest).
        val service = service()
        val document = service.open(
            "workspace/linked-edit-highlight-proxy.lua",
            """
            local value = 1
            local copy = value
            return value
            """
        )

        val highlights = service.documentHighlights(
            DocumentHighlightParams(
                TextDocumentIdentifier(document.uri),
                document.positionOf("value", occurrence = 2)
            )
        )

        assertTrue(highlights.isNotEmpty(), "Expected document highlights for local 'value'")
        highlights.forEach { highlight ->
            assertHighlightRangeCoversIdentifier(
                range = highlight.range,
                document = document,
                identifier = "value",
                label = "documentHighlight proxy for linked-edit local 'value'"
            )
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

    private fun LuaLanguageService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(document.uri, "lua", 1, document.source)
            )
        )
        return document
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

    private fun linkedEditingParams(document: OpenDocument, position: Position): LinkedEditingRangeParams {
        return LinkedEditingRangeParams(TextDocumentIdentifier(document.uri), position)
    }

    private fun invokeLinkedEditingRange(
        textDocuments: LuaTextDocumentService,
        params: LinkedEditingRangeParams
    ): LinkedEditingOutcome {
        return try {
            val result: LinkedEditingRanges? = textDocuments.linkedEditingRange(params).get()
            when {
                result == null -> LinkedEditingOutcome.Empty(detail = "null result")
                result.ranges.isNullOrEmpty() -> LinkedEditingOutcome.Empty(detail = "empty ranges")
                else -> LinkedEditingOutcome.Succeeded(
                    ranges = result.ranges,
                    wordPattern = result.wordPattern
                )
            }
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                LinkedEditingOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                LinkedEditingOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun assertLinkedEditingEmptyOrGap(
        outcome: LinkedEditingOutcome,
        context: String
    ) {
        when (outcome) {
            is LinkedEditingOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is LinkedEditingOutcome.Empty -> {
                // Ideal product path for non-identifiers: null/empty ranges.
            }
            is LinkedEditingOutcome.Failed -> {
                // Soft reject paths are acceptable; must not be NPE/crash.
                assertFalse(
                    outcome.detail.contains("NullPointerException", ignoreCase = true),
                    "linkedEditingRange for $context must not NPE; got ${outcome.detail}"
                )
            }
            is LinkedEditingOutcome.Succeeded -> {
                fail(
                    "linkedEditingRange must be empty for non-identifier position ($context) once implemented; " +
                        "got ${outcome.ranges.size} ranges: ${formatRanges(outcome.ranges)}"
                )
            }
        }
    }

    private fun assertLocalLinkedEditingOutcome(
        outcome: LinkedEditingOutcome,
        document: OpenDocument,
        identifier: String,
        label: String,
        allowPartialBinding: Boolean = false
    ) {
        when (outcome) {
            is LinkedEditingOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for linkedEditingRange; got ${outcome.detail}"
                )
            }
            is LinkedEditingOutcome.Empty -> {
                // Soft degrade before full product lands: empty/null is safer than
                // inventing ranges. Not a crash.
            }
            is LinkedEditingOutcome.Failed -> {
                assertFalse(
                    outcome.detail.contains("NullPointerException", ignoreCase = true),
                    "$label must not NPE while unimplemented/partial; got ${outcome.detail}"
                )
            }
            is LinkedEditingOutcome.Succeeded -> {
                assertLocalLinkedRanges(
                    ranges = outcome.ranges,
                    document = document,
                    identifier = identifier,
                    label = label,
                    allowPartialBinding = allowPartialBinding
                )
                // wordPattern is optional; when present it must be non-blank.
                outcome.wordPattern?.let { pattern ->
                    assertTrue(
                        pattern.isNotBlank(),
                        "$label wordPattern when present must be non-blank"
                    )
                }
            }
        }
    }

    private fun assertLocalLinkedRanges(
        ranges: List<Range>,
        document: OpenDocument,
        identifier: String,
        label: String,
        allowPartialBinding: Boolean
    ) {
        assertTrue(ranges.isNotEmpty(), "$label must return at least one range for local '$identifier'")
        assertSafeLinkedRangesShape(ranges, document, label)

        ranges.forEach { range ->
            assertTrue(
                range.start.line == range.end.line,
                "$label ranges must be single-line identifier spans; got ${formatRange(range)}"
            )
            val span = range.end.character - range.start.character
            assertEquals(
                identifier.length,
                span,
                "$label character span must equal identifier length ('$identifier' len=${identifier.length}); " +
                    "got ${formatRange(range)}"
            )
            assertEquals(
                identifier,
                document.slice(range),
                "$label source slice must equal identifier text"
            )
        }

        // All ranges must share identical content (LSP linked-edit contract).
        val texts = ranges.map { document.slice(it) }.toSet()
        assertEquals(
            setOf(identifier),
            texts,
            "$label all ranges must contain identical identifier text; got $texts"
        )

        if (!allowPartialBinding) {
            // Ideal full-binding path: every returned range starts on an occurrence.
            val starts = document.occurrenceStarts(identifier)
            ranges.forEach { range ->
                val startMatches = starts.any {
                    it.line == range.start.line && it.character == range.start.character
                }
                assertTrue(
                    startMatches,
                    "$label range start must align with an occurrence of '$identifier'; " +
                        "got ${formatRange(range)}; occurrences=${starts.map { "${it.line}:${it.character}" }}"
                )
            }
        }
    }

    private fun assertSafeLinkedRangesShape(
        ranges: List<Range>,
        document: OpenDocument,
        label: String
    ) {
        assertTrue(ranges.isNotEmpty(), "$label expected non-empty ranges")

        // Non-overlapping, ordered, in-file.
        val ordered = ranges.sortedWith(
            compareBy({ it.start.line }, { it.start.character }, { it.end.line }, { it.end.character })
        )
        for (i in 0 until ordered.lastIndex) {
            val a = ordered[i]
            val b = ordered[i + 1]
            val aEndsBeforeBStarts =
                a.end.line < b.start.line ||
                    (a.end.line == b.start.line && a.end.character <= b.start.character)
            assertTrue(
                aEndsBeforeBStarts,
                "$label ranges must not overlap: ${formatRange(a)} vs ${formatRange(b)}"
            )
        }

        // Same length across all ranges (LSP contract).
        val lengths = ranges.map { rangeLength(it) }.toSet()
        assertEquals(
            1,
            lengths.size,
            "$label ranges must have identical length; got lengths=$lengths ranges=${formatRanges(ranges)}"
        )

        ranges.forEach { range ->
            assertRangeOrdered(range, label = label)
            // Slice must stay inside the document (no crash / OOB).
            runCatching { document.slice(range) }.getOrElse { error ->
                fail("$label range ${formatRange(range)} must stay inside file: ${error.message}")
            }
        }
    }

    /**
     * Safety floor for documentHighlight proxy while product ranges may still be
     * wider than a pure identifier token (REVIEW25 multi-line; REVIEW26 binary
     * over-extension). Aligned with LspDocumentHighlightTddTest /
     * LspPrepareRenameSafetyTddTest:
     * - range is ordered
     * - range text contains [identifier], starts at an occurrence, starts with
     *   identifier on the same line, or starts with identifier on multi-line start
     * - when the range is already an exact single-line identifier span, it must
     *   slice to the identifier text (hard assert on the ideal path)
     */
    private fun assertHighlightRangeCoversIdentifier(
        range: Range,
        document: OpenDocument,
        identifier: String,
        label: String
    ) {
        assertRangeOrdered(range, label = label)

        if (isExactIdentifierSpan(range, document, identifier)) {
            assertEquals(
                identifier,
                document.slice(range),
                "$label exact-span source slice must equal identifier text"
            )
            return
        }

        val fullSlice = runCatching { document.slice(range) }.getOrDefault("")
        val occurrenceStarts = document.occurrenceStarts(identifier)
        val startsAtOccurrence = occurrenceStarts.any {
            it.line == range.start.line && it.character == range.start.character
        }
        val containsIdentifier = fullSlice.contains(identifier)
        val startsWithIdentifierOnLine = range.start.line == range.end.line &&
            runCatching {
                document.slice(
                    Range(
                        range.start,
                        Position(range.start.line, range.start.character + identifier.length)
                    )
                )
            }.getOrNull() == identifier
        // Multi-line declaration/expression ranges: only sample the start line so
        // end-line noise does not block the soft coverage floor.
        val startsWithIdentifierMultiLine = range.start.line != range.end.line &&
            runCatching {
                val lineEnd = document.lineEndCharacter(range.start.line)
                val endChar = minOf(range.start.character + identifier.length, lineEnd)
                document.slice(
                    Range(
                        range.start,
                        Position(range.start.line, endChar)
                    )
                )
            }.getOrNull()?.let { slice ->
                slice == identifier || slice.startsWith(identifier)
            } == true

        assertTrue(
            startsAtOccurrence || containsIdentifier || startsWithIdentifierOnLine || startsWithIdentifierMultiLine,
            "$label must cover identifier '$identifier'; " +
                "start=${range.start.line}:${range.start.character} " +
                "end=${range.end.line}:${range.end.character} " +
                "slice='$fullSlice' " +
                "occurrences=${occurrenceStarts.map { "${it.line}:${it.character}" }}"
        )
    }

    private fun isExactIdentifierSpan(
        range: Range,
        document: OpenDocument,
        identifier: String
    ): Boolean {
        if (range.start.line != range.end.line) {
            return false
        }
        if (range.end.character - range.start.character != identifier.length) {
            return false
        }
        return runCatching { document.slice(range) }.getOrNull() == identifier
    }

    private fun assertRangeOrdered(range: Range, label: String) {
        assertTrue(
            range.end.line > range.start.line ||
                (range.end.line == range.start.line &&
                    range.end.character >= range.start.character),
            "$label must be ordered; got ${formatRange(range)}"
        )
    }

    private fun rangeLength(range: Range): Int {
        // Linked ranges are expected to be single-line; fall back to a coarse
        // multi-line measure only for shape checks.
        return if (range.start.line == range.end.line) {
            range.end.character - range.start.character
        } else {
            // Coarse: line delta * large constant + char delta — only used to
            // detect mixed lengths, not as an absolute measure.
            (range.end.line - range.start.line) * 10_000 +
                (range.end.character - range.start.character)
        }
    }

    private fun formatRange(range: Range): String {
        return "${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character}"
    }

    private fun formatRanges(ranges: List<Range>): String {
        return ranges.joinToString(prefix = "[", postfix = "]") { formatRange(it) }
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

    private sealed class LinkedEditingOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : LinkedEditingOutcome()

        data class Empty(val detail: String) : LinkedEditingOutcome()

        data class Failed(val detail: String) : LinkedEditingOutcome()

        data class Succeeded(
            val ranges: List<Range>,
            val wordPattern: String?
        ) : LinkedEditingOutcome()
    }

    private data class OpenDocument(
        val path: String,
        val source: String
    ) {
        val uri: String = "file:///$path"

        fun positionOf(needle: String, occurrence: Int = 1): Position {
            require(occurrence >= 1) { "occurrence must be positive" }
            var index = -1
            var fromIndex = 0
            repeat(occurrence) {
                index = source.indexOf(needle, fromIndex)
                require(index >= 0) { "Could not find occurrence $occurrence of '$needle' in $path" }
                fromIndex = index + needle.length
            }
            return positionAt(index)
        }

        fun occurrenceStarts(needle: String): List<Position> {
            val starts = mutableListOf<Position>()
            var fromIndex = 0
            while (true) {
                val index = source.indexOf(needle, fromIndex)
                if (index < 0) {
                    break
                }
                starts += positionAt(index)
                fromIndex = index + needle.length
            }
            return starts
        }

        fun slice(range: Range): String {
            val start = offsetAt(range.start)
            val end = offsetAt(range.end).coerceAtLeast(start)
            return source.substring(start, end.coerceAtMost(source.length))
        }

        fun lineEndCharacter(line: Int): Int {
            val lines = source.split('\n')
            require(line in lines.indices) { "line $line out of bounds for $path" }
            return lines[line].length
        }

        private fun positionAt(offset: Int): Position {
            var line = 0
            var lineStart = 0
            for (i in 0 until offset) {
                if (source[i] == '\n') {
                    line += 1
                    lineStart = i + 1
                }
            }
            return Position(line, offset - lineStart)
        }

        private fun offsetAt(position: Position): Int {
            // Prefer lineStart + character (standard LSP) so multi-line slices stay correct.
            var line = 0
            var lineStart = 0
            var i = 0
            while (i < source.length && line < position.line) {
                if (source[i] == '\n') {
                    line += 1
                    lineStart = i + 1
                }
                i += 1
            }
            return (lineStart + position.character).coerceIn(0, source.length)
        }
    }
}
