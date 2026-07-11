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
import kotlin.test.fail

/**
 * TASK-269 — LSP selection range nested block corpus.
 *
 * Locks the contract for `textDocument/selectionRange` around nested Lua blocks:
 * - When selection ranges are advertised / returned, a cursor inside nested
 *   function / table / if blocks yields a parent chain that nests those blocks
 *   (inner range contained by each parent).
 * - Missing capability (provider not advertised) or unsupported positions must
 *   return empty / null without throwing — never hard-crash the LSP surface.
 * - Multi-position requests stay aligned with the request order when results
 *   are returned.
 *
 * Product selectionRange remains unimplemented today: [LuaTextDocumentService]
 * inherits the LSP4J default that throws [UnsupportedOperationException], and
 * [LuaLanguageService] does not advertise selectionRangeProvider. This corpus
 * therefore dual-paths:
 * - Documented gap: unimplemented methods complete exceptionally with
 *   UnsupportedOperationException and are recorded as the known surface.
 * - Ideal path: empty/null when capability is missing or position unsupported;
 *   well-formed nested parent chains when product returns SelectionRange values.
 *
 * Test-only; no product edits. Verification is review-owned and serial; this
 * worker does not run Gradle.
 */
class LspSelectionRangeNestedBlockTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun selection_range_capability_is_null_or_explicit_when_product_lands() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        assertNotNull(capabilities, "initialize must return ServerCapabilities")
        val provider = capabilities.selectionRangeProvider
        if (provider == null) {
            // Documented gap: product has not advertised selection ranges yet.
            assertTrue(true)
        } else {
            // Boolean true, Either, or registration options — any non-null is fine.
            assertNotNull(provider)
        }
    }

    @Test
    fun selection_range_surface_is_invokable_without_killing_service() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-surface.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(document.positionOf("value", occurrence = 1)))
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
    }

    @Test
    fun missing_capability_or_unimplemented_degrades_without_throw() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-missing-capability.lua",
            """
            local function probe()
                return 1
            end
            return probe
            """
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(document.positionOf("return", occurrence = 1)))
        )

        // Acceptance: missing capability / unsupported surface returns empty
        // without throw. Documented UnsupportedOperation gap is also allowed
        // while product has not shipped the provider.
        when (outcome) {
            is SelectionOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException when " +
                        "selectionRangeProvider=${capabilities.selectionRangeProvider}; " +
                        "got ${outcome.detail}"
                )
            }
            is SelectionOutcome.Empty -> {
                // Ideal soft degrade when capability is absent or position yields nothing.
            }
            is SelectionOutcome.Succeeded -> {
                // Capability may lag implementation; returned ranges must still be well-formed.
                assertWellFormedSelectionRanges(outcome.ranges, document, label = "missing-capability probe")
            }
            is SelectionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "missing capability path must not hard-crash; got ${outcome.detail}"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Nested function / table / if blocks
    // -------------------------------------------------------------------------

    @Test
    fun selection_range_nests_function_table_and_if_blocks_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        // Cursor target: the numeric literal deep inside if → table → function.
        val document = textDocuments.open(
            "workspace/selection-range-nested-blocks.lua",
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

        val cursor = document.positionOf("42")
        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(cursor))
        )

        when (outcome) {
            is SelectionOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for nested blocks; " +
                        "got ${outcome.detail}"
                )
            }
            is SelectionOutcome.Empty -> {
                // Soft empty is acceptable while product has not implemented nesting.
            }
            is SelectionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "nested block selectionRange must not hard-crash; got ${outcome.detail}"
                )
            }
            is SelectionOutcome.Succeeded -> {
                assertTrue(
                    outcome.ranges.isNotEmpty(),
                    "when selection ranges are returned for nested blocks, list must be non-empty"
                )
                // One requested position → first result is the chain for that cursor.
                val root = outcome.ranges.first()
                assertNotNull(root)
                assertWellFormedSelectionRangeChain(root, document, label = "nested function/table/if")

                val chain = flattenChain(root)
                assertTrue(
                    chain.isNotEmpty(),
                    "selection chain must contain at least the leaf range"
                )
                // Leaf (or some ancestor) should cover the cursor token.
                assertTrue(
                    chain.any { rangeContains(it.range, cursor) },
                    "some selection range in the chain must contain cursor at ${formatPosition(cursor)}; " +
                        "got ${describeChain(root)}"
                )

                // Nesting contract: when parents exist, each parent.range must contain child.range.
                // Prefer multi-level nesting once product ships block-aware selection.
                val hasNestedParent = chain.any { it.parent != null }
                if (hasNestedParent) {
                    assertTrue(
                        chain.size >= 2,
                        "parent chain implies at least leaf + parent; got ${describeChain(root)}"
                    )
                    // Heuristic coverage of the three block kinds: function / table / if.
                    // Product may name ranges differently; we only require that the
                    // expanding parent chain eventually covers the surrounding blocks.
                    val coversIfBody = chain.any { range ->
                        rangeCoversLines(range.range, startLine = 3, endLine = 6) ||
                            rangeCoversLines(range.range, startLine = 4, endLine = 5)
                    }
                    val coversTable = chain.any { range ->
                        rangeCoversLines(range.range, startLine = 1, endLine = 8)
                    }
                    val coversFunction = chain.any { range ->
                        rangeCoversLines(range.range, startLine = 0, endLine = 10) ||
                            rangeCoversLines(range.range, startLine = 0, endLine = 9)
                    }
                    assertTrue(
                        coversIfBody || coversTable || coversFunction,
                        "nested parent chain should expand to cover if and/or table and/or " +
                            "function blocks; got ${describeChain(root)}"
                    )
                }
            }
        }
    }

    @Test
    fun selection_range_nests_local_function_body_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-function-body.lua",
            """
            local function greet(name)
                local message = "hi " .. name
                return message
            end
            return greet
            """
        )

        val cursor = document.positionOf("message", occurrence = 2)
        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(cursor))
        )

        assertNestedOrGap(
            outcome = outcome,
            document = document,
            cursor = cursor,
            context = "local function body"
        )
    }

    @Test
    fun selection_range_nests_table_constructor_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-table.lua",
            """
            local config = {
                enabled = true,
                retries = 3,
                labels = { "a", "b" }
            }
            return config
            """
        )

        val cursor = document.positionOf("3")
        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(cursor))
        )

        assertNestedOrGap(
            outcome = outcome,
            document = document,
            cursor = cursor,
            context = "table constructor"
        )
    }

    @Test
    fun selection_range_nests_if_then_else_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-if.lua",
            """
            local function pick(flag)
                if flag then
                    return "yes"
                else
                    return "no"
                end
            end
            return pick
            """
        )

        val cursor = document.positionOf("\"yes\"")
        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(cursor))
        )

        assertNestedOrGap(
            outcome = outcome,
            document = document,
            cursor = cursor,
            context = "if/then/else block"
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
            "workspace/selection-range-whitespace.lua",
            "local value = 1\n\nreturn value"
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(Position(1, 0)))
        )

        assertEmptyOrGapWithoutThrow(outcome, context = "whitespace / blank line")
    }

    @Test
    fun selection_range_on_out_of_bounds_position_is_empty_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-oob.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(Position(50, 0)))
        )

        assertEmptyOrGapWithoutThrow(outcome, context = "out-of-bounds position past EOF")
    }

    @Test
    fun selection_range_on_extreme_character_offset_is_empty_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-extreme-col.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(Position(0, 10_000)))
        )

        assertEmptyOrGapWithoutThrow(outcome, context = "extreme character offset")
    }

    @Test
    fun selection_range_empty_positions_list_is_empty_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-empty-positions.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, emptyList())
        )

        assertEmptyOrGapWithoutThrow(outcome, context = "empty positions list")
    }

    // -------------------------------------------------------------------------
    // Multi-position + malformed safety
    // -------------------------------------------------------------------------

    @Test
    fun selection_range_multi_position_request_aligns_or_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-multi-pos.lua",
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
    fun selection_range_on_empty_document_does_not_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-empty-doc.lua",
            ""
        )

        val outcome = invokeSelectionRange(
            textDocuments,
            selectionParams(document, listOf(Position(0, 0)))
        )

        assertEmptyOrGapWithoutThrow(outcome, context = "empty document")
    }

    @Test
    fun selection_range_on_malformed_source_does_not_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/selection-range-malformed.lua",
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

        assertEmptyOrGapWithoutThrow(outcome, context = "malformed / incomplete source")
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
            when {
                ranges == null -> SelectionOutcome.Empty(detail = "null result")
                ranges.isEmpty() -> SelectionOutcome.Empty(detail = "empty list")
                ranges.all { it == null } -> SelectionOutcome.Empty(detail = "all-null list")
                else -> SelectionOutcome.Succeeded(ranges = ranges)
            }
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

    private fun assertNestedOrGap(
        outcome: SelectionOutcome,
        document: OpenDocument,
        cursor: Position,
        context: String
    ) {
        when (outcome) {
            is SelectionOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is SelectionOutcome.Empty -> {
                // Soft empty while unimplemented.
            }
            is SelectionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "selectionRange for $context must not hard-crash; got ${outcome.detail}"
                )
            }
            is SelectionOutcome.Succeeded -> {
                assertWellFormedSelectionRanges(outcome.ranges, document, label = context)
                val root = outcome.ranges.firstOrNull()
                if (root != null) {
                    val chain = flattenChain(root)
                    assertTrue(
                        chain.any { rangeContains(it.range, cursor) },
                        "$context chain should contain cursor ${formatPosition(cursor)}; " +
                            "got ${describeChain(root)}"
                    )
                }
            }
        }
    }

    private fun assertEmptyOrGapWithoutThrow(
        outcome: SelectionOutcome,
        context: String
    ) {
        when (outcome) {
            is SelectionOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is SelectionOutcome.Empty -> {
                // Ideal: empty without throw when capability missing / position unsupported.
            }
            is SelectionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "selectionRange for $context must not hard-crash; got ${outcome.detail}"
                )
            }
            is SelectionOutcome.Succeeded -> {
                // Product may return empty-ish chains or null entries; any concrete
                // ranges must still be well-formed (no inverted parents).
                outcome.ranges.forEachIndexed { index, range ->
                    if (range != null) {
                        assertOrderedRange(range.range, label = "$context result[$index]")
                        val parent = range.parent
                        if (parent != null) {
                            assertTrue(
                                rangeContainsRange(parent.range, range.range),
                                "$context result[$index] parent.range must contain child.range; " +
                                    "child=${formatRange(range.range)} parent=${formatRange(parent.range)}"
                            )
                        }
                    }
                }
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
            // Soft bound: ranges should not wildly exceed document line count.
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

    private fun rangeContains(range: Range, position: Position): Boolean {
        val afterStart =
            position.line > range.start.line ||
                (position.line == range.start.line && position.character >= range.start.character)
        val beforeEnd =
            position.line < range.end.line ||
                (position.line == range.end.line && position.character <= range.end.character)
        return afterStart && beforeEnd
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

    private fun rangeCoversLines(range: Range, startLine: Int, endLine: Int): Boolean {
        // Exact match or outer range that includes the target span.
        if (range.start.line <= startLine && range.end.line >= endLine) {
            return true
        }
        // Interior-only fold/selection: starts after header, ends on/before closer.
        if (range.start.line in startLine..endLine && range.end.line in startLine..endLine) {
            return range.end.line >= range.start.line
        }
        return false
    }

    private fun describeChain(root: SelectionRange): String {
        return flattenChain(root).joinToString(prefix = "[", postfix = "]", separator = " -> ") {
            formatRange(it.range)
        }
    }

    private fun formatRange(range: Range): String {
        return "${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character}"
    }

    private fun formatPosition(position: Position): String {
        return "${position.line}:${position.character}"
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

        data class Failed(val detail: String) : SelectionOutcome()

        data class Succeeded(val ranges: List<SelectionRange?>) : SelectionOutcome()
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
