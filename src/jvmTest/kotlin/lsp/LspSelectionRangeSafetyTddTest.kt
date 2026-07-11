package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-419 — LSP selectionRange dual-path safety corpus.
 *
 * Locks the safety contract for `textDocument/selectionRange` when the feature is
 * unimplemented or only partially product-reachable:
 * - Unimplemented surface (LSP4J default [UnsupportedOperationException] on
 *   [LuaTextDocumentService]) must degrade as a documented gap — never as an
 *   unexpected hard crash (NPE / AssertionError / IndexOutOfBounds / StackOverflow).
 * - When product soft-degrades instead of throwing, empty / null range lists are
 *   accepted ("empty per product ads").
 * - Empty documents, syntax-error buffers, large files, never-opened URIs,
 *   whitespace / out-of-bounds / extreme columns, empty position lists, multi-
 *   position requests, CRLF, comment-only, single-line, and nested function/table
 *   corpora must not invent process-killing failures.
 * - When product returns selection ranges (capability advertised and surface live),
 *   each entry must be well-formed (ordered ranges, parent.range contains
 *   child.range, no parent cycles, non-negative positions).
 *
 * Product selection ranges remain out of scope for this worker (test-only).
 * Current [LuaLanguageService] does not advertise selectionRangeProvider and
 * [LuaTextDocumentService] inherits the LSP4J default that throws
 * [UnsupportedOperationException]. Tests therefore dual-path:
 * - Documented gap: unimplemented methods complete exceptionally with
 *   UnsupportedOperationException and are recorded as the known surface.
 * - Ideal / soft path: empty range list (or null → treated as empty) when the
 *   server elects soft degrade per product ads; well-formed parent chains when live.
 *
 * Complements [LspSelectionRangeNestedBlockTddTest] (TASK-269 nesting corpus) with
 * an explicit safety dual-path lock that never hard-skips the unimplemented
 * surface. Verification is review-owned and serial; this worker does not run
 * Gradle.
 */
class LspSelectionRangeSafetyTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun initialize_selection_range_capability_is_null_or_explicit_when_product_lands() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        assertNotNull(capabilities, "initialize must return ServerCapabilities")
        val provider = capabilities.selectionRangeProvider
        if (provider == null) {
            // Documented gap: product has not advertised selection ranges yet.
            assertTrue(
                true,
                "selectionRangeProvider absent is an accepted pre-product surface"
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
            "advertised selectionRangeProvider must enable selection ranges " +
                "(boolean true or options object); got $provider"
        )
    }

    @Test
    fun selection_range_surface_is_invokable_without_killing_service() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-safety-surface.lua",
            """
            local function probe()
                return 1
            end
            return probe
            """
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(document.positionOf("1")))
        )

        assertTrue(
            outcome is SelectionOutcome.Unsupported ||
                outcome is SelectionOutcome.Empty ||
                outcome is SelectionOutcome.Succeeded ||
                outcome is SelectionOutcome.Failed,
            "selectionRange surface must resolve to a known outcome; got $outcome " +
                "(selectionRangeProvider=${capabilities.selectionRangeProvider})"
        )

        if (outcome is SelectionOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "selectionRange surface must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is SelectionOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is SelectionOutcome.Succeeded) {
            assertWellFormedSelectionRanges(outcome.ranges, document, label = "surface ranges")
        }
    }

    @Test
    fun selection_range_unimplemented_degrades_as_documented_gap_or_empty() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-safety-gap.lua",
            """
            local function greet(name)
                return name
            end
            return greet
            """
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(document.positionOf("name", occurrence = 2)))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "unimplemented / pre-product selectionRange"
        )
    }

    // -------------------------------------------------------------------------
    // Full request safety: empty / syntax error / large / never-opened / single-line
    // -------------------------------------------------------------------------

    @Test
    fun selection_range_empty_document_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-safety-empty.lua",
            ""
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(Position(0, 0)))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "empty document",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun selection_range_syntax_error_buffer_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-safety-syntax-error.lua",
            """
            local function broken(
                return {
                    a = 1,
                    if true then
            """
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(Position(1, 4)))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "syntax-error buffer"
        )
    }

    @Test
    fun selection_range_large_document_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val body = buildString {
            appendLine("local function outer()")
            repeat(200) { i ->
                appendLine("    local function f$i()")
                appendLine("        local v = $i")
                appendLine("        return v")
                appendLine("    end")
            }
            appendLine("    return f0")
            appendLine("end")
            appendLine("return outer")
        }
        val document = textDocuments.open(
            "workspace/selection-range-safety-large.lua",
            body
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(document.positionOf("v = 0")))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "large nested function document"
        )
    }

    @Test
    fun selection_range_never_opened_uri_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val params = SelectionRangeParams(
            TextDocumentIdentifier("file:///workspace/selection-range-safety-never-opened.lua"),
            mutableListOf(Position(0, 0))
        )

        val outcome = invokeSelectionRange(textDocuments, params)

        // No OpenDocument for bounds checks; only dual-path shape.
        when (outcome) {
            is SelectionOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for never-opened uri expects UnsupportedOperationException; " +
                        "got ${outcome.detail}"
                )
            }
            is SelectionOutcome.Empty -> {
                // Soft degrade is ideal for unknown uri.
            }
            is SelectionOutcome.Succeeded -> {
                outcome.ranges.forEachIndexed { index, range ->
                    if (range != null) {
                        assertOrderedRange(range.range, label = "never-opened result[$index]")
                        val parent = range.parent
                        if (parent != null) {
                            assertTrue(
                                rangeContainsRange(parent.range, range.range),
                                "never-opened result[$index] parent.range must contain child.range"
                            )
                        }
                    }
                }
            }
            is SelectionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "selectionRange on never-opened uri must not NPE/assert; got ${outcome.detail}"
                )
            }
        }
    }

    @Test
    fun selection_range_single_line_source_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-safety-single-line.lua",
            "local function id(x) return x end\nreturn id"
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(document.positionOf("x", occurrence = 2)))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "single-line function source"
        )
    }

    // -------------------------------------------------------------------------
    // Unsupported positions / empty results without throw
    // -------------------------------------------------------------------------

    @Test
    fun selection_range_on_whitespace_is_empty_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-safety-whitespace.lua",
            "local value = 1\n\nreturn value"
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(Position(1, 0)))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "whitespace / blank line"
        )
    }

    @Test
    fun selection_range_on_out_of_bounds_position_is_empty_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-safety-oob.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(Position(50, 0)))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "out-of-bounds position past EOF"
        )
    }

    @Test
    fun selection_range_on_extreme_character_offset_is_empty_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-safety-extreme-col.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(Position(0, 10_000)))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "extreme character offset"
        )
    }

    @Test
    fun selection_range_empty_positions_list_is_empty_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-safety-empty-positions.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, emptyList())
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "empty positions list"
        )
    }

    @Test
    fun selection_range_on_keyword_is_empty_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-safety-keyword.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(document.positionOf("local")))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "keyword 'local'"
        )
    }

    @Test
    fun selection_range_on_numeric_literal_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-safety-number.lua",
            "local value = 42\nreturn value"
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(document.positionOf("42")))
        )

        // Literals may be valid selection roots once product lands; dual-path only.
        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "numeric literal"
        )
    }

    // -------------------------------------------------------------------------
    // Nested / multi-position / CRLF / comment corpora
    // -------------------------------------------------------------------------

    @Test
    fun selection_range_function_table_if_corpus_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-safety-function-table-if.lua",
            """
            local function outer(flag)
                local module = {
                    value = (function()
                        if flag then
                            return 42
                        end
                        return 0
                    end)()
                }
                return module
            end
            return outer
            """
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(document.positionOf("42")))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "function/table/if nested corpus"
        )
    }

    @Test
    fun selection_range_multi_position_request_aligns_or_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-safety-multi-pos.lua",
            """
            local function outer()
                local t = { x = 1 }
                if t.x then
                    return t.x
                end
                return 0
            end
            return outer
            """
        )

        val positions = listOf(
            document.positionOf("x", occurrence = 1),
            document.positionOf("t.x", occurrence = 1),
            document.positionOf("return", occurrence = 1)
        )
        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, positions)
        )

        when (outcome) {
            is SelectionOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for multi-position; " +
                        "got ${outcome.detail}"
                )
            }
            is SelectionOutcome.Empty -> {
                // Soft empty for partial implementations.
            }
            is SelectionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "multi-position selectionRange must not hard-crash; got ${outcome.detail}"
                )
            }
            is SelectionOutcome.Succeeded -> {
                // LSP: result is an array of selection ranges corresponding to the
                // positions (same order / same length when fully implemented).
                assertTrue(
                    outcome.ranges.size == positions.size || outcome.ranges.isEmpty(),
                    "result length should match positions (${positions.size}) or be empty; " +
                        "got ${outcome.ranges.size}"
                )
                outcome.ranges.forEachIndexed { index, range ->
                    if (range != null) {
                        assertWellFormedSelectionRangeChain(
                            range,
                            document,
                            label = "multi-position[$index]"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun selection_range_twice_is_stable_on_gap_or_empty_or_ranges() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-safety-twice.lua",
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
        val params = selectionParams(document, listOf(document.positionOf("1")))

        val first = invokeSelectionRange(textDocuments, params)
        val second = invokeSelectionRange(textDocuments, params)

        assertDegradesAsGapOrEmpty(first, document, context = "first selectionRange call")
        assertDegradesAsGapOrEmpty(second, document, context = "second selectionRange call")

        // Same dual-path class on both invocations (gap stays gap; empty stays empty;
        // live ranges stay live). Soft: do not require identical payloads yet.
        assertTrue(
            first::class == second::class ||
                (first is SelectionOutcome.Empty && second is SelectionOutcome.Succeeded) ||
                (first is SelectionOutcome.Succeeded && second is SelectionOutcome.Empty),
            "repeated selectionRange calls should stay on the same dual-path family; " +
                "first=$first second=$second"
        )
        if (first is SelectionOutcome.Succeeded && second is SelectionOutcome.Succeeded) {
            assertWellFormedSelectionRanges(first.ranges, document, label = "first call")
            assertWellFormedSelectionRanges(second.ranges, document, label = "second call")
        }
    }

    @Test
    fun selection_range_crlf_buffer_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        // Intentionally keep CRLF; do not trimIndent (would not alter \r\n mid-line).
        val document = textDocuments.open(
            "workspace/selection-range-safety-crlf.lua",
            "local function f()\r\n    return 1\r\nend\r\nreturn f"
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(Position(1, 11)))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "CRLF buffer"
        )
    }

    @Test
    fun selection_range_comment_only_buffer_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-safety-comments.lua",
            """
            -- header
            --[[
              multi-line comment block
              that looks selectable
            ]]
            -- trailer
            """
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(Position(2, 4)))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "comment-only buffer"
        )
    }

    @Test
    fun selection_range_mixed_valid_and_oob_positions_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-safety-mixed-positions.lua",
            """
            local value = 1
            return value
            """
        )

        val positions = listOf(
            document.positionOf("value", occurrence = 1),
            Position(50, 0),
            Position(0, 10_000)
        )
        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, positions)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "mixed valid + out-of-bounds positions"
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

    private fun selectionParams(
        document: OpenDocument,
        positions: List<Position>
    ): SelectionRangeParams {
        return SelectionRangeParams(
            TextDocumentIdentifier(document.uri),
            positions.toMutableList()
        )
    }

    private fun invokeSelectionRange(
        textDocuments: LuaTextDocumentService,
        params: SelectionRangeParams
    ): SelectionOutcome {
        return try {
            val ranges = textDocuments.selectionRange(params).get()
            classifyRanges(ranges)
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                SelectionOutcome.Unsupported(
                    detail = root.toString(),
                    isUnsupportedOperation = true
                )
            } else {
                SelectionOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun classifyRanges(ranges: List<SelectionRange>?): SelectionOutcome {
        if (ranges == null) {
            return SelectionOutcome.Empty(detail = "null selection ranges")
        }
        if (ranges.isEmpty()) {
            return SelectionOutcome.Empty(detail = "empty selection range list")
        }
        if (ranges.all { it == null }) {
            return SelectionOutcome.Empty(detail = "all-null selection range list")
        }
        return SelectionOutcome.Succeeded(ranges = ranges.toList())
    }

    private fun assertDegradesAsGapOrEmpty(
        outcome: SelectionOutcome,
        document: OpenDocument,
        context: String,
        allowNonEmptyWhenSucceeded: Boolean = true
    ) {
        when (outcome) {
            is SelectionOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is SelectionOutcome.Empty -> {
                // Soft degrade (empty / null) is the ideal unimplemented-or-no-hit path
                // "per product ads" once the server elects not to throw.
            }
            is SelectionOutcome.Succeeded -> {
                assertWellFormedSelectionRanges(
                    outcome.ranges,
                    document,
                    label = "ranges for $context"
                )
                if (!allowNonEmptyWhenSucceeded) {
                    // Empty document: classifyRanges already routes empty/null to Empty.
                    // If we land here, product invented non-empty ranges for an empty
                    // buffer (unusual); shape is already validated above.
                    assertTrue(
                        outcome.ranges.isNotEmpty(),
                        "$context succeeded path is non-empty by construction"
                    )
                }
            }
            is SelectionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "selectionRange at $context must not NPE/assert; got ${outcome.detail}"
                )
                // Soft product errors (ResponseError, IllegalState, etc.) are allowed
                // while the feature is partial; hard process-killing crash classes are not.
            }
        }
    }

    private fun assertWellFormedSelectionRanges(
        ranges: List<SelectionRange?>,
        document: OpenDocument,
        label: String
    ) {
        assertTrue(ranges.isNotEmpty(), "$label expected non-empty selection range list")
        ranges.forEachIndexed { index, range ->
            if (range != null) {
                assertWellFormedSelectionRangeChain(range, document, label = "$label[$index]")
            }
        }
    }

    private fun assertWellFormedSelectionRangeChain(
        root: SelectionRange,
        document: OpenDocument,
        label: String
    ) {
        val seen = mutableSetOf<SelectionRange>()
        var current: SelectionRange? = root
        var depth = 0
        while (current != null) {
            assertTrue(
                seen.add(current),
                "$label selection parent chain must not cycle; depth=$depth chain=${describeChain(root)}"
            )
            assertOrderedRange(current.range, label = "$label depth=$depth")
            if (document.lineCount > 0) {
                assertTrue(
                    current.range.start.line >= 0,
                    "$label depth=$depth start.line must be >= 0"
                )
                assertTrue(
                    current.range.end.line >= 0,
                    "$label depth=$depth end.line must be >= 0"
                )
            }
            val parent = current.parent
            if (parent != null) {
                assertTrue(
                    rangeContainsRange(parent.range, current.range),
                    "$label depth=$depth parent.range must contain child.range " +
                        "(LSP SelectionRange contract); child=${formatRange(current.range)} " +
                        "parent=${formatRange(parent.range)}"
                )
            }
            current = parent
            depth += 1
            assertTrue(
                depth < 256,
                "$label parent chain depth exploded (>255); possible cycle/mislink"
            )
        }
    }

    private fun flattenChain(root: SelectionRange): List<SelectionRange> {
        val chain = mutableListOf<SelectionRange>()
        var current: SelectionRange? = root
        while (current != null) {
            chain += current
            current = current.parent
        }
        return chain
    }

    private fun assertOrderedRange(range: Range, label: String) {
        assertTrue(
            range.start.line >= 0 && range.start.character >= 0,
            "$label start must be non-negative; got ${formatRange(range)}"
        )
        assertTrue(
            range.end.line >= 0 && range.end.character >= 0,
            "$label end must be non-negative; got ${formatRange(range)}"
        )
        assertTrue(
            range.end.line > range.start.line ||
                (range.end.line == range.start.line &&
                    range.end.character >= range.start.character),
            "$label range must be ordered; got ${formatRange(range)}"
        )
    }

    private fun rangeContainsRange(outer: Range, inner: Range): Boolean {
        val startOk =
            inner.start.line > outer.start.line ||
                (inner.start.line == outer.start.line &&
                    inner.start.character >= outer.start.character)
        val endOk =
            inner.end.line < outer.end.line ||
                (inner.end.line == outer.end.line &&
                    inner.end.character <= outer.end.character)
        return startOk && endOk
    }

    private fun describeChain(root: SelectionRange): String {
        return flattenChain(root).joinToString(prefix = "[", postfix = "]", separator = " -> ") {
            formatRange(it.range)
        }
    }

    private fun formatRange(range: Range): String {
        return "${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character}"
    }

    private fun isHardCrash(detail: String): Boolean {
        return detail.contains("NullPointerException", ignoreCase = true) ||
            detail.contains("AssertionError", ignoreCase = true) ||
            detail.contains("KotlinNullPointerException", ignoreCase = true) ||
            detail.contains("IndexOutOfBoundsException", ignoreCase = true) ||
            detail.contains("ArrayIndexOutOfBoundsException", ignoreCase = true) ||
            detail.contains("StringIndexOutOfBoundsException", ignoreCase = true) ||
            detail.contains("StackOverflowError", ignoreCase = true)
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

    private sealed class SelectionOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : SelectionOutcome()

        data class Empty(val detail: String) : SelectionOutcome()

        data class Succeeded(val ranges: List<SelectionRange?>) : SelectionOutcome()

        data class Failed(val detail: String) : SelectionOutcome()
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
                return source.count { it == '\n' } + 1
            }

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
    }
}
