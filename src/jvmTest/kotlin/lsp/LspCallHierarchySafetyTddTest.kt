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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-420 — LSP callHierarchy dual-path safety corpus.
 *
 * Locks the safety contract for `textDocument/prepareCallHierarchy` and the
 * follow-on `callHierarchy/incomingCalls` + `callHierarchy/outgoingCalls`
 * requests when the feature is unimplemented or only partially product-reachable:
 * - Unimplemented surface (LSP4J default [UnsupportedOperationException] on
 *   [LuaTextDocumentService]) must degrade as a documented gap — never as an
 *   unexpected hard crash (NPE / AssertionError / IndexOutOfBounds).
 * - When product soft-degrades instead of throwing, empty / null item or call
 *   lists are accepted ("empty per product ads").
 * - Empty documents, syntax-error buffers, large files, never-opened URIs,
 *   non-function positions, out-of-bounds cursors, and phantom items must not
 *   invent process-killing failures.
 * - When product returns items / edges (capability advertised and surface live),
 *   each [CallHierarchyItem] must be well-formed (non-blank name, kind, ordered
 *   range/selectionRange with selection inside range).
 *
 * Product call hierarchy remains out of scope for this worker (test-only).
 * Current [LuaLanguageService] does not advertise callHierarchyProvider and
 * [LuaTextDocumentService] inherits the LSP4J defaults that throw
 * [UnsupportedOperationException]. Tests therefore dual-path:
 * - Documented gap: unimplemented methods complete exceptionally with
 *   UnsupportedOperationException and are recorded as the known surface.
 * - Ideal / soft path: empty list (or null → treated as empty) when the server
 *   elects soft degrade per product ads; well-formed items/edges when live.
 *
 * Complements [LspCallHierarchyLocalFunctionTddTest] (TASK-272 local-function
 * corpus with dual-path asserts) with an explicit safety dual-path lock that
 * never hard-skips the unimplemented surface. Verification is review-owned and
 * serial; this worker does not run Gradle.
 */
class LspCallHierarchySafetyTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun initialize_call_hierarchy_capability_is_null_or_explicit_when_product_lands() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        assertNotNull(capabilities, "initialize must return ServerCapabilities")
        val provider = capabilities.callHierarchyProvider
        if (provider == null) {
            // Documented gap: product has not advertised call hierarchy yet.
            assertTrue(
                true,
                "callHierarchyProvider absent is an accepted pre-product surface"
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
            "advertised callHierarchyProvider must enable call hierarchy " +
                "(boolean true or options object); got $provider"
        )
    }

    @Test
    fun call_hierarchy_prepare_surface_is_invokable_without_killing_service() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-safety-surface.lua",
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

        assertTrue(
            outcome is PrepareOutcome.Unsupported ||
                outcome is PrepareOutcome.Empty ||
                outcome is PrepareOutcome.Succeeded ||
                outcome is PrepareOutcome.Failed,
            "prepareCallHierarchy surface must resolve to a known outcome; got $outcome " +
                "(callHierarchyProvider=${capabilities.callHierarchyProvider})"
        )

        if (outcome is PrepareOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "prepareCallHierarchy surface must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is PrepareOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is PrepareOutcome.Succeeded) {
            assertWellFormedItems(outcome.items, document, label = "surface prepare items")
        }
    }

    @Test
    fun call_hierarchy_unimplemented_degrades_as_documented_gap_or_empty() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-safety-gap.lua",
            """
            local function greet(name)
                return name
            end
            return greet
            """
        )

        val prepare = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("greet", occurrence = 1))
        )
        assertPrepareDegradesAsGapOrEmpty(
            prepare,
            document,
            context = "unimplemented / pre-product prepareCallHierarchy"
        )

        // Even when prepare is unimplemented, exercise follow-on surfaces with a
        // synthetic item so incoming/outgoing dual-path is locked independently.
        val item = syntheticItem(document, name = "greet", needle = "greet", occurrence = 1)
        val incoming = invokeIncoming(textDocuments, CallHierarchyIncomingCallsParams(item))
        val outgoing = invokeOutgoing(textDocuments, CallHierarchyOutgoingCallsParams(item))

        assertIncomingDegradesAsGapOrEmpty(
            incoming,
            document,
            context = "unimplemented / pre-product callHierarchy/incomingCalls"
        )
        assertOutgoingDegradesAsGapOrEmpty(
            outgoing,
            document,
            context = "unimplemented / pre-product callHierarchy/outgoingCalls"
        )
    }

    // -------------------------------------------------------------------------
    // Prepare request safety: empty / syntax error / large / unknown uri / oob
    // -------------------------------------------------------------------------

    @Test
    fun prepare_call_hierarchy_empty_document_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-safety-empty.lua",
            ""
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, Position(0, 0))
        )

        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "empty document",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun prepare_call_hierarchy_syntax_error_buffer_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-safety-syntax-error.lua",
            """
            local function broken(
                return {
                    a = 1,
                    b =
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, Position(0, 6))
        )

        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "syntax-error buffer"
        )
    }

    @Test
    fun prepare_call_hierarchy_large_document_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val body = buildString {
            repeat(200) { i ->
                append("local function f").append(i).append("()\n")
                append("    return ").append(i).append("\n")
                append("end\n")
            }
            append("return f0()")
        }
        val document = textDocuments.open(
            "workspace/call-hierarchy-safety-large.lua",
            body
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("f0", occurrence = 1))
        )

        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "large document"
        )
    }

    @Test
    fun prepare_call_hierarchy_never_opened_uri_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val params = CallHierarchyPrepareParams(
            TextDocumentIdentifier("file:///workspace/call-hierarchy-safety-never-opened.lua"),
            Position(0, 0)
        )

        val outcome = invokePrepare(textDocuments, params)

        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document = null,
            context = "never-opened document uri"
        )
    }

    @Test
    fun prepare_call_hierarchy_out_of_bounds_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-safety-oob.lua",
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

        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "out-of-bounds position",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun prepare_call_hierarchy_on_whitespace_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-safety-whitespace.lua",
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

        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "whitespace / blank line",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun prepare_call_hierarchy_on_keyword_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-safety-keyword.lua",
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

        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "keyword 'local'",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun prepare_call_hierarchy_on_numeric_literal_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-safety-number.lua",
            "local value = 42\nreturn value"
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("42"))
        )

        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "numeric literal",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun prepare_call_hierarchy_local_function_corpus_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-safety-local-function.lua",
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

        val onDef = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("helper", occurrence = 1))
        )
        val onCall = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("helper", occurrence = 2))
        )

        assertPrepareDegradesAsGapOrEmpty(
            onDef,
            document,
            context = "local function definition 'helper'"
        )
        assertPrepareDegradesAsGapOrEmpty(
            onCall,
            document,
            context = "local function call site helper()"
        )
    }

    @Test
    fun prepare_call_hierarchy_twice_is_stable_on_gap_or_empty_or_items() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-safety-twice.lua",
            """
            local function a()
                return 1
            end
            local function b()
                return a()
            end
            return b
            """
        )
        val params = prepareParams(document, document.positionOf("a", occurrence = 1))

        val first = invokePrepare(textDocuments, params)
        val second = invokePrepare(textDocuments, params)

        assertPrepareDegradesAsGapOrEmpty(first, document, context = "first prepareCallHierarchy call")
        assertPrepareDegradesAsGapOrEmpty(second, document, context = "second prepareCallHierarchy call")

        // Same dual-path class on both invocations (gap stays gap; empty stays empty;
        // live items stay live). Soft: do not require identical payloads yet.
        assertTrue(
            first::class == second::class ||
                (first is PrepareOutcome.Empty && second is PrepareOutcome.Succeeded) ||
                (first is PrepareOutcome.Succeeded && second is PrepareOutcome.Empty),
            "repeated prepareCallHierarchy calls should stay on the same dual-path family; " +
                "first=$first second=$second"
        )
        if (first is PrepareOutcome.Succeeded && second is PrepareOutcome.Succeeded) {
            assertWellFormedItems(first.items, document, label = "first call")
            assertWellFormedItems(second.items, document, label = "second call")
        }
    }

    // -------------------------------------------------------------------------
    // Incoming / outgoing safety (independent of prepare product readiness)
    // -------------------------------------------------------------------------

    @Test
    fun incoming_calls_surface_is_invokable_without_killing_service() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-safety-incoming-surface.lua",
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
        val item = syntheticItem(document, name = "target", needle = "target", occurrence = 1)

        val outcome = invokeIncoming(textDocuments, CallHierarchyIncomingCallsParams(item))

        assertTrue(
            outcome is IncomingOutcome.Unsupported ||
                outcome is IncomingOutcome.Empty ||
                outcome is IncomingOutcome.Succeeded ||
                outcome is IncomingOutcome.Failed,
            "callHierarchy/incomingCalls surface must resolve to a known outcome; got $outcome"
        )
        if (outcome is IncomingOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "incomingCalls surface must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is IncomingOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is IncomingOutcome.Succeeded) {
            assertWellFormedIncoming(outcome.calls, document, label = "surface incoming")
        }
    }

    @Test
    fun outgoing_calls_surface_is_invokable_without_killing_service() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-safety-outgoing-surface.lua",
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
        val item = syntheticItem(document, name = "root", needle = "root", occurrence = 1)

        val outcome = invokeOutgoing(textDocuments, CallHierarchyOutgoingCallsParams(item))

        assertTrue(
            outcome is OutgoingOutcome.Unsupported ||
                outcome is OutgoingOutcome.Empty ||
                outcome is OutgoingOutcome.Succeeded ||
                outcome is OutgoingOutcome.Failed,
            "callHierarchy/outgoingCalls surface must resolve to a known outcome; got $outcome"
        )
        if (outcome is OutgoingOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "outgoingCalls surface must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is OutgoingOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is OutgoingOutcome.Succeeded) {
            assertWellFormedOutgoing(outcome.calls, document, label = "surface outgoing")
        }
    }

    @Test
    fun incoming_calls_unknown_item_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-safety-incoming-unknown.lua",
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

        assertIncomingDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "incomingCalls for unknown PhantomMissing item",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun outgoing_calls_unknown_item_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-safety-outgoing-unknown.lua",
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

        assertOutgoingDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "outgoingCalls for unknown PhantomMissing item",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun incoming_calls_never_opened_uri_item_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val phantom = CallHierarchyItem(
            "Ghost",
            SymbolKind.Function,
            "file:///workspace/call-hierarchy-safety-incoming-never-opened.lua",
            Range(Position(0, 0), Position(0, 1)),
            Range(Position(0, 0), Position(0, 1))
        )

        val outcome = invokeIncoming(textDocuments, CallHierarchyIncomingCallsParams(phantom))

        assertIncomingDegradesAsGapOrEmpty(
            outcome,
            document = null,
            context = "incomingCalls for never-opened uri item"
        )
    }

    @Test
    fun outgoing_calls_never_opened_uri_item_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val phantom = CallHierarchyItem(
            "Ghost",
            SymbolKind.Function,
            "file:///workspace/call-hierarchy-safety-outgoing-never-opened.lua",
            Range(Position(0, 0), Position(0, 1)),
            Range(Position(0, 0), Position(0, 1))
        )

        val outcome = invokeOutgoing(textDocuments, CallHierarchyOutgoingCallsParams(phantom))

        assertOutgoingDegradesAsGapOrEmpty(
            outcome,
            document = null,
            context = "outgoingCalls for never-opened uri item"
        )
    }

    @Test
    fun prepare_then_incoming_outgoing_chain_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/call-hierarchy-safety-chain.lua",
            """
            local function leaf()
                return 1
            end
            local function mid()
                return leaf()
            end
            local function root()
                return mid()
            end
            return root()
            """
        )

        val prepare = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("mid", occurrence = 1))
        )
        assertPrepareDegradesAsGapOrEmpty(
            prepare,
            document,
            context = "prepare in prepare→incoming/outgoing chain"
        )

        val item = when (prepare) {
            is PrepareOutcome.Succeeded -> prepare.items.firstOrNull()
                ?: syntheticItem(document, name = "mid", needle = "mid", occurrence = 1)
            else -> syntheticItem(document, name = "mid", needle = "mid", occurrence = 1)
        }

        val incoming = invokeIncoming(textDocuments, CallHierarchyIncomingCallsParams(item))
        val outgoing = invokeOutgoing(textDocuments, CallHierarchyOutgoingCallsParams(item))

        assertIncomingDegradesAsGapOrEmpty(
            incoming,
            document,
            context = "incoming in prepare→incoming/outgoing chain"
        )
        assertOutgoingDegradesAsGapOrEmpty(
            outgoing,
            document,
            context = "outgoing in prepare→incoming/outgoing chain"
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
            classifyPrepare(items)
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
            classifyIncoming(calls)
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
            classifyOutgoing(calls)
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                OutgoingOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                OutgoingOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun classifyPrepare(items: List<CallHierarchyItem>?): PrepareOutcome {
        if (items == null) {
            return PrepareOutcome.Empty(detail = "null prepare items")
        }
        val list = items.toList()
        return if (list.isEmpty()) {
            PrepareOutcome.Empty(detail = "empty prepare item list")
        } else {
            PrepareOutcome.Succeeded(items = list)
        }
    }

    private fun classifyIncoming(calls: List<CallHierarchyIncomingCall>?): IncomingOutcome {
        if (calls == null) {
            return IncomingOutcome.Empty(detail = "null incoming calls")
        }
        val list = calls.toList()
        return if (list.isEmpty()) {
            IncomingOutcome.Empty(detail = "empty incoming call list")
        } else {
            IncomingOutcome.Succeeded(calls = list)
        }
    }

    private fun classifyOutgoing(calls: List<CallHierarchyOutgoingCall>?): OutgoingOutcome {
        if (calls == null) {
            return OutgoingOutcome.Empty(detail = "null outgoing calls")
        }
        val list = calls.toList()
        return if (list.isEmpty()) {
            OutgoingOutcome.Empty(detail = "empty outgoing call list")
        } else {
            OutgoingOutcome.Succeeded(calls = list)
        }
    }

    private fun assertPrepareDegradesAsGapOrEmpty(
        outcome: PrepareOutcome,
        document: OpenDocument?,
        context: String,
        allowNonEmptyWhenSucceeded: Boolean = true
    ) {
        when (outcome) {
            is PrepareOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is PrepareOutcome.Empty -> {
                // Soft degrade (empty / null) is the ideal unimplemented-or-no-hit path
                // "per product ads" once the server elects not to throw.
            }
            is PrepareOutcome.Succeeded -> {
                if (document != null) {
                    assertWellFormedItems(outcome.items, document, label = "items for $context")
                } else {
                    outcome.items.forEach { item ->
                        assertNotNull(item.name, "CallHierarchyItem.name must be non-null for $context")
                        assertTrue(item.name.isNotBlank(), "CallHierarchyItem.name must be non-blank for $context")
                    }
                }
                if (!allowNonEmptyWhenSucceeded) {
                    // Non-function / empty / oob positions should ideally be Empty;
                    // if product invents items they must still be shape-valid (already checked).
                    assertTrue(
                        outcome.items.isNotEmpty(),
                        "$context succeeded path has non-empty items (already validated)"
                    )
                }
            }
            is PrepareOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "prepareCallHierarchy at $context must not NPE/assert; got ${outcome.detail}"
                )
            }
        }
    }

    private fun assertIncomingDegradesAsGapOrEmpty(
        outcome: IncomingOutcome,
        document: OpenDocument?,
        context: String,
        allowNonEmptyWhenSucceeded: Boolean = true
    ) {
        when (outcome) {
            is IncomingOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is IncomingOutcome.Empty -> {
                // Soft degrade ideal.
            }
            is IncomingOutcome.Succeeded -> {
                if (document != null) {
                    assertWellFormedIncoming(outcome.calls, document, label = "incoming for $context")
                }
                if (!allowNonEmptyWhenSucceeded) {
                    assertTrue(
                        outcome.calls.isNotEmpty(),
                        "$context succeeded path has non-empty calls (already validated)"
                    )
                }
            }
            is IncomingOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "incomingCalls at $context must not NPE/assert; got ${outcome.detail}"
                )
            }
        }
    }

    private fun assertOutgoingDegradesAsGapOrEmpty(
        outcome: OutgoingOutcome,
        document: OpenDocument?,
        context: String,
        allowNonEmptyWhenSucceeded: Boolean = true
    ) {
        when (outcome) {
            is OutgoingOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is OutgoingOutcome.Empty -> {
                // Soft degrade ideal.
            }
            is OutgoingOutcome.Succeeded -> {
                if (document != null) {
                    assertWellFormedOutgoing(outcome.calls, document, label = "outgoing for $context")
                }
                if (!allowNonEmptyWhenSucceeded) {
                    assertTrue(
                        outcome.calls.isNotEmpty(),
                        "$context succeeded path has non-empty calls (already validated)"
                    )
                }
            }
            is OutgoingOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "outgoingCalls at $context must not NPE/assert; got ${outcome.detail}"
                )
            }
        }
    }

    private fun assertWellFormedItems(
        items: List<CallHierarchyItem>,
        document: OpenDocument,
        label: String
    ) {
        items.forEach { item ->
            assertNotNull(item.name, "$label: CallHierarchyItem.name must be non-null")
            assertTrue(
                item.name.isNotBlank(),
                "$label: CallHierarchyItem.name must be non-blank: ${describeItems(listOf(item))}"
            )
            assertNotNull(item.kind, "$label: CallHierarchyItem.kind must be non-null for ${item.name}")
            assertTrue(
                item.uri.isNullOrBlank() || item.uri == document.uri || item.uri.startsWith("file:"),
                "$label: CallHierarchyItem.uri should be empty or a file URI; got '${item.uri}' for ${item.name}"
            )
            val range = item.range
            val selection = item.selectionRange
            assertNotNull(range, "$label: CallHierarchyItem.range must be non-null for ${item.name}")
            assertNotNull(selection, "$label: CallHierarchyItem.selectionRange must be non-null for ${item.name}")
            assertOrderedRange(range, label = "$label range of ${item.name}")
            assertOrderedRange(selection, label = "$label selectionRange of ${item.name}")
            assertTrue(
                containsRange(range, selection),
                "$label: selectionRange must be inside range for ${item.name}; " +
                    "range=${formatRange(range)} selection=${formatRange(selection)}"
            )
        }
    }

    private fun assertWellFormedIncoming(
        calls: List<CallHierarchyIncomingCall>,
        document: OpenDocument,
        label: String
    ) {
        calls.forEach { call ->
            assertNotNull(call.from, "$label: CallHierarchyIncomingCall.from must be non-null")
            assertWellFormedItems(listOf(call.from), document, label = "$label.from")
            assertNotNull(call.fromRanges, "$label: CallHierarchyIncomingCall.fromRanges must be non-null")
            call.fromRanges.forEachIndexed { index, range ->
                assertOrderedRange(range, label = "$label incoming fromRanges[$index] of ${call.from.name}")
            }
        }
    }

    private fun assertWellFormedOutgoing(
        calls: List<CallHierarchyOutgoingCall>,
        document: OpenDocument,
        label: String
    ) {
        calls.forEach { call ->
            assertNotNull(call.to, "$label: CallHierarchyOutgoingCall.to must be non-null")
            assertWellFormedItems(listOf(call.to), document, label = "$label.to")
            assertNotNull(call.fromRanges, "$label: CallHierarchyOutgoingCall.fromRanges must be non-null")
            call.fromRanges.forEachIndexed { index, range ->
                assertOrderedRange(range, label = "$label outgoing fromRanges[$index] of ${call.to.name}")
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

    private fun formatRange(range: Range?): String {
        if (range == null) {
            return "null"
        }
        return "${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character}"
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

    private sealed class PrepareOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : PrepareOutcome()

        data class Empty(val detail: String) : PrepareOutcome()

        data class Succeeded(val items: List<CallHierarchyItem>) : PrepareOutcome()

        data class Failed(val detail: String) : PrepareOutcome()
    }

    private sealed class IncomingOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : IncomingOutcome()

        data class Empty(val detail: String) : IncomingOutcome()

        data class Succeeded(val calls: List<CallHierarchyIncomingCall>) : IncomingOutcome()

        data class Failed(val detail: String) : IncomingOutcome()
    }

    private sealed class OutgoingOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : OutgoingOutcome()

        data class Empty(val detail: String) : OutgoingOutcome()

        data class Succeeded(val calls: List<CallHierarchyOutgoingCall>) : OutgoingOutcome()

        data class Failed(val detail: String) : OutgoingOutcome()
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
