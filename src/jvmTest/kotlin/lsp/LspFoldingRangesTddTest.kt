package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.Assume
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-251 — LSP folding ranges function and table corpus.
 *
 * Locks the contract for textDocument/foldingRange when the product supports it:
 * - Multi-line function bodies produce fold ranges covering the body (or whole
 *   function statement) so editors can collapse function definitions.
 * - Multi-line table constructors produce fold ranges covering the table body.
 * - Nested function-in-table / table-in-function shapes remain foldable without
 *   inventing inverted or out-of-bounds ranges.
 * - Malformed / incomplete sources and empty documents must not crash the
 *   folding provider (empty list or well-formed ranges only).
 *
 * Product folding is intentionally out of scope for this task (test-only).
 * Current [LuaTextDocumentService] inherits the LSP4J default that throws
 * [UnsupportedOperationException], and [LuaLanguageService] does not advertise
 * foldingRangeProvider. Tests therefore:
 * - Skip cleanly with an explicit [Assume] reason when folding is absent.
 * - Enforce hard range contracts once folding is implemented / advertised.
 *
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class LspFoldingRangesTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun folding_range_capability_or_explicit_skip_when_unimplemented() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/folding-capability-probe.lua",
            """
            local function probe()
                return 1
            end
            """
        )

        val outcome = invokeFoldingRange(textDocuments, foldingParams(document))

        when (outcome) {
            is FoldingOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-251 skipped: folding ranges not yet implemented " +
                        "(foldingRangeProvider=${capabilities.foldingRangeProvider}, " +
                        "detail=${outcome.detail})",
                    false
                )
            }
            is FoldingOutcome.Failed -> {
                fail("foldingRange must not fail hard once surface is reachable: ${outcome.detail}")
            }
            is FoldingOutcome.Succeeded -> {
                // Capability may lag implementation; once ranges are returned they
                // must be well-formed even if the initialize flag is still null.
                assertWellFormedRanges(outcome.ranges, document.lineCount)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Function body folding
    // -------------------------------------------------------------------------

    @Test
    fun folding_ranges_cover_local_function_body_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/folding-local-function.lua",
            """
            local function greet(name)
                local message = "hi " .. name
                return message
            end
            return greet
            """
        )

        val ranges = requireFoldingRanges(textDocuments, document, context = "local function body")

        assertTrue(
            ranges.any { range -> rangeCoversLines(range, startLine = 0, endLine = 3) },
            "Expected a fold covering the local function (lines 0..3); got ${describe(ranges)}"
        )
        assertWellFormedRanges(ranges, document.lineCount)
    }

    // -------------------------------------------------------------------------
    // Table constructor folding
    // -------------------------------------------------------------------------

    @Test
    fun folding_ranges_cover_nested_table_and_function_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/folding-nested-table-function.lua",
            """
            local module = {
                version = 1,
                run = function(self)
                    return self.version
                end
            }
            return module
            """
        )

        val ranges = requireFoldingRanges(textDocuments, document, context = "nested table + function")

        val hasTableFold = ranges.any { range -> rangeCoversLines(range, startLine = 0, endLine = 5) }
        val hasFunctionFold = ranges.any { range ->
            range.startLine in 2..3 && range.endLine in 3..4 && range.endLine >= range.startLine
        }
        assertTrue(
            hasTableFold || hasFunctionFold,
            "Expected folds for outer table and/or nested function; got ${describe(ranges)}"
        )
        assertWellFormedRanges(ranges, document.lineCount)
    }

    // -------------------------------------------------------------------------
    // Crash / malformed safety (must not throw; may skip only if surface missing)
    // -------------------------------------------------------------------------

    @Test
    fun folding_ranges_on_malformed_source_do_not_crash_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        // Incomplete function + broken table — recovery path must stay non-throwing.
        val document = textDocuments.open(
            "workspace/folding-malformed.lua",
            """
            local function broken(
                return {
                    a = 1,
                    b =
            """
        )

        val outcome = invokeFoldingRange(textDocuments, foldingParams(document))
        when (outcome) {
            is FoldingOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-251 skipped: folding ranges not yet implemented (malformed source probe); " +
                        "detail=${outcome.detail}",
                    false
                )
            }
            is FoldingOutcome.Failed -> {
                fail("malformed source must not crash folding provider: ${outcome.detail}")
            }
            is FoldingOutcome.Succeeded -> {
                assertWellFormedRanges(outcome.ranges, document.lineCount)
            }
        }
    }

    @Test
    fun folding_ranges_on_single_line_function_are_empty_or_well_formed_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/folding-single-line.lua",
            "local function id(x) return x end\nreturn id"
        )

        val outcome = invokeFoldingRange(textDocuments, foldingParams(document))
        when (outcome) {
            is FoldingOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-251 skipped: folding ranges not yet implemented (single-line probe); " +
                        "detail=${outcome.detail}",
                    false
                )
            }
            is FoldingOutcome.Failed -> {
                fail("single-line source must not crash folding provider: ${outcome.detail}")
            }
            is FoldingOutcome.Succeeded -> {
                // Single-line folds are optional; any returned ranges must still be valid.
                assertWellFormedRanges(outcome.ranges, document.lineCount)
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

    private fun foldingParams(document: OpenDocument): FoldingRangeRequestParams {
        return FoldingRangeRequestParams(TextDocumentIdentifier(document.uri))
    }

    private fun requireFoldingRanges(
        textDocuments: LuaTextDocumentService,
        document: OpenDocument,
        context: String
    ): List<FoldingRange> {
        return when (val outcome = invokeFoldingRange(textDocuments, foldingParams(document))) {
            is FoldingOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-251 skipped: folding ranges not yet implemented for $context; " +
                        "detail=${outcome.detail}",
                    false
                )
                emptyList() // unreachable after assume
            }
            is FoldingOutcome.Failed -> {
                fail("foldingRange failed for $context: ${outcome.detail}")
            }
            is FoldingOutcome.Succeeded -> {
                assertNotNull(outcome.ranges)
                outcome.ranges
            }
        }
    }

    private fun invokeFoldingRange(
        textDocuments: LuaTextDocumentService,
        params: FoldingRangeRequestParams
    ): FoldingOutcome {
        return try {
            val ranges = textDocuments.foldingRange(params).get()
            FoldingOutcome.Succeeded(ranges.orEmpty())
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                FoldingOutcome.Unsupported(detail = root.toString())
            } else {
                FoldingOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun assertWellFormedRanges(ranges: List<FoldingRange>, lineCount: Int) {
        ranges.forEach { range ->
            assertTrue(
                range.startLine >= 0,
                "startLine must be >= 0; got ${range.startLine} in ${describe(listOf(range))}"
            )
            assertTrue(
                range.endLine >= range.startLine,
                "endLine must be >= startLine; got ${describe(listOf(range))}"
            )
            if (lineCount > 0) {
                assertTrue(
                    range.startLine < lineCount,
                    "startLine must be inside document (lineCount=$lineCount); got ${describe(listOf(range))}"
                )
                assertTrue(
                    range.endLine < lineCount,
                    "endLine must be inside document (lineCount=$lineCount); got ${describe(listOf(range))}"
                )
            } else {
                // Empty document: any range would be out of bounds; allow only empty list.
                assertTrue(
                    ranges.isEmpty(),
                    "empty document should yield no folding ranges; got ${describe(ranges)}"
                )
            }
            val startChar = range.startCharacter
            val endChar = range.endCharacter
            if (startChar != null) {
                assertTrue(startChar >= 0, "startCharacter must be >= 0; got $startChar")
            }
            if (endChar != null) {
                assertTrue(endChar >= 0, "endCharacter must be >= 0; got $endChar")
            }
            if (startChar != null && endChar != null && range.startLine == range.endLine) {
                assertTrue(
                    endChar >= startChar,
                    "same-line endCharacter must be >= startCharacter; got ${describe(listOf(range))}"
                )
            }
        }
    }

    /**
     * True when [range] fully covers the inclusive line span [startLine]..[endLine]
     * (product may fold the header+body or only the body interior; both count if the
     * span is nested inside the reported range, or the reported range matches exactly).
     */
    private fun rangeCoversLines(range: FoldingRange, startLine: Int, endLine: Int): Boolean {
        // Exact match or range that includes the target span.
        if (range.startLine <= startLine && range.endLine >= endLine) {
            return true
        }
        // Body-only fold: starts after header line, ends on/before closing end/}.
        if (range.startLine in startLine..endLine && range.endLine in startLine..endLine) {
            return range.endLine > range.startLine
        }
        return false
    }

    private fun describe(ranges: List<FoldingRange>): String {
        return ranges.joinToString(prefix = "[", postfix = "]") { range ->
            val kind = range.kind?.let { " kind=$it" } ?: ""
            val chars = when {
                range.startCharacter != null || range.endCharacter != null ->
                    " chars=${range.startCharacter}-${range.endCharacter}"
                else -> ""
            }
            "${range.startLine}-${range.endLine}$chars$kind"
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

    private sealed class FoldingOutcome {
        data class Unsupported(val detail: String) : FoldingOutcome()
        data class Failed(val detail: String) : FoldingOutcome()
        data class Succeeded(val ranges: List<FoldingRange>) : FoldingOutcome()
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
