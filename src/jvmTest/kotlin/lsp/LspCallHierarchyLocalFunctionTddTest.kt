package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-272 — LSP call hierarchy for local functions corpus.
 *
 * Locks the safety contract for textDocument/prepareCallHierarchy and the
 * follow-on callHierarchy/incomingCalls + callHierarchy/outgoingCalls requests
 * when the cursor sits on Lua local function names (definitions or call sites):
 * - prepareCallHierarchy / incoming / outgoing degrade safely when unimplemented
 *   (LSP4J default [UnsupportedOperationException]).
 * - Local function positions do not throw once the surface is live: either
 *   null/empty results or well-formed [CallHierarchyItem] / call edges.
 * - Non-function positions, empty buffers, and malformed sources must not hard-crash.
 *
 * TASK-520 product surface: [LuaLanguageService] advertises callHierarchyProvider
 * and [LuaTextDocumentService] implements prepare/incoming/outgoing for same-file
 * local functions (definition + references-based call graph subset). Dual-path
 * assertions remain so pre-product gap (UnsupportedOperationException) and the
 * live path (empty-without-throw / well-formed items) both stay green.
 *
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class LspCallHierarchyLocalFunctionTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun call_hierarchy_capability_or_documented_gap_when_unimplemented() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-capability-probe.lua",
            """
            local function probe()
              return 1
            end
            return probe
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("probe", occurrence = 1))
        )

        when (outcome) {
            is PrepareOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for prepareCallHierarchy; " +
                        "got ${outcome.detail} (callHierarchyProvider=${capabilities.callHierarchyProvider})"
                )
            }
            is PrepareOutcome.Failed -> {
                fail(
                    "prepareCallHierarchy must not fail hard once surface is reachable: ${outcome.detail}"
                )
            }
            is PrepareOutcome.Succeeded -> {
                assertWellFormedItems(outcome.items, document)
            }
        }
    }

    // -------------------------------------------------------------------------
    // prepareCallHierarchy on local function definitions
    // -------------------------------------------------------------------------

    @Test
    fun prepare_call_hierarchy_on_local_function_name_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-local-function.lua",
            """
            local function greet(name)
              return "hi " .. name
            end
            return greet
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("greet", occurrence = 1))
        )

        assertPrepareSafeOrGap(
            outcome,
            document = document,
            context = "prepareCallHierarchy on 'local function greet'"
        )

        if (outcome is PrepareOutcome.Succeeded && outcome.items.isNotEmpty()) {
            val greetItems = outcome.items.filter { it.name.contains("greet") }
            assertTrue(
                greetItems.isNotEmpty() ||
                    outcome.items.any {
                        it.kind == SymbolKind.Function || it.kind == SymbolKind.Method
                    },
                "When items are returned for local function greet, expect Function/Method kind " +
                    "or name containing greet; got ${describeItems(outcome.items)}"
            )
        }
    }

    @Test
    fun prepare_call_hierarchy_on_local_assigned_function_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-local-assigned-function.lua",
            """
            local add = function(a, b)
              return a + b
            end
            return add
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("add", occurrence = 1))
        )

        assertPrepareSafeOrGap(
            outcome,
            document = document,
            context = "prepareCallHierarchy on 'local add = function(...)'"
        )
    }

    @Test
    fun prepare_call_hierarchy_on_nested_local_function_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-nested-local-function.lua",
            """
            local function outer()
              local function inner()
                return 42
              end
              return inner()
            end
            return outer
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("inner", occurrence = 1))
        )

        assertPrepareSafeOrGap(
            outcome,
            document = document,
            context = "prepareCallHierarchy on nested local function 'inner'"
        )
    }

    @Test
    fun prepare_call_hierarchy_on_local_function_call_site_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-local-call-site.lua",
            """
            local function helper()
              return 1
            end

            local function main()
              return helper()
            end

            return main()
            """
        )

        // Second occurrence of helper is the call site inside main.
        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("helper", occurrence = 2))
        )

        assertPrepareSafeOrGap(
            outcome,
            document = document,
            context = "prepareCallHierarchy on local call site helper()"
        )
    }

    // -------------------------------------------------------------------------
    // Non-function positions: empty without throw (or documented gap)
    // -------------------------------------------------------------------------

    @Test
    fun prepare_call_hierarchy_on_local_number_binding_empty_without_throw_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-local-number.lua",
            """
            local count = 1
            return count
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("count", occurrence = 1))
        )

        assertEmptyPrepareOrGap(
            outcome,
            context = "local number binding is not a call hierarchy root"
        )
    }

    @Test
    fun prepare_call_hierarchy_on_whitespace_empty_without_throw_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-whitespace.lua",
            """
            local function work()
              return 1
            end

            return work
            """
        )

        // Blank line between end and return.
        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, Position(3, 0))
        )

        assertEmptyPrepareOrGap(
            outcome,
            context = "whitespace / blank line between statements"
        )
    }

    @Test
    fun prepare_call_hierarchy_on_keyword_empty_without_throw_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-keyword.lua",
            """
            local function work()
              return 1
            end
            return work
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("local"))
        )

        assertEmptyPrepareOrGap(
            outcome,
            context = "keyword 'local'"
        )
    }

    @Test
    fun prepare_call_hierarchy_unknown_free_name_empty_without_throw_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-unknown-free.lua",
            """
            local function known()
              return 1
            end
            return unknownCaller
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("unknownCaller"))
        )

        assertEmptyPrepareOrGap(
            outcome,
            context = "unknown free name 'unknownCaller'"
        )
    }

    // -------------------------------------------------------------------------
    // incoming / outgoing for local functions
    // -------------------------------------------------------------------------

    @Test
    fun call_hierarchy_incoming_for_local_function_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-incoming-local.lua",
            """
            local function target()
              return 1
            end

            local function caller()
              return target()
            end

            return caller()
            """
        )

        val prepare = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("target", occurrence = 1))
        )

        val item = when (prepare) {
            is PrepareOutcome.Unsupported -> {
                assertTrue(
                    prepare.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for prepare; got ${prepare.detail}"
                )
                syntheticItem(document, name = "target", needle = "target", occurrence = 1)
            }
            is PrepareOutcome.Failed -> {
                fail("prepareCallHierarchy must not fail hard for local target: ${prepare.detail}")
            }
            is PrepareOutcome.Succeeded -> {
                prepare.items.firstOrNull()
                    ?: syntheticItem(document, name = "target", needle = "target", occurrence = 1)
            }
        }

        val outcome = invokeIncoming(textDocuments, CallHierarchyIncomingCallsParams(item))
        assertIncomingSafeOrGap(
            outcome,
            document = document,
            context = "callHierarchy/incomingCalls for local function target"
        )
    }

    @Test
    fun call_hierarchy_outgoing_for_local_function_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-outgoing-local.lua",
            """
            local function leaf()
              return 1
            end

            local function root()
              return leaf()
            end

            return root()
            """
        )

        val prepare = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("root", occurrence = 1))
        )

        val item = when (prepare) {
            is PrepareOutcome.Unsupported -> {
                assertTrue(
                    prepare.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for prepare; got ${prepare.detail}"
                )
                syntheticItem(document, name = "root", needle = "root", occurrence = 1)
            }
            is PrepareOutcome.Failed -> {
                fail("prepareCallHierarchy must not fail hard for local root: ${prepare.detail}")
            }
            is PrepareOutcome.Succeeded -> {
                prepare.items.firstOrNull()
                    ?: syntheticItem(document, name = "root", needle = "root", occurrence = 1)
            }
        }

        val outcome = invokeOutgoing(textDocuments, CallHierarchyOutgoingCallsParams(item))
        assertOutgoingSafeOrGap(
            outcome,
            document = document,
            context = "callHierarchy/outgoingCalls for local function root"
        )
    }

    @Test
    fun call_hierarchy_incoming_unknown_item_empty_without_throw_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-incoming-unknown.lua",
            """
            local function known()
              return 1
            end
            return known
            """
        )

        val phantom = CallHierarchyItem(
            "PhantomMissing",
            SymbolKind.Function,
            document.uri,
            Range(Position(0, 0), Position(0, 1)),
            Range(Position(0, 0), Position(0, 1))
        )

        val outcome = invokeIncoming(textDocuments, CallHierarchyIncomingCallsParams(phantom))
        assertEmptyIncomingOrGap(
            outcome,
            context = "incomingCalls for unknown PhantomMissing item"
        )
    }

    @Test
    fun call_hierarchy_outgoing_unknown_item_empty_without_throw_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-outgoing-unknown.lua",
            """
            local function known()
              return 1
            end
            return known
            """
        )

        val phantom = CallHierarchyItem(
            "PhantomMissing",
            SymbolKind.Function,
            document.uri,
            Range(Position(0, 0), Position(0, 1)),
            Range(Position(0, 0), Position(0, 1))
        )

        val outcome = invokeOutgoing(textDocuments, CallHierarchyOutgoingCallsParams(phantom))
        assertEmptyOutgoingOrGap(
            outcome,
            context = "outgoingCalls for unknown PhantomMissing item"
        )
    }

    // -------------------------------------------------------------------------
    // Local function positions do not throw (core acceptance)
    // -------------------------------------------------------------------------

    @Test
    fun local_function_definition_position_does_not_throw() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-no-throw-def.lua",
            """
            local function alpha()
              return 1
            end

            local beta = function()
              return 2
            end

            return alpha() + beta()
            """
        )

        val positions = listOf(
            document.positionOf("alpha", occurrence = 1),
            document.positionOf("beta", occurrence = 1),
            document.positionOf("alpha", occurrence = 2),
            document.positionOf("beta", occurrence = 2)
        )

        for (position in positions) {
            val outcome = invokePrepare(textDocuments, prepareParams(document, position))
            when (outcome) {
                is PrepareOutcome.Unsupported -> {
                    assertTrue(
                        outcome.isUnsupportedOperation,
                        "Local function position ${formatPosition(position)} documented gap " +
                            "expects UnsupportedOperationException; got ${outcome.detail}"
                    )
                }
                is PrepareOutcome.Failed -> {
                    fail(
                        "Local function position ${formatPosition(position)} must not throw: ${outcome.detail}"
                    )
                }
                is PrepareOutcome.Succeeded -> {
                    assertWellFormedItems(outcome.items, document)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Malformed / empty buffer safety
    // -------------------------------------------------------------------------

    @Test
    fun prepare_call_hierarchy_empty_document_does_not_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-empty.lua",
            ""
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, Position(0, 0))
        )

        assertEmptyPrepareOrGap(
            outcome,
            context = "empty document"
        )
    }

    @Test
    fun prepare_call_hierarchy_malformed_local_function_does_not_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        // Incomplete local function forms — product must not throw from the LSP surface.
        val document = textDocuments.open(
            "workspace/call-hierarchy-malformed-local.lua",
            """
            local function
            local function (
            local = function
            return 1
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, Position(0, 6))
        )

        assertEmptyPrepareOrGap(
            outcome,
            context = "malformed local function fragments"
        )
    }

    @Test
    fun prepare_call_hierarchy_out_of_bounds_position_does_not_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-oob.lua",
            """
            local function tiny()
              return 0
            end
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, Position(99, 99))
        )

        assertEmptyPrepareOrGap(
            outcome,
            context = "out-of-bounds position on local function file"
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

    private fun prepareParams(document: OpenDocument, position: Position): CallHierarchyPrepareParams {
        return CallHierarchyPrepareParams(TextDocumentIdentifier(document.uri), position)
    }

    private fun syntheticItem(
        document: OpenDocument,
        name: String,
        needle: String,
        occurrence: Int
    ): CallHierarchyItem {
        val start = document.positionOf(needle, occurrence)
        val end = Position(start.line, start.character + needle.length)
        val range = Range(start, end)
        return CallHierarchyItem(name, SymbolKind.Function, document.uri, range, range)
    }

    private fun invokePrepare(
        textDocuments: LuaTextDocumentService,
        params: CallHierarchyPrepareParams
    ): PrepareOutcome {
        return try {
            val items = textDocuments.prepareCallHierarchy(params).get()
            PrepareOutcome.Succeeded(items.orEmpty())
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                PrepareOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                PrepareOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun invokeIncoming(
        textDocuments: LuaTextDocumentService,
        params: CallHierarchyIncomingCallsParams
    ): IncomingOutcome {
        return try {
            val calls = textDocuments.callHierarchyIncomingCalls(params).get()
            IncomingOutcome.Succeeded(calls.orEmpty())
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                IncomingOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                IncomingOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun invokeOutgoing(
        textDocuments: LuaTextDocumentService,
        params: CallHierarchyOutgoingCallsParams
    ): OutgoingOutcome {
        return try {
            val calls = textDocuments.callHierarchyOutgoingCalls(params).get()
            OutgoingOutcome.Succeeded(calls.orEmpty())
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                OutgoingOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                OutgoingOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun assertPrepareSafeOrGap(
        outcome: PrepareOutcome,
        document: OpenDocument,
        context: String
    ) {
        when (outcome) {
            is PrepareOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is PrepareOutcome.Failed -> {
                fail("$context must not throw a hard failure: ${outcome.detail}")
            }
            is PrepareOutcome.Succeeded -> {
                assertWellFormedItems(outcome.items, document)
            }
        }
    }

    private fun assertEmptyPrepareOrGap(outcome: PrepareOutcome, context: String) {
        when (outcome) {
            is PrepareOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is PrepareOutcome.Failed -> {
                fail("$context must not throw; non-function positions should be empty without throw: ${outcome.detail}")
            }
            is PrepareOutcome.Succeeded -> {
                assertTrue(
                    outcome.items.isEmpty(),
                    "Expected empty prepareCallHierarchy for $context; got ${describeItems(outcome.items)}"
                )
            }
        }
    }

    private fun assertIncomingSafeOrGap(
        outcome: IncomingOutcome,
        document: OpenDocument,
        context: String
    ) {
        when (outcome) {
            is IncomingOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is IncomingOutcome.Failed -> {
                fail("$context must not throw a hard failure: ${outcome.detail}")
            }
            is IncomingOutcome.Succeeded -> {
                assertWellFormedIncoming(outcome.calls, document)
            }
        }
    }

    private fun assertOutgoingSafeOrGap(
        outcome: OutgoingOutcome,
        document: OpenDocument,
        context: String
    ) {
        when (outcome) {
            is OutgoingOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is OutgoingOutcome.Failed -> {
                fail("$context must not throw a hard failure: ${outcome.detail}")
            }
            is OutgoingOutcome.Succeeded -> {
                assertWellFormedOutgoing(outcome.calls, document)
            }
        }
    }

    private fun assertEmptyIncomingOrGap(outcome: IncomingOutcome, context: String) {
        when (outcome) {
            is IncomingOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is IncomingOutcome.Failed -> {
                fail("$context must not throw; unknown items should yield empty without throw: ${outcome.detail}")
            }
            is IncomingOutcome.Succeeded -> {
                assertTrue(
                    outcome.calls.isEmpty(),
                    "Expected empty incomingCalls for $context; got ${describeIncoming(outcome.calls)}"
                )
            }
        }
    }

    private fun assertEmptyOutgoingOrGap(outcome: OutgoingOutcome, context: String) {
        when (outcome) {
            is OutgoingOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is OutgoingOutcome.Failed -> {
                fail("$context must not throw; unknown items should yield empty without throw: ${outcome.detail}")
            }
            is OutgoingOutcome.Succeeded -> {
                assertTrue(
                    outcome.calls.isEmpty(),
                    "Expected empty outgoingCalls for $context; got ${describeOutgoing(outcome.calls)}"
                )
            }
        }
    }

    private fun assertWellFormedItems(items: List<CallHierarchyItem>, document: OpenDocument) {
        items.forEach { item ->
            assertNotNull(item.name, "CallHierarchyItem.name must be non-null")
            assertTrue(item.name.isNotBlank(), "CallHierarchyItem.name must be non-blank: ${describeItems(listOf(item))}")
            assertNotNull(item.kind, "CallHierarchyItem.kind must be non-null for ${item.name}")
            assertTrue(
                item.uri.isNullOrBlank() || item.uri == document.uri || item.uri.startsWith("file:"),
                "CallHierarchyItem.uri should be empty or a file URI; got '${item.uri}' for ${item.name}"
            )
            val range = item.range
            val selection = item.selectionRange
            assertNotNull(range, "CallHierarchyItem.range must be non-null for ${item.name}")
            assertNotNull(selection, "CallHierarchyItem.selectionRange must be non-null for ${item.name}")
            assertOrderedRange(range, label = "range of ${item.name}")
            assertOrderedRange(selection, label = "selectionRange of ${item.name}")
            assertTrue(
                containsRange(range, selection),
                "selectionRange must be inside range for ${item.name}; " +
                    "range=${formatRange(range)} selection=${formatRange(selection)}"
            )
        }
    }

    private fun assertWellFormedIncoming(calls: List<CallHierarchyIncomingCall>, document: OpenDocument) {
        calls.forEach { call ->
            assertNotNull(call.from, "CallHierarchyIncomingCall.from must be non-null")
            assertWellFormedItems(listOf(call.from), document)
            assertNotNull(call.fromRanges, "CallHierarchyIncomingCall.fromRanges must be non-null")
            call.fromRanges.forEachIndexed { index, range ->
                assertOrderedRange(range, label = "incoming fromRanges[$index] of ${call.from.name}")
            }
        }
    }

    private fun assertWellFormedOutgoing(calls: List<CallHierarchyOutgoingCall>, document: OpenDocument) {
        calls.forEach { call ->
            assertNotNull(call.to, "CallHierarchyOutgoingCall.to must be non-null")
            assertWellFormedItems(listOf(call.to), document)
            assertNotNull(call.fromRanges, "CallHierarchyOutgoingCall.fromRanges must be non-null")
            call.fromRanges.forEachIndexed { index, range ->
                assertOrderedRange(range, label = "outgoing fromRanges[$index] of ${call.to.name}")
            }
        }
    }

    private fun assertOrderedRange(range: Range, label: String) {
        assertTrue(
            range.end.line > range.start.line ||
                (range.end.line == range.start.line &&
                    range.end.character >= range.start.character),
            "$label must be ordered; got ${formatRange(range)}"
        )
        assertTrue(range.start.line >= 0, "$label start.line must be >= 0")
        assertTrue(range.start.character >= 0, "$label start.character must be >= 0")
        assertTrue(range.end.character >= 0, "$label end.character must be >= 0")
    }

    private fun containsRange(outer: Range, inner: Range): Boolean {
        val startsOk =
            inner.start.line > outer.start.line ||
                (inner.start.line == outer.start.line &&
                    inner.start.character >= outer.start.character)
        val endsOk =
            inner.end.line < outer.end.line ||
                (inner.end.line == outer.end.line &&
                    inner.end.character <= outer.end.character)
        return startsOk && endsOk
    }

    private fun describeItems(items: List<CallHierarchyItem>): String {
        return items.joinToString(prefix = "[", postfix = "]") { item ->
            val kind = item.kind?.name ?: "?"
            "${item.name}/$kind@${formatRange(item.range)}"
        }
    }

    private fun describeIncoming(calls: List<CallHierarchyIncomingCall>): String {
        return calls.joinToString(prefix = "[", postfix = "]") { call ->
            "from=${call.from?.name}@${formatRange(call.from?.range)} ranges=${call.fromRanges?.size ?: 0}"
        }
    }

    private fun describeOutgoing(calls: List<CallHierarchyOutgoingCall>): String {
        return calls.joinToString(prefix = "[", postfix = "]") { call ->
            "to=${call.to?.name}@${formatRange(call.to?.range)} ranges=${call.fromRanges?.size ?: 0}"
        }
    }

    private fun formatRange(range: Range?): String {
        if (range == null) {
            return "null"
        }
        return "${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character}"
    }

    private fun formatPosition(position: Position): String {
        return "${position.line}:${position.character}"
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

    private sealed class PrepareOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : PrepareOutcome()

        data class Failed(val detail: String) : PrepareOutcome()

        data class Succeeded(val items: List<CallHierarchyItem>) : PrepareOutcome()
    }

    private sealed class IncomingOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : IncomingOutcome()

        data class Failed(val detail: String) : IncomingOutcome()

        data class Succeeded(val calls: List<CallHierarchyIncomingCall>) : IncomingOutcome()
    }

    private sealed class OutgoingOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : OutgoingOutcome()

        data class Failed(val detail: String) : OutgoingOutcome()

        data class Succeeded(val calls: List<CallHierarchyOutgoingCall>) : OutgoingOutcome()
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
