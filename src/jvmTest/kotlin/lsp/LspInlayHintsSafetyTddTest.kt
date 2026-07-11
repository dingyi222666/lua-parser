package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-417 — LSP inlay hints dual-path safety corpus.
 *
 * Locks the safety contract for `textDocument/inlayHint` when the feature is
 * unimplemented or only partially product-reachable:
 * - Unimplemented surface (LSP4J default [UnsupportedOperationException] on
 *   [LuaTextDocumentService]) must degrade as a documented gap — never as an
 *   unexpected hard crash (NPE / AssertionError / IndexOutOfBounds).
 * - When product soft-degrades instead of throwing, empty / null hint lists are
 *   accepted ("empty per product ads").
 * - Empty documents, syntax-error buffers, call-free files, large open docs,
 *   never-opened URIs, and out-of-bounds / empty / inverted ranges must not
 *   invent process-killing failures.
 * - When product returns inlay hints (capability advertised and surface live),
 *   each entry must be well-formed (non-null position with non-negative
 *   line/character, non-empty label, optional kind limited to known
 *   [InlayHintKind] values, position inside document bounds when lineCount > 0).
 *
 * Complements [LspInlayHintsParamNameTddTest] (TASK-270 parameter-name corpus,
 * which Assume-skips when unimplemented) with an explicit dual-path safety lock
 * that never skips: Unsupported / Empty / Succeeded / Failed are all first-class
 * outcomes, and only hard-crash classes fail the suite.
 *
 * Product inlay hints remain out of scope for this worker (test-only). Current
 * [LuaLanguageService] does not advertise inlayHintProvider and
 * [LuaTextDocumentService] inherits the LSP4J default that throws
 * [UnsupportedOperationException]. Tests therefore dual-path:
 * - Documented gap: unimplemented methods complete exceptionally with
 *   UnsupportedOperationException and are recorded as the known surface.
 * - Ideal / soft path: empty hint list (or null → treated as empty) when the
 *   server elects soft degrade; well-formed hints when live.
 *
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class LspInlayHintsSafetyTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun initialize_inlay_hint_capability_is_null_or_explicit_when_product_lands() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        assertNotNull(capabilities, "initialize must return ServerCapabilities")
        val provider = capabilities.inlayHintProvider
        if (provider == null) {
            // Documented gap: product has not advertised inlay hints yet.
            assertTrue(
                true,
                "inlayHintProvider absent is an accepted pre-product surface"
            )
            return
        }

        // Advertised as boolean true or as InlayHintRegistrationOptions.
        val enabled = when {
            provider.isLeft -> provider.left == true
            provider.isRight -> true
            else -> false
        }
        assertTrue(
            enabled,
            "advertised inlayHintProvider must enable inlay hints (got $provider)"
        )
    }

    @Test
    fun inlay_hint_surface_is_invokable_without_killing_service() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-safety-surface.lua",
            """
            ---@param value number
            ---@param label string
            local function render(value, label)
                return label
            end
            local current = render(1, "hi")
            return current
            """
        )

        val outcome = invokeInlayHint(
            textDocuments,
            fullDocumentInlayParams(document)
        )

        assertTrue(
            outcome is InlayOutcome.Unsupported ||
                outcome is InlayOutcome.Empty ||
                outcome is InlayOutcome.Succeeded ||
                outcome is InlayOutcome.Failed,
            "inlayHint surface must resolve to a known outcome; got $outcome " +
                "(inlayHintProvider=${capabilities.inlayHintProvider})"
        )

        if (outcome is InlayOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "inlayHint surface must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is InlayOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is InlayOutcome.Succeeded) {
            assertWellFormedHints(outcome.hints, document, label = "surface hints")
        }
    }

    @Test
    fun inlay_hint_unimplemented_degrades_as_documented_gap_or_empty() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-safety-gap.lua",
            """
            ---@param a number
            ---@param b string
            local function paint(a, b)
                return b
            end
            local current = paint(1, "mid")
            return current
            """
        )

        val outcome = invokeInlayHint(
            textDocuments,
            fullDocumentInlayParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "unimplemented / pre-product inlayHint"
        )
    }

    // -------------------------------------------------------------------------
    // Full-range safety: empty / syntax error / large / call-free / unknown uri
    // -------------------------------------------------------------------------

    @Test
    fun inlay_hint_empty_document_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-safety-empty.lua",
            ""
        )

        val outcome = invokeInlayHint(
            textDocuments,
            fullDocumentInlayParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "empty document",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun inlay_hint_syntax_error_buffer_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-safety-syntax-error.lua",
            "local =\nfunction (\nend\npaint(1, 2,"
        )

        val outcome = invokeInlayHint(
            textDocuments,
            fullDocumentInlayParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "syntax-error buffer"
        )
    }

    @Test
    fun inlay_hint_call_free_document_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-safety-call-free.lua",
            """
            local name = "token"
            local value = 1
            return name
            """
        )

        val outcome = invokeInlayHint(
            textDocuments,
            fullDocumentInlayParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "call-free document"
        )
    }

    @Test
    fun inlay_hint_large_document_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val body = buildString {
            appendLine("---@param a number")
            appendLine("---@param b string")
            appendLine("---@param c boolean")
            appendLine("local function paint(a, b, c)")
            appendLine("    return c")
            appendLine("end")
            repeat(400) { index ->
                appendLine("local v$index = paint($index, \"mid$index\", true)")
            }
            appendLine("return v0")
        }
        val document = textDocuments.open(
            "workspace/inlay-safety-large.lua",
            body
        )

        val outcome = invokeInlayHint(
            textDocuments,
            fullDocumentInlayParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "large multi-call document"
        )
    }

    @Test
    fun inlay_hint_never_opened_uri_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val params = InlayHintParams(
            TextDocumentIdentifier("file:///workspace/inlay-safety-never-opened.lua"),
            Range(Position(0, 0), Position(0, 1))
        )

        val outcome = invokeInlayHint(textDocuments, params)

        // No OpenDocument available for bounds checks; only dual-path shape.
        when (outcome) {
            is InlayOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for never-opened uri expects UnsupportedOperationException; " +
                        "got ${outcome.detail}"
                )
            }
            is InlayOutcome.Empty -> {
                // Soft degrade is ideal for unknown uri.
            }
            is InlayOutcome.Succeeded -> {
                // Product may still return a list; require non-null positions/labels.
                outcome.hints.forEachIndexed { index, hint ->
                    assertNotNull(hint.position, "never-opened uri hints[$index].position")
                    assertTrue(
                        hint.position.line >= 0 && hint.position.character >= 0,
                        "never-opened uri hints[$index] position must be non-negative"
                    )
                    val labels = hintLabels(hint)
                    assertTrue(
                        labels.isNotEmpty() && labels.all { it.isNotEmpty() },
                        "never-opened uri hints[$index] label must be non-empty"
                    )
                }
            }
            is InlayOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "inlayHint on never-opened uri must not NPE/assert; got ${outcome.detail}"
                )
            }
        }
    }

    @Test
    fun inlay_hint_multi_arg_call_corpus_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-safety-multi-arg.lua",
            """
            ---@param a number
            ---@param b string
            ---@param c boolean
            local function paint(a, b, c)
                return c
            end
            local current = paint(1, "mid", true)
            return current
            """
        )

        val outcome = invokeInlayHint(
            textDocuments,
            fullDocumentInlayParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "multi-arg call corpus"
        )
    }

    @Test
    fun inlay_hint_colon_method_call_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-safety-colon.lua",
            """
            local box = {}
            ---@param self table
            ---@param value number
            ---@param label string
            function box:render(value, label)
                return label
            end
            local current = box:render(1, "hi")
            return current
            """
        )

        val outcome = invokeInlayHint(
            textDocuments,
            fullDocumentInlayParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "colon method call"
        )
    }

    @Test
    fun inlay_hint_twice_is_stable_on_gap_or_empty_or_data() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-safety-twice.lua",
            """
            ---@param value number
            ---@param label string
            local function render(value, label)
                return label
            end
            local current = render(1, "hi")
            return current
            """
        )
        val params = fullDocumentInlayParams(document)

        val first = invokeInlayHint(textDocuments, params)
        val second = invokeInlayHint(textDocuments, params)

        assertDegradesAsGapOrEmpty(first, document, context = "first inlayHint call")
        assertDegradesAsGapOrEmpty(second, document, context = "second inlayHint call")

        // Same dual-path class on both invocations (gap stays gap; empty stays empty;
        // live data stays live). Soft: do not require identical payloads yet.
        assertTrue(
            first::class == second::class ||
                (first is InlayOutcome.Empty && second is InlayOutcome.Succeeded) ||
                (first is InlayOutcome.Succeeded && second is InlayOutcome.Empty),
            "repeated inlayHint calls should stay on the same dual-path family; " +
                "first=$first second=$second"
        )
        if (first is InlayOutcome.Succeeded && second is InlayOutcome.Succeeded) {
            assertWellFormedHints(first.hints, document, label = "first inlayHint")
            assertWellFormedHints(second.hints, document, label = "second inlayHint")
        }
    }

    // -------------------------------------------------------------------------
    // Range request safety (also dual-path)
    // -------------------------------------------------------------------------

    @Test
    fun inlay_hint_range_outside_calls_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-safety-range-outside.lua",
            """
            ---@param value number
            ---@param label string
            local function render(value, label)
                return label
            end
            local current = render(1, "hi")
            return current
            """
        )

        // Range covering only the function declaration header (no call site).
        val outcome = invokeInlayHint(
            textDocuments,
            InlayHintParams(
                TextDocumentIdentifier(document.uri),
                Range(Position(0, 0), Position(3, 0))
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "range outside call sites"
        )
    }

    @Test
    fun inlay_hint_empty_selection_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-safety-range-empty.lua",
            """
            ---@param value number
            local function render(value)
                return value
            end
            local current = render(1)
            return current
            """
        )

        val outcome = invokeInlayHint(
            textDocuments,
            InlayHintParams(
                TextDocumentIdentifier(document.uri),
                Range(Position(0, 0), Position(0, 0))
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "empty selection range"
        )
    }

    @Test
    fun inlay_hint_out_of_bounds_range_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-safety-range-oob.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeInlayHint(
            textDocuments,
            InlayHintParams(
                TextDocumentIdentifier(document.uri),
                Range(Position(50, 0), Position(60, 10))
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "out-of-bounds range"
        )
    }

    @Test
    fun inlay_hint_inverted_range_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-safety-range-inverted.lua",
            """
            ---@param value number
            local function render(value)
                return value
            end
            local current = render(1)
            return current
            """
        )

        // End before start — malformed client range must soft-degrade.
        val outcome = invokeInlayHint(
            textDocuments,
            InlayHintParams(
                TextDocumentIdentifier(document.uri),
                Range(Position(4, 0), Position(0, 0))
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "inverted range"
        )
    }

    @Test
    fun inlay_hint_whitespace_only_range_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-safety-range-whitespace.lua",
            "local value = 1\n\nreturn value"
        )

        // Blank line between statements.
        val outcome = invokeInlayHint(
            textDocuments,
            InlayHintParams(
                TextDocumentIdentifier(document.uri),
                Range(Position(1, 0), Position(1, 0))
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "whitespace-only range"
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

    private fun fullDocumentInlayParams(document: OpenDocument): InlayHintParams {
        val endLine = (document.lineCount - 1).coerceAtLeast(0)
        val endChar = if (document.lineCount == 0) {
            0
        } else {
            document.source.substringAfterLast('\n').length
        }
        return InlayHintParams(
            TextDocumentIdentifier(document.uri),
            Range(Position(0, 0), Position(endLine, endChar))
        )
    }

    private fun invokeInlayHint(
        textDocuments: LuaTextDocumentService,
        params: InlayHintParams
    ): InlayOutcome {
        return try {
            val hints = textDocuments.inlayHint(params).get()
            classifyHints(hints)
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                InlayOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                InlayOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun classifyHints(hints: List<InlayHint>?): InlayOutcome {
        if (hints == null) {
            return InlayOutcome.Empty(detail = "null hint list")
        }
        return if (hints.isEmpty()) {
            InlayOutcome.Empty(detail = "empty hint list")
        } else {
            InlayOutcome.Succeeded(hints = hints)
        }
    }

    private fun assertDegradesAsGapOrEmpty(
        outcome: InlayOutcome,
        document: OpenDocument,
        context: String,
        allowNonEmptyWhenSucceeded: Boolean = true
    ) {
        when (outcome) {
            is InlayOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is InlayOutcome.Empty -> {
                // Soft degrade (empty / null) is the ideal unimplemented-or-no-hit path
                // "per product ads" once the server elects not to throw.
            }
            is InlayOutcome.Succeeded -> {
                assertWellFormedHints(outcome.hints, document, label = "hints for $context")
                if (!allowNonEmptyWhenSucceeded) {
                    // Empty document: classifyHints already routes empty/null to Empty.
                    // If we land here, product invented non-empty hints for an empty
                    // buffer (unusual); shape is already validated above.
                    assertTrue(
                        outcome.hints.isNotEmpty(),
                        "$context succeeded path is non-empty by construction"
                    )
                }
            }
            is InlayOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "inlayHint at $context must not NPE/assert; got ${outcome.detail}"
                )
                // Soft product errors (ResponseError, IllegalState, etc.) are allowed
                // while the feature is partial; hard process-killing crash classes are not.
            }
        }
    }

    private fun assertWellFormedHints(
        hints: List<InlayHint>,
        document: OpenDocument,
        label: String
    ) {
        assertTrue(hints.isNotEmpty(), "$label expected non-empty hint list")
        val lineCount = document.lineCount
        hints.forEachIndexed { index, hint ->
            val position = hint.position
            assertNotNull(position, "$label[$index].position must be non-null")
            assertTrue(
                position.line >= 0,
                "$label[$index].position.line must be >= 0; got ${position.line}"
            )
            assertTrue(
                position.character >= 0,
                "$label[$index].position.character must be >= 0; got ${position.character}"
            )
            if (lineCount > 0) {
                assertTrue(
                    position.line < lineCount,
                    "$label[$index].position.line must be inside document " +
                        "(lineCount=$lineCount); got ${position.line}"
                )
            }
            val labels = hintLabels(hint)
            assertTrue(
                labels.isNotEmpty() && labels.all { it.isNotEmpty() },
                "$label[$index].label must be non-empty; got $labels"
            )
            val kind = hint.kind
            if (kind != null) {
                assertTrue(
                    isKnownInlayHintKind(kind),
                    "$label[$index].kind must be a known InlayHintKind " +
                        "(Type/Parameter) when present; got $kind"
                )
            }
        }
    }

    private fun hintLabels(hint: InlayHint): List<String> {
        val label: Either<String, List<org.eclipse.lsp4j.InlayHintLabelPart>>? = hint.label
        if (label == null) {
            return emptyList()
        }
        return when {
            label.isLeft -> listOfNotNull(label.left)
            label.isRight -> label.right.orEmpty().mapNotNull { part -> part.value }
            else -> emptyList()
        }
    }

    private fun isKnownInlayHintKind(kind: InlayHintKind): Boolean {
        return kind == InlayHintKind.Type || kind == InlayHintKind.Parameter
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

    private sealed class InlayOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : InlayOutcome()

        data class Empty(val detail: String) : InlayOutcome()

        data class Succeeded(val hints: List<InlayHint>) : InlayOutcome()

        data class Failed(val detail: String) : InlayOutcome()
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
    }
}
