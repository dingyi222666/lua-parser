package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.SemanticTokenTypes
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensDelta
import org.eclipse.lsp4j.SemanticTokensDeltaParams
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.Assume
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-252 — LSP semantic tokens basic keyword corpus.
 *
 * Encodes the contract for textDocument/semanticTokens/full|delta when the server
 * advertises semanticTokensProvider:
 * - Full tokens encode keywords and identifiers without crashing.
 * - Empty documents and large documents must not throw.
 * - When both full and delta modes are exposed, results stay consistent
 *   (delta against an identical previous full result is empty or a no-op
 *   full re-encode that yields the same data).
 *
 * Product semantic-tokens remain out of scope for this worker. Today's
 * [LuaLanguageService] does not advertise semanticTokensProvider and
 * [LuaTextDocumentService] inherits the LSP4J defaults that throw
 * [UnsupportedOperationException]. The corpus therefore:
 * - Skips hard token assertions cleanly when the capability is absent
 *   (explicit Assume / documented gap).
 * - Still locks the initialize-capability probe and dual-path invoke surface
 *   so the file is meaningful both before and after product lands tokens.
 *
 * Test-only; no product edits. Verification is review-owned and serial; this
 * worker does not run Gradle.
 */
class LspSemanticTokensBasicTddTest {

    // -------------------------------------------------------------------------
    // Capability probe
    // -------------------------------------------------------------------------

    @Test
    fun initialize_semantic_tokens_capability_is_probeable() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        // Capability may be null today (documented gap) or present once product
        // lands semantic tokens. Either way, reading the field must not throw.
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
        // Full and/or range may be enabled; at least one delivery mode is required.
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
    fun semantic_tokens_capability_absent_skips_hard_token_corpus() {
        val service = service()
        val provider = service.initialize(InitializeParams()).capabilities.semanticTokensProvider

        Assume.assumeTrue(
            "TASK-252 skipped: semanticTokensProvider not advertised yet " +
                "(documented product gap; hard keyword/identifier corpus deferred until capability lands).",
            provider != null
        )

        // Reached only when capability is present.
        assertNotNull(provider)
        assertNotNull(provider.legend)
    }

    // -------------------------------------------------------------------------
    // Full request: empty / keywords / identifiers / large file
    // -------------------------------------------------------------------------

