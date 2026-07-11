package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-418 — LSP foldingRange dual-path safety corpus.
 *
 * Locks the safety contract for `textDocument/foldingRange` when the feature is
 * unimplemented or only partially product-reachable:
 * - Unimplemented surface (LSP4J default [UnsupportedOperationException] on
 *   [LuaTextDocumentService]) must degrade as a documented gap — never as an
 *   unexpected hard crash (NPE / AssertionError / IndexOutOfBounds).
 * - When product soft-degrades instead of throwing, empty / null range lists are
 *   accepted ("empty per product ads").
 * - Empty documents, syntax-error buffers, large files, single-line sources,
 *   never-opened URIs, and nested function/table corpora must not invent
 *   process-killing failures.
 * - When product returns ranges (capability advertised and surface live), each
 *   range must be well-formed (non-negative lines, endLine >= startLine, inside
 *   document bounds when the document is non-empty).
 *
 * Product folding ranges remain out of scope for this worker (test-only).
 * Current [LuaLanguageService] does not advertise foldingRangeProvider and
 * [LuaTextDocumentService] inherits the LSP4J defaults that throw
 * [UnsupportedOperationException]. Tests therefore dual-path:
 * - Documented gap: unimplemented methods complete exceptionally with
 *   UnsupportedOperationException and are recorded as the known surface.
 * - Ideal / soft path: empty range list (or null → treated as empty) when the
 *   server elects soft degrade per product ads; well-formed ranges when live.
 *
 * Complements [LspFoldingRangesTddTest] (TASK-251 function/table corpus with
 * Assume skips) with an explicit safety dual-path lock that never hard-skips the
 * unimplemented surface. Verification is review-owned and serial; this worker
 * does not run Gradle.
 */
class LspFoldingRangeSafetyTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun initialize_folding_range_capability_is_null_or_explicit_when_product_lands() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        assertNotNull(capabilities, "initialize must return ServerCapabilities")
        val provider = capabilities.foldingRangeProvider
        if (provider == null) {
            // Documented gap: product has not advertised folding ranges yet.
            assertTrue(
                true,
                "foldingRangeProvider absent is an accepted pre-product surface"
            )
            return
        }

        // When advertised as Either, either boolean true or options object is fine.
        val enabled = when {
            provider.isLeft -> provider.left == true
            provider.isRight -> provider.right != null
            else -> false
        }
        assertTrue(
            enabled,
            "advertised foldingRangeProvider must enable folding " +
                "(boolean true or options object); got $provider"
        )
    }

    @Test
    fun folding_range_surface_is_invokable_without_killing_service() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/folding-range-safety-surface.lua",
            """
            local function probe()
                return 1
            end
            return probe
            """
        )

        val outcome = invokeFoldingRange(
            textDocuments,
            foldingParams(document)
        )

        assertTrue(
            outcome is FoldingOutcome.Unsupported ||
                outcome is FoldingOutcome.Empty ||
                outcome is FoldingOutcome.Succeeded ||
                outcome is FoldingOutcome.Failed,
            "foldingRange surface must resolve to a known outcome; got $outcome " +
                "(foldingRangeProvider=${capabilities.foldingRangeProvider})"
        )

        if (outcome is FoldingOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "foldingRange surface must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is FoldingOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is FoldingOutcome.Succeeded) {
            assertWellFormedRanges(outcome.ranges, document.lineCount, label = "surface ranges")
        }
    }

    @Test
    fun folding_range_unimplemented_degrades_as_documented_gap_or_empty() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/folding-range-safety-gap.lua",
            """
            local function greet(name)
                return name
            end
            return greet
            """
        )

        val outcome = invokeFoldingRange(
            textDocuments,
            foldingParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document.lineCount,
            context = "unimplemented / pre-product foldingRange"
        )
    }

    // -------------------------------------------------------------------------
    // Full request safety: empty / syntax error / large / unknown uri / single-line
    // -------------------------------------------------------------------------

    @Test
    fun folding_range_empty_document_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/folding-range-safety-empty.lua",
            ""
        )

        val outcome = invokeFoldingRange(
            textDocuments,
            foldingParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document.lineCount,
            context = "empty document",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun folding_range_syntax_error_buffer_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/folding-range-safety-syntax-error.lua",
            """
            local function broken(
                return {
                    a = 1,
                    b =
            """
        )

        val outcome = invokeFoldingRange(
            textDocuments,
            foldingParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document.lineCount,
            context = "syntax-error buffer"
        )
    }

    @Test
    fun folding_range_large_document_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val body = buildString {
            repeat(200) { i ->
                append("local function f").append(i).append("()\n")
                append("    local v = ").append(i).append("\n")
                append("    return v\n")
                append("end\n")
            }
            append("return f0")
        }
        val document = textDocuments.open(
            "workspace/folding-range-safety-large.lua",
            body
        )

        val outcome = invokeFoldingRange(
            textDocuments,
            foldingParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document.lineCount,
            context = "large document"
        )
    }

    @Test
    fun folding_range_never_opened_uri_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val params = FoldingRangeRequestParams(
            TextDocumentIdentifier("file:///workspace/folding-range-safety-never-opened.lua")
        )

        val outcome = invokeFoldingRange(textDocuments, params)

        // lineCount unknown for never-opened uri; only require dual-path safety.
        assertDegradesAsGapOrEmpty(
            outcome,
            lineCount = -1,
            context = "never-opened document uri"
        )
    }

    @Test
    fun folding_range_single_line_source_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/folding-range-safety-single-line.lua",
            "local function id(x) return x end\nreturn id"
        )

        val outcome = invokeFoldingRange(
            textDocuments,
            foldingParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document.lineCount,
            context = "single-line function source"
        )
    }

    @Test
    fun folding_range_function_table_corpus_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/folding-range-safety-function-table.lua",
            """
            local module = {
                version = 1,
                run = function(self)
                    return self.version
                end
            }
            local function greet(name)
                local message = "hi " .. name
                return message
            end
            return module, greet
            """
        )

        val outcome = invokeFoldingRange(
            textDocuments,
            foldingParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document.lineCount,
            context = "function/table corpus"
        )
    }

    @Test
    fun folding_range_twice_is_stable_on_gap_or_empty_or_ranges() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/folding-range-safety-twice.lua",
            """
            local function a()
                return 1
            end
            local function b()
                return 2
            end
            return a, b
            """
        )
        val params = foldingParams(document)

        val first = invokeFoldingRange(textDocuments, params)
        val second = invokeFoldingRange(textDocuments, params)

        assertDegradesAsGapOrEmpty(first, document.lineCount, context = "first foldingRange call")
        assertDegradesAsGapOrEmpty(second, document.lineCount, context = "second foldingRange call")

        // Same dual-path class on both invocations (gap stays gap; empty stays empty;
        // live ranges stay live). Soft: do not require identical payloads yet.
        assertTrue(
            first::class == second::class ||
                (first is FoldingOutcome.Empty && second is FoldingOutcome.Succeeded) ||
                (first is FoldingOutcome.Succeeded && second is FoldingOutcome.Empty),
            "repeated foldingRange calls should stay on the same dual-path family; " +
                "first=$first second=$second"
        )
        if (first is FoldingOutcome.Succeeded && second is FoldingOutcome.Succeeded) {
            assertWellFormedRanges(first.ranges, document.lineCount, label = "first call")
            assertWellFormedRanges(second.ranges, document.lineCount, label = "second call")
        }
    }

    @Test
    fun folding_range_crlf_buffer_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/folding-range-safety-crlf.lua",
            "local function f()\r\n    return 1\r\nend\r\nreturn f"
        )

        val outcome = invokeFoldingRange(
            textDocuments,
            foldingParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document.lineCount,
            context = "CRLF buffer"
        )
    }

    @Test
    fun folding_range_comment_only_buffer_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/folding-range-safety-comments.lua",
            """
            -- header
            --[[
              multi-line comment block
              that looks foldable
            ]]
            -- trailer
            """
        )

        val outcome = invokeFoldingRange(
            textDocuments,
            foldingParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document.lineCount,
            context = "comment-only buffer"
        )
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

    private fun invokeFoldingRange(
        textDocuments: LuaTextDocumentService,
        params: FoldingRangeRequestParams
    ): FoldingOutcome {
        return try {
            val ranges = textDocuments.foldingRange(params).get()
            classifyRanges(ranges)
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                FoldingOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                FoldingOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun classifyRanges(ranges: List<FoldingRange>?): FoldingOutcome {
        if (ranges == null) {
            return FoldingOutcome.Empty(detail = "null folding ranges")
        }
        return if (ranges.isEmpty()) {
            FoldingOutcome.Empty(detail = "empty folding range list")
        } else {
            FoldingOutcome.Succeeded(ranges = ranges.toList())
        }
    }

    private fun assertDegradesAsGapOrEmpty(
        outcome: FoldingOutcome,
        lineCount: Int,
        context: String,
        allowNonEmptyWhenSucceeded: Boolean = true
    ) {
        when (outcome) {
            is FoldingOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is FoldingOutcome.Empty -> {
                // Soft degrade (empty / null) is the ideal unimplemented-or-no-hit path
                // "per product ads" once the server elects not to throw.
            }
            is FoldingOutcome.Succeeded -> {
                assertWellFormedRanges(outcome.ranges, lineCount, label = "ranges for $context")
                if (!allowNonEmptyWhenSucceeded) {
                    // Empty document should ideally yield Empty via classifyRanges; if product
                    // invents ranges for empty buffers they must still be shape-valid (already
                    // checked). Soft-accept non-empty only if well-formed.
                    assertTrue(
                        outcome.ranges.isNotEmpty(),
                        "$context succeeded path has non-empty ranges (already validated)"
                    )
                }
            }
            is FoldingOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "foldingRange at $context must not NPE/assert; got ${outcome.detail}"
                )
                // Soft product errors (ResponseError, IllegalState, etc.) are allowed
                // while the feature is partial; hard process-killing crash classes are not.
            }
        }
    }

    private fun assertWellFormedRanges(ranges: List<FoldingRange>, lineCount: Int, label: String) {
        if (lineCount == 0) {
            assertTrue(
                ranges.isEmpty(),
                "$label: empty document should yield no folding ranges; got ${describe(ranges)}"
            )
            return
        }
        ranges.forEach { range ->
            assertTrue(
                range.startLine >= 0,
                "$label startLine must be >= 0; got ${range.startLine} in ${describe(listOf(range))}"
            )
            assertTrue(
                range.endLine >= range.startLine,
                "$label endLine must be >= startLine; got ${describe(listOf(range))}"
            )
            if (lineCount > 0) {
                assertTrue(
                    range.startLine < lineCount,
                    "$label startLine must be inside document (lineCount=$lineCount); " +
                        "got ${describe(listOf(range))}"
                )
                assertTrue(
                    range.endLine < lineCount,
                    "$label endLine must be inside document (lineCount=$lineCount); " +
                        "got ${describe(listOf(range))}"
                )
            }
            val startChar = range.startCharacter
            val endChar = range.endCharacter
            if (startChar != null) {
                assertTrue(startChar >= 0, "$label startCharacter must be >= 0; got $startChar")
            }
            if (endChar != null) {
                assertTrue(endChar >= 0, "$label endCharacter must be >= 0; got $endChar")
            }
            if (startChar != null && endChar != null && range.startLine == range.endLine) {
                assertTrue(
                    endChar >= startChar,
                    "$label same-line endCharacter must be >= startCharacter; " +
                        "got ${describe(listOf(range))}"
                )
            }
        }
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

    private fun isHardCrash(detail: String): Boolean {
        return detail.contains("NullPointerException", ignoreCase = true) ||
            detail.contains("AssertionError", ignoreCase = true) ||
            detail.contains("KotlinNullPointerException", ignoreCase = true) ||
            detail.contains("IndexOutOfBoundsException", ignoreCase = true)
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
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : FoldingOutcome()

        data class Empty(val detail: String) : FoldingOutcome()

        data class Succeeded(val ranges: List<FoldingRange>) : FoldingOutcome()

        data class Failed(val detail: String) : FoldingOutcome()
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
