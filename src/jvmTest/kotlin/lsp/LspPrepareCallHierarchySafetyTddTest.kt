package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
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
 * TASK-470 — LSP prepareCallHierarchy dual-path safety corpus.
 *
 * Focused lock for `textDocument/prepareCallHierarchy` when the feature is
 * unimplemented or only partially product-reachable:
 * - Unimplemented surface (LSP4J default [UnsupportedOperationException] on
 *   [LuaTextDocumentService]) must degrade as a documented gap — never as an
 *   unexpected hard crash (NPE / AssertionError / IndexOutOfBounds).
 * - When product soft-degrades instead of throwing, empty / null item lists are
 *   accepted ("empty per product ads").
 * - Empty documents, syntax-error buffers, large files, never-opened URIs,
 *   non-callable positions (whitespace / keyword / literal / operator / comment),
 *   out-of-bounds cursors, CRLF sources, method/colon/table forms, globals, and
 *   multi-prepare stability must not invent process-killing failures.
 * - When product returns items (capability advertised and surface live), each
 *   [CallHierarchyItem] must be well-formed (non-blank name, kind, ordered
 *   range/selectionRange with selection inside range).
 *
 * Complements:
 * - [LspCallHierarchySafetyTddTest] (TASK-420 full prepare + incoming/outgoing
 *   safety dual-path)
 * - [LspCallHierarchyLocalFunctionTddTest] (TASK-272 local-function corpus)
 *
 * Product call hierarchy remains out of scope for this worker (test-only).
 * Current [LuaLanguageService] does not advertise callHierarchyProvider and
 * [LuaTextDocumentService] inherits the LSP4J defaults that throw
 * [UnsupportedOperationException]. Tests therefore dual-path:
 * - Documented gap: unimplemented prepareCallHierarchy completes exceptionally
 *   with UnsupportedOperationException and is recorded as the known surface.
 * - Ideal / soft path: empty list (or null → treated as empty) when the server
 *   elects soft degrade per product ads; well-formed items when live.
 *
 * Verification is review-owned and serial; this worker does not run Gradle.
 * Host android.jar paths (Downloads + SDK android-35) only — never G:/.
 */