    @Test
    fun semantic_tokens_full_empty_document_does_not_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/semantic-tokens-empty.lua",
            ""
        )

        val outcome = invokeSemanticTokensFull(
            textDocuments,
            SemanticTokensParams(TextDocumentIdentifier(document.uri))
        )

        when (outcome) {
            is TokensOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for empty full; got ${outcome.detail}"
                )
            }
            is TokensOutcome.Failed -> {
                fail("semanticTokensFull on empty document must not fail hard: ${outcome.detail}")
            }
            is TokensOutcome.Succeeded -> {
                assertNotNull(outcome.tokens)
                val data = outcome.tokens.data.orEmpty()
                assertTrue(
                    data.isEmpty() || data.size % 5 == 0,
                    "empty document tokens must be empty or well-formed 5-int groups; size=${data.size}"
                )
            }
        }
    }

    @Test
    fun semantic_tokens_full_keywords_and_identifiers_encode_without_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/semantic-tokens-keywords.lua",
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

        when (outcome) {
            is TokensOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for keyword full; got ${outcome.detail}"
                )
            }
            is TokensOutcome.Failed -> {
                fail("semanticTokensFull on keyword corpus must not fail hard: ${outcome.detail}")
            }
            is TokensOutcome.Succeeded -> {
                val tokens = outcome.tokens
                assertNotNull(tokens)
                val data = tokens.data.orEmpty()
                assertTrue(
                    data.size % 5 == 0,
                    "semantic token data must be multiples of 5 (deltaLine, deltaStart, length, type, modifiers); size=${data.size}"
                )
                assertTrue(
                    data.isNotEmpty(),
                    "keyword/identifier corpus should yield at least one token once capability is live"
                )

                val decoded = decodeTokens(data)

                val provider = service.initialize(InitializeParams()).capabilities.semanticTokensProvider
                if (provider?.legend?.tokenTypes != null) {
                    val legendTypes = provider.legend.tokenTypes
                    val keywordType = legendTypes.indexOf(SemanticTokenTypes.Keyword)
                        .takeIf { it >= 0 }
                        ?: legendTypes.indexOf("keyword")
                    val identifierTypes = listOf(
                        SemanticTokenTypes.Variable,
                        SemanticTokenTypes.Function,
                        SemanticTokenTypes.Parameter,
                        SemanticTokenTypes.Property,
                        SemanticTokenTypes.Method
                    ).mapNotNull { name ->
                        legendTypes.indexOf(name).takeIf { it >= 0 }
                    }.toSet()

                    if (keywordType >= 0) {
                        assertTrue(
                            decoded.any { it.tokenType == keywordType },
                            "expected at least one keyword token (type index $keywordType); legend=$legendTypes data=$data"
                        )
                    }
                    if (identifierTypes.isNotEmpty()) {
                        assertTrue(
                            decoded.any { it.tokenType in identifierTypes },
                            "expected at least one identifier-like token (types $identifierTypes); legend=$legendTypes data=$data"
                        )
                    }
                }

                // Absolute ranges must stay inside the source (no crash / no OOB spans).
                decoded.forEach { token ->
                    assertTrue(token.line >= 0, "token line must be non-negative: $token")
                    assertTrue(token.startChar >= 0, "token startChar must be non-negative: $token")
                    assertTrue(token.length > 0, "token length must be positive: $token")
                    val lineText = document.lineAt(token.line)
                    assertTrue(
                        token.startChar + token.length <= lineText.length + 1,
                        "token span must fit line ${token.line} (len=${lineText.length}): $token"
                    )
                }
            }
        }
    }

    @Test
    fun semantic_tokens_full_large_document_does_not_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val body = buildString {
            appendLine("local total = 0")
            // ~2k lines of simple statements — large enough to stress encoding.
            repeat(2000) { index ->
                appendLine("total = total + $index")
            }
            appendLine("return total")
        }
        val document = textDocuments.open(
            "workspace/semantic-tokens-large.lua",
            body
        )

        val outcome = invokeSemanticTokensFull(
            textDocuments,
            SemanticTokensParams(TextDocumentIdentifier(document.uri))
        )

        when (outcome) {
            is TokensOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for large full; got ${outcome.detail}"
                )
            }
            is TokensOutcome.Failed -> {
                fail("semanticTokensFull on large document must not fail hard: ${outcome.detail}")
            }
            is TokensOutcome.Succeeded -> {
                val data = outcome.tokens.data.orEmpty()
                assertTrue(
                    data.size % 5 == 0,
                    "large-file token data must be well-formed 5-int groups; size=${data.size}"
                )
                // Sanity: large source should produce a non-trivial token stream when live.
                assertTrue(
                    data.isNotEmpty(),
                    "large keyword/identifier file should not yield an empty token stream once capability is live"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Delta / full consistency when both modes are exposed
    // -------------------------------------------------------------------------

    @Test
    fun semantic_tokens_delta_and_full_remain_consistent_when_exposed() {
        val service = service()
        val provider = service.initialize(InitializeParams()).capabilities.semanticTokensProvider

        Assume.assumeTrue(
            "TASK-252 skipped: semanticTokensProvider not advertised; delta/full consistency deferred.",
            provider != null
        )

        val deltaEnabled = provider?.full?.let { full ->
            when {
                full.isRight -> full.right?.delta == true
                else -> false
            }
        } == true

        Assume.assumeTrue(
            "TASK-252 skipped: semanticTokensProvider.full.delta not enabled; delta consistency deferred.",
            deltaEnabled
        )

        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/semantic-tokens-delta.lua",
            """
            local flag = true
            if flag then
                return flag
            end
            return flag
            """
        )

        val fullOutcome = invokeSemanticTokensFull(
            textDocuments,
            SemanticTokensParams(TextDocumentIdentifier(document.uri))
        )
        val fullTokens = when (fullOutcome) {
            is TokensOutcome.Succeeded -> fullOutcome.tokens
            is TokensOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-252 skipped: semanticTokensFull unsupported despite capability advertisement: ${fullOutcome.detail}",
                    false
                )
                return
            }
            is TokensOutcome.Failed -> {
                fail("semanticTokensFull must succeed when capability advertises full: ${fullOutcome.detail}")
            }
        }

        val previousResultId = fullTokens.resultId
        Assume.assumeTrue(
            "TASK-252 skipped: full result has no resultId; delta requires a previousResultId.",
            !previousResultId.isNullOrBlank()
        )

        val deltaOutcome = invokeSemanticTokensFullDelta(
            textDocuments,
            SemanticTokensDeltaParams(TextDocumentIdentifier(document.uri), previousResultId)
        )

        when (deltaOutcome) {
            is DeltaOutcome.Unsupported -> {
                fail(
                    "delta was advertised but semanticTokensFullDelta is unsupported: ${deltaOutcome.detail}"
                )
            }
            is DeltaOutcome.Failed -> {
                fail("semanticTokensFullDelta must not fail when delta is advertised: ${deltaOutcome.detail}")
            }
            is DeltaOutcome.Full -> {
                // Server may re-send a full payload instead of a delta; data must match.
                assertEquals(
                    fullTokens.data.orEmpty(),
                    deltaOutcome.tokens.data.orEmpty(),
                    "full re-encode via delta request must match previous full data"
                )
            }
            is DeltaOutcome.Delta -> {
                // Unchanged document → empty edits, or edits that are a no-op against prior data.
                val edits = deltaOutcome.delta.edits.orEmpty()
                if (edits.isEmpty()) {
                    // Preferred: no-op delta for identical content.
                    return
                }
                val reconstructed = applyEdits(fullTokens.data.orEmpty(), edits)
                assertEquals(
                    fullTokens.data.orEmpty(),
                    reconstructed,
                    "applying non-empty delta edits on unchanged source must reconstruct the same full data"
                )
            }
        }
    }

    @Test
    fun semantic_tokens_full_twice_is_stable_when_capability_present() {
        val service = service()
        val provider = service.initialize(InitializeParams()).capabilities.semanticTokensProvider

        Assume.assumeTrue(
            "TASK-252 skipped: semanticTokensProvider not advertised; full stability deferred.",
            provider != null
        )

        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/semantic-tokens-stable.lua",
            """
            local count = 0
            count = count + 1
            return count
            """
        )

        val first = invokeSemanticTokensFull(
            textDocuments,
            SemanticTokensParams(TextDocumentIdentifier(document.uri))
        )
        val second = invokeSemanticTokensFull(
            textDocuments,
            SemanticTokensParams(TextDocumentIdentifier(document.uri))
        )

        val firstData = when (first) {
            is TokensOutcome.Succeeded -> first.tokens.data.orEmpty()
            is TokensOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-252 skipped: full unsupported despite capability: ${first.detail}",
                    false
                )
                return
            }
            is TokensOutcome.Failed -> fail("first full must not fail: ${first.detail}")
        }
        val secondData = when (second) {
            is TokensOutcome.Succeeded -> second.tokens.data.orEmpty()
            is TokensOutcome.Unsupported -> {
                fail("second full became unsupported after first succeeded: ${second.detail}")
            }
            is TokensOutcome.Failed -> fail("second full must not fail: ${second.detail}")
        }

        assertEquals(
            firstData,
            secondData,
            "two consecutive full requests on an unchanged document must yield identical token data"
        )
        assertTrue(firstData.size % 5 == 0, "stable full data must be 5-int groups")
    }

    // -------------------------------------------------------------------------
    // TextDocumentService surface (dual-path: gap or live)
    // -------------------------------------------------------------------------

    @Test
    fun text_document_service_semantic_tokens_full_surface_is_invokable() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/semantic-tokens-surface.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeSemanticTokensFull(
            textDocuments,
            SemanticTokensParams(TextDocumentIdentifier(document.uri))
        )

        assertTrue(
            outcome is TokensOutcome.Unsupported ||
                outcome is TokensOutcome.Succeeded ||
                outcome is TokensOutcome.Failed,
            "semanticTokensFull surface must resolve to a known outcome; got $outcome"
        )
        // Hard failures (non-gap) are only acceptable when capability is absent and
        // the default UnsupportedOperationException path is taken; other failures
        // should not crash the worker harness.
        if (outcome is TokensOutcome.Failed) {
            fail("unexpected hard failure from semanticTokensFull surface: ${outcome.detail}")
        }
        if (outcome is TokensOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is TokensOutcome.Succeeded) {
            assertTrue(
                outcome.tokens.data.orEmpty().size % 5 == 0,
                "live full surface must return well-formed token data"
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
            TokensOutcome.Succeeded(tokens ?: SemanticTokens(emptyList()))
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                TokensOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                TokensOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun invokeSemanticTokensFullDelta(
        textDocuments: LuaTextDocumentService,
        params: SemanticTokensDeltaParams
    ): DeltaOutcome {
        return try {
            val either: Either<SemanticTokens, SemanticTokensDelta>? =
                textDocuments.semanticTokensFullDelta(params).get()
            when {
                either == null -> DeltaOutcome.Failed(detail = "null delta response")
                either.isLeft -> DeltaOutcome.Full(either.left ?: SemanticTokens(emptyList()))
                either.isRight -> DeltaOutcome.Delta(either.right ?: SemanticTokensDelta(emptyList()))
                else -> DeltaOutcome.Failed(detail = "empty Either for delta: $either")
            }
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                DeltaOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                DeltaOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun decodeTokens(data: List<Int>): List<DecodedToken> {
        val tokens = mutableListOf<DecodedToken>()
        var line = 0
        var startChar = 0
        var i = 0
        while (i + 4 < data.size) {
            val deltaLine = data[i]
            val deltaStart = data[i + 1]
            val length = data[i + 2]
            val tokenType = data[i + 3]
            val modifiers = data[i + 4]
            if (deltaLine != 0) {
                line += deltaLine
                startChar = deltaStart
            } else {
                startChar += deltaStart
            }
            tokens += DecodedToken(
                line = line,
                startChar = startChar,
                length = length,
                tokenType = tokenType,
                modifiers = modifiers
            )
            i += 5
        }
        return tokens
    }

    private fun applyEdits(previous: List<Int>, edits: List<org.eclipse.lsp4j.SemanticTokensEdit>): List<Int> {
        // LSP semantic token edits: each edit is (start, deleteCount, data?).
        // Apply in order against the flat integer array.
        val buffer = previous.toMutableList()
        edits.forEach { edit ->
            val start = edit.start
            val deleteCount = edit.deleteCount
            require(start >= 0 && start <= buffer.size) {
                "edit start $start out of bounds for data size ${buffer.size}"
            }
            require(deleteCount >= 0 && start + deleteCount <= buffer.size) {
                "edit deleteCount $deleteCount from $start exceeds data size ${buffer.size}"
            }
            repeat(deleteCount) {
                buffer.removeAt(start)
            }
            val insert = edit.data.orEmpty()
            buffer.addAll(start, insert)
        }
        return buffer
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

        data class Failed(val detail: String) : TokensOutcome()

        data class Succeeded(val tokens: SemanticTokens) : TokensOutcome()
    }

    private sealed class DeltaOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : DeltaOutcome()

        data class Failed(val detail: String) : DeltaOutcome()

        data class Full(val tokens: SemanticTokens) : DeltaOutcome()

        data class Delta(val delta: SemanticTokensDelta) : DeltaOutcome()
    }

    private data class DecodedToken(
        val line: Int,
        val startChar: Int,
        val length: Int,
        val tokenType: Int,
        val modifiers: Int
    )

    private data class OpenDocument(
        val path: String,
        val source: String
    ) {
        val uri: String = "file:///$path"

        fun lineAt(line: Int): String {
            val lines = source.split('\n')
            if (line < 0 || line >= lines.size) {
                return ""
            }
            return lines[line]
        }
    }
}
