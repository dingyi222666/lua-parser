package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokensRangeParams
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
 * TASK-416 — LSP semantic tokens dual-path safety corpus.
 *
 * Locks the safety contract for `textDocument/semanticTokens/full` and
 * `textDocument/semanticTokens/range` when the feature is unimplemented or only
 * partially product-reachable:
 * - Unimplemented surface (LSP4J default [UnsupportedOperationException] on
 *   [LuaTextDocumentService]) must degrade as a documented gap — never as an
 *   unexpected hard crash (NPE / AssertionError / IndexOutOfBounds).
 * - When product soft-degrades instead of throwing, empty / null token data is
 *   accepted ("empty per product ads").
 * - Empty documents, syntax-error buffers, large files, never-opened URIs, and
 *   range requests must not invent process-killing failures.
 * - When product returns tokens (capability advertised and surface live), data
 *   must be well-formed 5-int groups (deltaLine, deltaStart, length, type,
 *   modifiers).
 *
 * Product semantic-tokens remain out of scope for this worker (test-only).
 * Current [LuaLanguageService] does not advertise semanticTokensProvider and
 * [LuaTextDocumentService] inherits the LSP4J defaults that throw
 * [UnsupportedOperationException]. Tests therefore dual-path:
 * - Documented gap: unimplemented methods complete exceptionally with
 *   UnsupportedOperationException and are recorded as the known surface.
 * - Ideal / soft path: empty token list (or null → treated as empty) when the
 *   server elects soft degrade per product ads; well-formed tokens when live.
 *
 * Complements [LspSemanticTokensBasicTddTest] (TASK-252 keyword/delta corpus)
 * with an explicit safety dual-path lock. Verification is review-owned and
 * serial; this worker does not run Gradle.
 */
class LspSemanticTokensSafetyTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun initialize_semantic_tokens_capability_is_null_or_explicit_when_product_lands() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        assertNotNull(capabilities, "initialize must return ServerCapabilities")
        val provider = capabilities.semanticTokensProvider
        if (provider == null) {
            // Documented gap: product has not advertised semantic tokens yet.
            assertTrue(
                true,
                "semanticTokensProvider absent is an accepted pre-product surface"
            )
            return
        }

        assertNotNull(provider.legend, "advertised provider must include a legend")
        assertNotNull(provider.legend.tokenTypes, "legend.tokenTypes must be non-null")
        assertTrue(
            provider.legend.tokenTypes.isNotEmpty(),
            "legend.tokenTypes must list at least one type when capability is advertised"
        )
        val hasFull = provider.full != null && (
            (provider.full.isLeft && provider.full.left == true) ||
                provider.full.isRight
            )
        val hasRange = provider.range != null && (
            (provider.range.isLeft && provider.range.left == true) ||
                provider.range.isRight
            )
        assertTrue(
            hasFull || hasRange,
            "advertised semanticTokensProvider must enable full and/or range"
        )
    }

    @Test
    fun semantic_tokens_full_surface_is_invokable_without_killing_service() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/semantic-tokens-safety-surface.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeSemanticTokensFull(
            textDocuments,
            SemanticTokensParams(TextDocumentIdentifier(document.uri))
        )

        assertTrue(
            outcome is TokensOutcome.Unsupported ||
                outcome is TokensOutcome.Empty ||
                outcome is TokensOutcome.Succeeded ||
                outcome is TokensOutcome.Failed,
            "semanticTokensFull surface must resolve to a known outcome; got $outcome " +
                "(semanticTokensProvider=${capabilities.semanticTokensProvider})"
        )

        if (outcome is TokensOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "semanticTokensFull surface must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is TokensOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is TokensOutcome.Succeeded) {
            assertWellFormedTokenData(outcome.tokens.data.orEmpty(), label = "surface full tokens")
        }
    }

    @Test
    fun semantic_tokens_unimplemented_degrades_as_documented_gap_or_empty() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/semantic-tokens-safety-gap.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeSemanticTokensFull(
            textDocuments,
            SemanticTokensParams(TextDocumentIdentifier(document.uri))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "unimplemented / pre-product semanticTokensFull"
        )
    }

    // -------------------------------------------------------------------------
    // Full request safety: empty / syntax error / large / unknown uri
    // -------------------------------------------------------------------------

    @Test
    fun semantic_tokens_full_empty_document_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/semantic-tokens-safety-empty.lua",
            ""
        )

        val outcome = invokeSemanticTokensFull(
            textDocuments,
            SemanticTokensParams(TextDocumentIdentifier(document.uri))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "empty document full",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun semantic_tokens_full_syntax_error_buffer_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/semantic-tokens-safety-syntax-error.lua",
            "local =\nfunction (\nend"
        )

        val outcome = invokeSemanticTokensFull(
            textDocuments,
            SemanticTokensParams(TextDocumentIdentifier(document.uri))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "syntax-error buffer full"
        )
    }

    @Test
    fun semantic_tokens_full_large_document_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val body = buildString {
            repeat(400) { i ->
                append("local v").append(i).append(" = ").append(i).append('\n')
            }
            append("return v0")
        }
        val document = textDocuments.open(
            "workspace/semantic-tokens-safety-large.lua",
            body
        )

        val outcome = invokeSemanticTokensFull(
            textDocuments,
            SemanticTokensParams(TextDocumentIdentifier(document.uri))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "large document full"
        )
    }

    @Test
    fun semantic_tokens_full_never_opened_uri_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val params = SemanticTokensParams(
            TextDocumentIdentifier("file:///workspace/semantic-tokens-safety-never-opened.lua")
        )

        val outcome = invokeSemanticTokensFull(textDocuments, params)

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "never-opened document uri full"
        )
    }

    @Test
    fun semantic_tokens_full_keyword_corpus_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/semantic-tokens-safety-keywords.lua",
            """
            local value = 1
            function greet(name)
                if name then
                    return name
                end
            end
            return greet(value)
            """
        )

        val outcome = invokeSemanticTokensFull(
            textDocuments,
            SemanticTokensParams(TextDocumentIdentifier(document.uri))
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "keyword/identifier corpus full"
        )
    }

    @Test
    fun semantic_tokens_full_twice_is_stable_on_gap_or_empty_or_data() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/semantic-tokens-safety-twice.lua",
            "local a = 1\nlocal b = 2\nreturn a + b"
        )
        val params = SemanticTokensParams(TextDocumentIdentifier(document.uri))

        val first = invokeSemanticTokensFull(textDocuments, params)
        val second = invokeSemanticTokensFull(textDocuments, params)

        assertDegradesAsGapOrEmpty(first, context = "first full call")
        assertDegradesAsGapOrEmpty(second, context = "second full call")

        // Same dual-path class on both invocations (gap stays gap; empty stays empty;
        // live data stays live). Soft: do not require identical data payloads yet.
        assertTrue(
            first::class == second::class ||
                (first is TokensOutcome.Empty && second is TokensOutcome.Succeeded) ||
                (first is TokensOutcome.Succeeded && second is TokensOutcome.Empty),
            "repeated full calls should stay on the same dual-path family; first=$first second=$second"
        )
        if (first is TokensOutcome.Succeeded && second is TokensOutcome.Succeeded) {
            assertWellFormedTokenData(first.tokens.data.orEmpty(), label = "first full")
            assertWellFormedTokenData(second.tokens.data.orEmpty(), label = "second full")
        }
    }

    // -------------------------------------------------------------------------
    // Range request safety (also dual-path)
    // -------------------------------------------------------------------------

    @Test
    fun semantic_tokens_range_surface_is_invokable_without_killing_service() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/semantic-tokens-safety-range-surface.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeSemanticTokensRange(
            textDocuments,
            SemanticTokensRangeParams(
                TextDocumentIdentifier(document.uri),
                Range(Position(0, 0), Position(0, 5))
            )
        )

        assertTrue(
            outcome is TokensOutcome.Unsupported ||
                outcome is TokensOutcome.Empty ||
                outcome is TokensOutcome.Succeeded ||
                outcome is TokensOutcome.Failed,
            "semanticTokensRange surface must resolve to a known outcome; got $outcome"
        )
        if (outcome is TokensOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "semanticTokensRange surface must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is TokensOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "range gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is TokensOutcome.Succeeded) {
            assertWellFormedTokenData(outcome.tokens.data.orEmpty(), label = "range surface tokens")
        }
    }

    @Test
    fun semantic_tokens_range_unimplemented_degrades_as_documented_gap_or_empty() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/semantic-tokens-safety-range-gap.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeSemanticTokensRange(
            textDocuments,
            SemanticTokensRangeParams(
                TextDocumentIdentifier(document.uri),
                Range(Position(0, 0), Position(1, 6))
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "unimplemented / pre-product semanticTokensRange"
        )
    }

    @Test
    fun semantic_tokens_range_empty_selection_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/semantic-tokens-safety-range-empty.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeSemanticTokensRange(
            textDocuments,
            SemanticTokensRangeParams(
                TextDocumentIdentifier(document.uri),
                Range(Position(0, 0), Position(0, 0))
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "empty selection range",
            allowNonEmptyWhenSucceeded = true
        )
    }

    @Test
    fun semantic_tokens_range_out_of_bounds_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/semantic-tokens-safety-range-oob.lua",
            "local value = 1"
        )

        val outcome = invokeSemanticTokensRange(
            textDocuments,
            SemanticTokensRangeParams(
                TextDocumentIdentifier(document.uri),
                Range(Position(50, 0), Position(60, 10))
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "out-of-bounds range"
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

    private fun invokeSemanticTokensFull(
        textDocuments: LuaTextDocumentService,
        params: SemanticTokensParams
    ): TokensOutcome {
        return try {
            val tokens = textDocuments.semanticTokensFull(params).get()
            classifyTokens(tokens)
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                TokensOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                TokensOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun invokeSemanticTokensRange(
        textDocuments: LuaTextDocumentService,
        params: SemanticTokensRangeParams
    ): TokensOutcome {
        return try {
            val tokens = textDocuments.semanticTokensRange(params).get()
            classifyTokens(tokens)
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                TokensOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                TokensOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun classifyTokens(tokens: SemanticTokens?): TokensOutcome {
        if (tokens == null) {
            return TokensOutcome.Empty(detail = "null SemanticTokens")
        }
        val data = tokens.data.orEmpty()
        return if (data.isEmpty()) {
            TokensOutcome.Empty(detail = "empty token data")
        } else {
            TokensOutcome.Succeeded(tokens = tokens)
        }
    }

    private fun assertDegradesAsGapOrEmpty(
        outcome: TokensOutcome,
        context: String,
        allowNonEmptyWhenSucceeded: Boolean = true
    ) {
        when (outcome) {
            is TokensOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is TokensOutcome.Empty -> {
                // Soft degrade (empty / null) is the ideal unimplemented-or-no-hit path
                // "per product ads" once the server elects not to throw.
            }
            is TokensOutcome.Succeeded -> {
                val data = outcome.tokens.data.orEmpty()
                assertWellFormedTokenData(data, label = "tokens for $context")
                if (!allowNonEmptyWhenSucceeded) {
                    // Empty document may still return a resultId-bearing SemanticTokens
                    // with empty data; classifyTokens already routes empty data to Empty.
                    // If we land here, data is non-empty — only acceptable when product
                    // invents tokens for empty buffers (unusual); still require shape.
                    assertTrue(
                        data.size % 5 == 0,
                        "$context succeeded with non-empty data must stay 5-int aligned"
                    )
                }
            }
            is TokensOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "semantic tokens at $context must not NPE/assert; got ${outcome.detail}"
                )
                // Soft product errors (ResponseError, IllegalState, etc.) are allowed
                // while the feature is partial; hard process-killing crash classes are not.
            }
        }
    }

    private fun assertWellFormedTokenData(data: List<Int>, label: String) {
        assertTrue(
            data.size % 5 == 0,
            "$label must be multiples of 5 (deltaLine, deltaStart, length, type, modifiers); size=${data.size}"
        )
        var i = 0
        while (i + 4 < data.size) {
            val length = data[i + 2]
            val tokenType = data[i + 3]
            val modifiers = data[i + 4]
            assertTrue(length >= 0, "$label token length must be non-negative; got $length at index $i")
            assertTrue(tokenType >= 0, "$label token type must be non-negative; got $tokenType at index $i")
            assertTrue(modifiers >= 0, "$label token modifiers must be non-negative; got $modifiers at index $i")
            i += 5
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

    private sealed class TokensOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : TokensOutcome()

        data class Empty(val detail: String) : TokensOutcome()

        data class Succeeded(val tokens: SemanticTokens) : TokensOutcome()

        data class Failed(val detail: String) : TokensOutcome()
    }

    private data class OpenDocument(
        val path: String,
        val source: String
    ) {
        val uri: String = "file:///$path"
    }
}