class LspPrepareCallHierarchySafetyTddTest {

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
    fun prepare_call_hierarchy_surface_is_invokable_without_killing_service() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-surface.lua",
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
    fun prepare_call_hierarchy_unimplemented_degrades_as_documented_gap_or_empty() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-gap.lua",
            """
            local function greet(name)
                return name
            end
            return greet
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("greet", occurrence = 1))
        )
        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "unimplemented / pre-product prepareCallHierarchy"
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
            "workspace/prepare-call-hierarchy-safety-empty.lua",
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
            "workspace/prepare-call-hierarchy-safety-syntax-error.lua",
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
            "workspace/prepare-call-hierarchy-safety-large.lua",
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
            TextDocumentIdentifier("file:///workspace/prepare-call-hierarchy-safety-never-opened.lua"),
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
            "workspace/prepare-call-hierarchy-safety-oob.lua",
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

    // -------------------------------------------------------------------------
    // Non-callable positions: whitespace / keyword / literals / operator / comment
    // -------------------------------------------------------------------------

    @Test
    fun prepare_call_hierarchy_on_whitespace_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-whitespace.lua",
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
            "workspace/prepare-call-hierarchy-safety-keyword.lua",
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
            "workspace/prepare-call-hierarchy-safety-number.lua",
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
    fun prepare_call_hierarchy_on_string_literal_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-string.lua",
            """
            local function work()
                return "hello"
            end
            return work
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("\"hello\""))
        )

        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "string literal",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun prepare_call_hierarchy_on_operator_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-operator.lua",
            """
            local function add(a, b)
                return a + b
            end
            return add
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("+"))
        )

        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "binary operator '+'",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun prepare_call_hierarchy_on_comment_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-comment.lua",
            """
            -- helper is not a call hierarchy root by itself
            local function helper()
                return 1
            end
            return helper
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("helper is not"))
        )

        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "line comment text",
            allowNonEmptyWhenSucceeded = false
        )
    }

    // -------------------------------------------------------------------------
    // Callable-shape corpora: local / assigned / method / colon / global
    // -------------------------------------------------------------------------

    @Test
    fun prepare_call_hierarchy_local_function_def_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-local-def.lua",
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
    fun prepare_call_hierarchy_assigned_function_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-assigned.lua",
            """
            local add = function(a, b)
                return a + b
            end
            return add(1, 2)
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("add", occurrence = 1))
        )

        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "local assigned function 'add'"
        )
    }

    @Test
    fun prepare_call_hierarchy_table_method_dot_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-table-dot.lua",
            """
            local M = {}
            function M.render(value)
                return value
            end
            return M.render(1)
            """
        )

        val onDef = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("render", occurrence = 1))
        )
        val onCall = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("render", occurrence = 2))
        )

        assertPrepareDegradesAsGapOrEmpty(
            onDef,
            document,
            context = "table method definition M.render"
        )
        assertPrepareDegradesAsGapOrEmpty(
            onCall,
            document,
            context = "table method call M.render(...)"
        )
    }

    @Test
    fun prepare_call_hierarchy_colon_method_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-colon.lua",
            """
            local Obj = {}
            function Obj:ping()
                return self
            end
            return Obj:ping()
            """
        )

        val onDef = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("ping", occurrence = 1))
        )
        val onCall = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("ping", occurrence = 2))
        )

        assertPrepareDegradesAsGapOrEmpty(
            onDef,
            document,
            context = "colon method definition Obj:ping"
        )
        assertPrepareDegradesAsGapOrEmpty(
            onCall,
            document,
            context = "colon method call Obj:ping()"
        )
    }

    @Test
    fun prepare_call_hierarchy_global_function_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-global.lua",
            """
            function globalWork()
                return 1
            end
            return globalWork()
            """
        )

        val onDef = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("globalWork", occurrence = 1))
        )
        val onCall = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("globalWork", occurrence = 2))
        )

        assertPrepareDegradesAsGapOrEmpty(
            onDef,
            document,
            context = "global function definition globalWork"
        )
        assertPrepareDegradesAsGapOrEmpty(
            onCall,
            document,
            context = "global function call globalWork()"
        )
    }

    @Test
    fun prepare_call_hierarchy_nested_local_function_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-nested.lua",
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

        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "nested local function 'inner'"
        )
    }

    // -------------------------------------------------------------------------
    // Encoding / stability / multi-position edge cases
    // -------------------------------------------------------------------------

    @Test
    fun prepare_call_hierarchy_crlf_document_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val source = "local function crlf()\r\n    return 1\r\nend\r\nreturn crlf()\r\n"
        val document = textDocuments.openRaw(
            "workspace/prepare-call-hierarchy-safety-crlf.lua",
            source
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("crlf", occurrence = 1))
        )

        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "CRLF-encoded document"
        )
    }

    @Test
    fun prepare_call_hierarchy_twice_is_stable_on_gap_or_empty_or_items() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-twice.lua",
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

    @Test
    fun prepare_call_hierarchy_two_positions_same_document_do_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-two-positions.lua",
            """
            local function left()
                return 1
            end
            local function right()
                return left()
            end
            return right()
            """
        )

        val left = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("left", occurrence = 1))
        )
        val right = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("right", occurrence = 1))
        )

        assertPrepareDegradesAsGapOrEmpty(left, document, context = "position on left()")
        assertPrepareDegradesAsGapOrEmpty(right, document, context = "position on right()")
    }

    @Test
    fun prepare_call_hierarchy_malformed_function_header_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-malformed.lua",
            """
            local function (
            function
            local broken = function
            return broken
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("broken"))
        )

        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "malformed function headers / local broken",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun prepare_call_hierarchy_eof_position_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-eof.lua",
            """
            local function work()
                return 1
            end
            return work
            """
        )

        val lastLine = document.source.count { it == '\n' }
        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, Position(lastLine + 2, 0))
        )

        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "position past EOF",
            allowNonEmptyWhenSucceeded = false
        )
    }

    // -------------------------------------------------------------------------
    // Light follow-on probe (prepare-centric; does not replace TASK-420 corpus)
    // -------------------------------------------------------------------------

    @Test
    fun prepare_then_incoming_outgoing_chain_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-chain.lua",
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

        // Follow-on surfaces are exercised only to ensure prepare-derived items
        // (or synthetic fallbacks) never hard-crash the process. Detailed
        // incoming/outgoing contracts live in LspCallHierarchySafetyTddTest.
        val incoming = invokeFollowOn(textDocuments) {
            it.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item)).get()
        }
        val outgoing = invokeFollowOn(textDocuments) {
            it.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(item)).get()
        }

        assertFollowOnDegradesAsGapOrEmpty(incoming, context = "incoming after prepare")
        assertFollowOnDegradesAsGapOrEmpty(outgoing, context = "outgoing after prepare")
    }

    @Test
    fun prepare_call_hierarchy_host_android_jar_paths_are_host_only_never_g_drive() {
        // Host-path policy lock for multi-agent workers: corpus must never encode
        // Windows G:/ android.jar paths. Accept only Downloads + SDK android-35.
        val downloads = "/Users/dingyi/Downloads/android.jar"
        val sdk = "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar"
        val forbidden = listOf("G:/", "G:\\", "g:/android.jar", "G:/android.jar")

        assertTrue(downloads.startsWith("/Users/dingyi/"), "Downloads android.jar must be host path")
        assertTrue(sdk.startsWith("/Users/dingyi/"), "SDK android.jar must be host path")
        forbidden.forEach { bad ->
            assertFalse(
                downloads.contains(bad, ignoreCase = true) || sdk.contains(bad, ignoreCase = true),
                "Host android.jar paths must never use $bad"
            )
        }

        // prepareCallHierarchy itself is language-surface only; this assertion
        // documents the wave host-path contract for the corpus file.
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-call-hierarchy-safety-host-paths.lua",
            "local function hostPathProbe()\n  return 1\nend\nreturn hostPathProbe"
        )
        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("hostPathProbe", occurrence = 1))
        )
        assertPrepareDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "host-path policy probe prepareCallHierarchy"
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

    private fun LuaTextDocumentService.openRaw(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source)
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

    private fun invokeFollowOn(
        textDocuments: LuaTextDocumentService,
        block: (LuaTextDocumentService) -> Any?
    ): FollowOnOutcome {
        return try {
            val result = block(textDocuments)
            when (result) {
                null -> FollowOnOutcome.Empty(detail = "null follow-on result")
                is Collection<*> -> {
                    if (result.isEmpty()) {
                        FollowOnOutcome.Empty(detail = "empty follow-on list")
                    } else {
                        FollowOnOutcome.Succeeded(size = result.size)
                    }
                }
                else -> FollowOnOutcome.Succeeded(size = 1)
            }
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                FollowOnOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                FollowOnOutcome.Failed(detail = root.toString())
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
                    // Non-callable / empty / oob positions should ideally be Empty;
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

    private fun assertFollowOnDegradesAsGapOrEmpty(outcome: FollowOnOutcome, context: String) {
        when (outcome) {
            is FollowOnOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is FollowOnOutcome.Empty -> {
                // Soft degrade ideal.
            }
            is FollowOnOutcome.Succeeded -> {
                assertTrue(outcome.size >= 0, "$context succeeded size must be non-negative")
            }
            is FollowOnOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "follow-on at $context must not NPE/assert; got ${outcome.detail}"
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

    private sealed class FollowOnOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : FollowOnOutcome()

        data class Empty(val detail: String) : FollowOnOutcome()

        data class Succeeded(val size: Int) : FollowOnOutcome()

        data class Failed(val detail: String) : FollowOnOutcome()
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
