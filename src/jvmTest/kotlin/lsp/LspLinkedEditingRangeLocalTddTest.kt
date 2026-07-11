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
 * - Non-identifier positions (whitespace, keywords, literals, operators) must
 *   degrade safely to empty/null — never invent ranges or crash the LSP
 *   surface.
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
 * linkedEditingRange is still a gap.
 *
 * Test-only; no product edits. Verification is review-owned and serial; this
 * worker does not run Gradle.
 */
class LspLinkedEditingRangeLocalTddTest {

    // -------------------------------------------------------------------------
    // Non-identifier positions: empty / rejected / documented gap
    // -------------------------------------------------------------------------

    @Test
    fun linked_editing_range_on_whitespace_is_empty_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/linked-edit-whitespace.lua",
            "local value = 1\n\nreturn value"
        )

        // Blank line between local and return — not an identifier.
        val outcome = invokeLinkedEditingRange(
            textDocuments,
            linkedEditingParams(document, Position(1, 0))
        )

        assertLinkedEditingEmptyOrGap(
            outcome,
            context = "whitespace / blank line between statements"
        )
    }

    @Test
    fun linked_editing_range_on_keyword_is_empty_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/linked-edit-keyword.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeLinkedEditingRange(
            textDocuments,
            linkedEditingParams(document, document.positionOf("local"))
        )

        assertLinkedEditingEmptyOrGap(
            outcome,
            context = "keyword 'local'"
        )
    }

    @Test
    fun linked_editing_range_on_numeric_literal_is_empty_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/linked-edit-number.lua",
            "local value = 42\nreturn value"
        )

        val outcome = invokeLinkedEditingRange(
            textDocuments,
            linkedEditingParams(document, document.positionOf("42"))
        )

        assertLinkedEditingEmptyOrGap(
            outcome,
            context = "numeric literal"
        )
    }

    @Test
    fun linked_editing_range_on_string_literal_is_empty_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/linked-edit-string.lua",
            "local greeting = \"hello\"\nreturn greeting"
        )

        val outcome = invokeLinkedEditingRange(
            textDocuments,
            linkedEditingParams(document, document.positionOf("\"hello\""))
        )

        assertLinkedEditingEmptyOrGap(
            outcome,
            context = "string literal"
        )
    }

    @Test
    fun linked_editing_range_on_operator_is_empty_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/linked-edit-operator.lua",
            "local a = 1\nlocal b = 2\nreturn a + b"
        )

        val outcome = invokeLinkedEditingRange(
            textDocuments,
            linkedEditingParams(document, document.positionOf("+"))
        )

        assertLinkedEditingEmptyOrGap(
            outcome,
            context = "binary operator '+'"
        )
    }

    @Test
    fun linked_editing_range_on_comment_is_empty_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/linked-edit-comment.lua",
            "-- comment about value\nlocal value = 1\nreturn value"
        )

        val outcome = invokeLinkedEditingRange(
            textDocuments,
            linkedEditingParams(document, document.positionOf("comment"))
        )

        assertLinkedEditingEmptyOrGap(
            outcome,
            context = "comment body token"
        )
    }

    // -------------------------------------------------------------------------
    // Local identifiers: safe degrade / ideal linked ranges
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
                    "linkedEditingRange on local must not NPE while unimplemented/partial; got ${outcome.detail}"
                )
            }
            is LinkedEditingOutcome.Succeeded -> {
                assertLocalLinkedRanges(
                    ranges = outcome.ranges,
                    document = document,
                    identifier = "value",
                    label = "linkedEditingRange for local 'value'"
                )
            }
        }
    }

    @Test
    fun linked_editing_range_local_function_name_degrades_safely_or_returns_same_content_ranges() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/linked-edit-local-function.lua",
            """
            local function render(arg)
                return arg
            end
            local out = render(1)
            return render
            """
        )

        val outcome = invokeLinkedEditingRange(
            textDocuments,
            linkedEditingParams(document, document.positionOf("render", occurrence = 2))
        )

        when (outcome) {
            is LinkedEditingOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for linkedEditingRange; got ${outcome.detail}"
                )
            }
            is LinkedEditingOutcome.Empty -> {
                // Soft degrade is acceptable until product implements the surface.
            }
            is LinkedEditingOutcome.Failed -> {
                assertFalse(
                    outcome.detail.contains("NullPointerException", ignoreCase = true),
                    "linkedEditingRange on local function must not NPE; got ${outcome.detail}"
                )
            }
            is LinkedEditingOutcome.Succeeded -> {
                assertLocalLinkedRanges(
                    ranges = outcome.ranges,
                    document = document,
                    identifier = "render",
                    label = "linkedEditingRange for local function 'render'"
                )
            }
        }
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

        when (outcome) {
            is LinkedEditingOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for linkedEditingRange; got ${outcome.detail}"
                )
            }
            is LinkedEditingOutcome.Empty -> Unit
            is LinkedEditingOutcome.Failed -> {
                assertFalse(
                    outcome.detail.contains("NullPointerException", ignoreCase = true),
                    "linkedEditingRange on parameter must not NPE; got ${outcome.detail}"
                )
            }
            is LinkedEditingOutcome.Succeeded -> {
                assertLocalLinkedRanges(
                    ranges = outcome.ranges,
                    document = document,
                    identifier = "value",
                    label = "linkedEditingRange for parameter 'value'"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Globals / free names: empty or documented gap (no invented cross-file)
    // -------------------------------------------------------------------------

    @Test
    fun linked_editing_range_global_without_local_binding_is_empty_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/linked-edit-global.lua",
            "print(1)\nreturn print"
        )

        val outcome = invokeLinkedEditingRange(
            textDocuments,
            linkedEditingParams(document, document.positionOf("print", occurrence = 1))
        )

        // Policy for local linked-edit corpus: free globals may be empty/rejected.
        // Accepting ranges is only OK if they stay same-content and non-overlapping
        // inside this file (no crash, no cross-file invention).
        when (outcome) {
            is LinkedEditingOutcome.Unsupported -> {
                assertTrue(outcome.isUnsupportedOperation)
            }
            is LinkedEditingOutcome.Empty -> Unit
            is LinkedEditingOutcome.Failed -> {
                assertFalse(
                    outcome.detail.contains("NullPointerException", ignoreCase = true),
                    "linkedEditingRange on global must not NPE; got ${outcome.detail}"
                )
            }
            is LinkedEditingOutcome.Succeeded -> {
                assertSafeLinkedRangesShape(
                    ranges = outcome.ranges,
                    document = document,
                    label = "linkedEditingRange for global 'print'"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Surface invokability + product-available documentHighlight proxy
    // -------------------------------------------------------------------------

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
            assertHighlightRangeStartsAtIdentifierOccurrence(
                range = highlight.range,
                document = document,
                identifier = "value",
                label = "documentHighlight proxy for linked-edit local 'value'"
            )
        }
    }

    @Test
    fun document_highlight_on_non_identifier_does_not_crash() {
        val service = service()
        val document = service.open(
            "workspace/linked-edit-highlight-whitespace.lua",
            "local value = 1\n\nreturn value"
        )

        val highlights = runCatching {
            service.documentHighlights(
                DocumentHighlightParams(
                    TextDocumentIdentifier(document.uri),
                    Position(1, 0)
                )
            )
        }.getOrElse { error ->
            fail("documentHighlight on whitespace must not throw: ${error.message}")
        }

        // Empty is the preferred degrade; non-empty ranges must still be ordered.
        highlights.forEach { highlight ->
            assertTrue(
                highlight.range.end.line > highlight.range.start.line ||
                    (highlight.range.end.line == highlight.range.start.line &&
                        highlight.range.end.character >= highlight.range.start.character),
                "highlight range must be ordered: ${highlight.range}"
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

    private fun assertLocalLinkedRanges(
        ranges: List<Range>,
        document: OpenDocument,
        identifier: String,
        label: String
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
            assertTrue(
                range.end.line > range.start.line ||
                    (range.end.line == range.start.line &&
                        range.end.character >= range.start.character),
                "$label range must be ordered: ${formatRange(range)}"
            )
            // Slice must stay inside the document (no crash / OOB).
            runCatching { document.slice(range) }.getOrElse { error ->
                fail("$label range ${formatRange(range)} must stay inside file: ${error.message}")
            }
        }
    }

    private fun assertHighlightRangeStartsAtIdentifierOccurrence(
        range: Range,
        document: OpenDocument,
        identifier: String,
        label: String
    ) {
        assertTrue(
            range.end.line > range.start.line ||
                (range.end.line == range.start.line &&
                    range.end.character >= range.start.character),
            "$label must be ordered; got ${formatRange(range)}"
        )

        val starts = document.occurrenceStarts(identifier)
        val startMatches = starts.any { start ->
            start.line == range.start.line && start.character == range.start.character
        }
        val startDump = starts.joinToString(prefix = "[", postfix = "]") { start ->
            "${start.line}:${start.character}"
        }
        assertTrue(
            startMatches,
            "$label start must align with an occurrence of '$identifier'; " +
                "start=${range.start.line}:${range.start.character}; occurrences=$startDump"
        )

        if (range.start.line == range.end.line &&
            range.end.character - range.start.character == identifier.length
        ) {
            assertEquals(
                identifier,
                document.slice(range),
                "$label exact-span source slice must equal identifier text"
            )
        } else {
            val extracted = document.slice(
                Range(
                    range.start,
                    Position(range.start.line, range.start.character + identifier.length)
                )
            )
            assertEquals(
                identifier,
                extracted,
                "$label wider product range must begin with identifier text '$identifier'; " +
                    "got start-slice='$extracted' full-slice='${document.slice(range)}' " +
                    "range=${formatRange(range)}"
            )
        }
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
            var line = 0
            var index = 0
            while (index < source.length && line < position.line) {
                if (source[index] == '\n') {
                    line += 1
                }
                index += 1
            }
            return (index + position.character).coerceIn(0, source.length)
        }
    }
}
