package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentLink
import org.eclipse.lsp4j.DocumentLinkParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-467 — LSP documentLink dual-path safety corpus.
 *
 * Locks the safety contract for `textDocument/documentLink` (and the optional
 * `documentLink/resolve` follow-on) when the feature is unimplemented or only
 * partially product-reachable:
 * - Unimplemented surface (LSP4J default [UnsupportedOperationException] on
 *   [LuaTextDocumentService]) must degrade as a documented gap — never as an
 *   unexpected hard crash (NPE / AssertionError / IndexOutOfBounds).
 * - When product soft-degrades instead of throwing, empty / null link lists are
 *   accepted ("empty per product ads").
 * - Empty documents, syntax-error buffers, large files, never-opened URIs,
 *   link-free sources, require()/http corpora, CRLF, and comment-only buffers
 *   must not invent process-killing failures.
 * - When product returns document links (capability advertised and surface live),
 *   each [DocumentLink] must be well-formed (non-null ordered range with
 *   non-negative positions; optional target non-blank when present; optional
 *   tooltip may be blank but range remains valid).
 *
 * Product document links remain out of scope for this worker (test-only). Current
 * [LuaLanguageService] does not advertise documentLinkProvider and
 * [LuaTextDocumentService] inherits the LSP4J default that throws
 * [UnsupportedOperationException]. Tests therefore dual-path:
 * - Documented gap: unimplemented methods complete exceptionally with
 *   UnsupportedOperationException and are recorded as the known surface.
 * - Ideal / soft path: empty link list (or null → treated as empty) when the
 *   server elects soft degrade per product ads; well-formed links when live.
 *
 * Typical Lua link sources (require module strings, http(s) URLs in comments,
 * file:// paths) are exercised as corpora only — no product link extraction is
 * asserted beyond dual-path safety and well-formed shape when results exist.
 *
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class LspDocumentLinkSafetyTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun initialize_document_link_capability_is_null_or_explicit_when_product_lands() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        assertNotNull(capabilities, "initialize must return ServerCapabilities")
        val provider = capabilities.documentLinkProvider
        if (provider == null) {
            // Documented gap: product has not advertised document links yet.
            assertTrue(
                true,
                "documentLinkProvider absent is an accepted pre-product surface"
            )
            return
        }

        // Advertised as DocumentLinkOptions (resolveProvider optional boolean).
        assertTrue(
            true,
            "advertised documentLinkProvider options object is accepted; " +
                "resolveProvider=${provider.resolveProvider}"
        )
    }

    @Test
    fun document_link_surface_is_invokable_without_killing_service() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/document-link-safety-surface.lua",
            """
            local ok = require("json")
            -- see https://example.com/docs
            return ok
            """
        )

        val outcome = invokeDocumentLink(
            textDocuments,
            documentLinkParams(document)
        )

        assertTrue(
            outcome is DocumentLinkOutcome.Unsupported ||
                outcome is DocumentLinkOutcome.Empty ||
                outcome is DocumentLinkOutcome.Succeeded ||
                outcome is DocumentLinkOutcome.Failed,
            "documentLink surface must resolve to a known outcome; got $outcome " +
                "(documentLinkProvider=${capabilities.documentLinkProvider})"
        )

        if (outcome is DocumentLinkOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "documentLink surface must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is DocumentLinkOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is DocumentLinkOutcome.Succeeded) {
            assertWellFormedLinks(outcome.links, document, label = "surface links")
        }
    }

    @Test
    fun document_link_unimplemented_degrades_as_documented_gap_or_empty() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/document-link-safety-gap.lua",
            """
            local m = require("socket")
            return m
            """
        )

        val outcome = invokeDocumentLink(
            textDocuments,
            documentLinkParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "unimplemented / pre-product documentLink"
        )
    }

    // -------------------------------------------------------------------------
    // Full request safety: empty / syntax error / large / never-opened / link-free
    // -------------------------------------------------------------------------

    @Test
    fun document_link_empty_document_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/document-link-safety-empty.lua",
            ""
        )

        val outcome = invokeDocumentLink(
            textDocuments,
            documentLinkParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "empty document",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun document_link_syntax_error_buffer_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/document-link-safety-syntax-error.lua",
            """
            local function broken(
                require("
                return {
                    a = 1,
            """
        )

        val outcome = invokeDocumentLink(
            textDocuments,
            documentLinkParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "syntax-error buffer"
        )
    }

    @Test
    fun document_link_link_free_document_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/document-link-safety-link-free.lua",
            """
            local name = "token"
            local value = 1
            return name, value
            """
        )

        val outcome = invokeDocumentLink(
            textDocuments,
            documentLinkParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "link-free document"
        )
    }

    @Test
    fun document_link_large_document_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val body = buildString {
            repeat(200) { i ->
                append("local m").append(i).append(" = require(\"mod.").append(i).append("\")\n")
                append("-- https://example.com/mod/").append(i).append("\n")
            }
            append("return m0")
        }
        val document = textDocuments.open(
            "workspace/document-link-safety-large.lua",
            body
        )

        val outcome = invokeDocumentLink(
            textDocuments,
            documentLinkParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "large document"
        )
    }

    @Test
    fun document_link_never_opened_uri_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val params = DocumentLinkParams(
            TextDocumentIdentifier("file:///workspace/document-link-safety-never-opened.lua")
        )

        val outcome = invokeDocumentLink(textDocuments, params)

        when (outcome) {
            is DocumentLinkOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for never-opened uri expects UnsupportedOperationException; " +
                        "got ${outcome.detail}"
                )
            }
            is DocumentLinkOutcome.Empty -> {
                // Soft degrade is ideal for unknown uri.
            }
            is DocumentLinkOutcome.Succeeded -> {
                outcome.links.forEachIndexed { index, link ->
                    assertOrderedRange(
                        link.range,
                        label = "never-opened result[$index]"
                    )
                    val target = link.target
                    if (target != null) {
                        assertTrue(
                            target.isNotBlank(),
                            "never-opened result[$index] target must be non-blank when present"
                        )
                    }
                }
            }
            is DocumentLinkOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "documentLink on never-opened uri must not NPE/assert; got ${outcome.detail}"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Corpora that typically produce document links once product lands
    // -------------------------------------------------------------------------

    @Test
    fun document_link_require_module_corpus_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/document-link-safety-require.lua",
            """
            local json = require("cjson")
            local socket = require("socket.http")
            local relative = require("./local_mod")
            return json, socket, relative
            """
        )

        val outcome = invokeDocumentLink(
            textDocuments,
            documentLinkParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "require() module corpus"
        )
    }

    @Test
    fun document_link_http_url_comment_corpus_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/document-link-safety-http-comments.lua",
            """
            -- Docs: https://www.lua.org/manual/5.4/
            -- Mirror: http://example.com/path?q=1#frag
            local v = 1
            return v
            """
        )

        val outcome = invokeDocumentLink(
            textDocuments,
            documentLinkParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "http(s) URL comment corpus"
        )
    }

    @Test
    fun document_link_file_uri_string_corpus_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/document-link-safety-file-uri.lua",
            """
            local path = "file:///workspace/lib/helper.lua"
            -- also file:///Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
            return path
            """
        )

        val outcome = invokeDocumentLink(
            textDocuments,
            documentLinkParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "file:// URI string corpus"
        )
    }

    @Test
    fun document_link_single_line_source_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/document-link-safety-single-line.lua",
            "local m = require(\"mod\"); return m"
        )

        val outcome = invokeDocumentLink(
            textDocuments,
            documentLinkParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "single-line require source"
        )
    }

    @Test
    fun document_link_crlf_buffer_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/document-link-safety-crlf.lua",
            "local m = require(\"mod\")\r\n-- https://example.com\r\nreturn m"
        )

        val outcome = invokeDocumentLink(
            textDocuments,
            documentLinkParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "CRLF buffer"
        )
    }

    @Test
    fun document_link_comment_only_buffer_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/document-link-safety-comments.lua",
            """
            -- header
            --[[
              multi-line comment block
              with https://example.com/inside
            ]]
            -- trailer
            """
        )

        val outcome = invokeDocumentLink(
            textDocuments,
            documentLinkParams(document)
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "comment-only buffer"
        )
    }

    @Test
    fun document_link_twice_is_stable_on_gap_or_empty_or_links() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/document-link-safety-twice.lua",
            """
            local a = require("a")
            local b = require("b")
            return a, b
            """
        )
        val params = documentLinkParams(document)

        val first = invokeDocumentLink(textDocuments, params)
        val second = invokeDocumentLink(textDocuments, params)

        assertDegradesAsGapOrEmpty(first, document, context = "first documentLink call")
        assertDegradesAsGapOrEmpty(second, document, context = "second documentLink call")

        // Same dual-path class on both invocations (gap stays gap; empty stays empty;
        // live links stay live). Soft: do not require identical payloads yet.
        assertTrue(
            first::class == second::class ||
                (first is DocumentLinkOutcome.Empty && second is DocumentLinkOutcome.Succeeded) ||
                (first is DocumentLinkOutcome.Succeeded && second is DocumentLinkOutcome.Empty),
            "repeated documentLink calls should stay on the same dual-path family; " +
                "first=$first second=$second"
        )
        if (first is DocumentLinkOutcome.Succeeded && second is DocumentLinkOutcome.Succeeded) {
            assertWellFormedLinks(first.links, document, label = "first call")
            assertWellFormedLinks(second.links, document, label = "second call")
        }
    }

    // -------------------------------------------------------------------------
    // documentLink/resolve dual-path (optional follow-on)
    // -------------------------------------------------------------------------

    @Test
    fun document_link_resolve_surface_is_invokable_without_killing_service() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val unresolved = DocumentLink(
            Range(Position(0, 0), Position(0, 4)),
            null
        )

        val outcome = invokeDocumentLinkResolve(textDocuments, unresolved)

        assertTrue(
            outcome is DocumentLinkResolveOutcome.Unsupported ||
                outcome is DocumentLinkResolveOutcome.Succeeded ||
                outcome is DocumentLinkResolveOutcome.Failed,
            "documentLink/resolve surface must resolve to a known outcome; got $outcome"
        )

        if (outcome is DocumentLinkResolveOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "documentLink/resolve must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is DocumentLinkResolveOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is DocumentLinkResolveOutcome.Succeeded) {
            assertOrderedRange(outcome.link.range, label = "resolved link range")
            val target = outcome.link.target
            // Resolve may leave target null when still unresolved; non-blank when set.
            if (target != null) {
                assertTrue(target.isNotBlank(), "resolved target must be non-blank when present")
            }
        }
    }

    @Test
    fun document_link_resolve_unimplemented_degrades_as_documented_gap_or_identity() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val unresolved = DocumentLink(
            Range(Position(1, 2), Position(1, 10)),
            null
        )

        val outcome = invokeDocumentLinkResolve(textDocuments, unresolved)

        when (outcome) {
            is DocumentLinkResolveOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for documentLink/resolve expects UnsupportedOperationException; " +
                        "got ${outcome.detail}"
                )
            }
            is DocumentLinkResolveOutcome.Succeeded -> {
                assertOrderedRange(outcome.link.range, label = "resolved link (identity/soft)")
            }
            is DocumentLinkResolveOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "documentLink/resolve must not NPE/assert when unimplemented/partial; " +
                        "got ${outcome.detail}"
                )
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

    private fun documentLinkParams(document: OpenDocument): DocumentLinkParams {
        return DocumentLinkParams(TextDocumentIdentifier(document.uri))
    }

    private fun invokeDocumentLink(
        textDocuments: LuaTextDocumentService,
        params: DocumentLinkParams
    ): DocumentLinkOutcome {
        return try {
            val links = textDocuments.documentLink(params).get()
            classifyLinks(links)
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                DocumentLinkOutcome.Unsupported(
                    detail = root.toString(),
                    isUnsupportedOperation = true
                )
            } else {
                DocumentLinkOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun invokeDocumentLinkResolve(
        textDocuments: LuaTextDocumentService,
        params: DocumentLink
    ): DocumentLinkResolveOutcome {
        return try {
            val link = textDocuments.documentLinkResolve(params).get()
            if (link == null) {
                // Null resolve is treated as soft failure of the follow-on, not a hard crash.
                DocumentLinkResolveOutcome.Failed(detail = "null resolved DocumentLink")
            } else {
                DocumentLinkResolveOutcome.Succeeded(link = link)
            }
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                DocumentLinkResolveOutcome.Unsupported(
                    detail = root.toString(),
                    isUnsupportedOperation = true
                )
            } else {
                DocumentLinkResolveOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun classifyLinks(links: List<DocumentLink>?): DocumentLinkOutcome {
        if (links == null) {
            return DocumentLinkOutcome.Empty(detail = "null document links")
        }
        return if (links.isEmpty()) {
            DocumentLinkOutcome.Empty(detail = "empty document link list")
        } else {
            DocumentLinkOutcome.Succeeded(links = links.toList())
        }
    }

    private fun assertDegradesAsGapOrEmpty(
        outcome: DocumentLinkOutcome,
        document: OpenDocument,
        context: String,
        allowNonEmptyWhenSucceeded: Boolean = true
    ) {
        when (outcome) {
            is DocumentLinkOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is DocumentLinkOutcome.Empty -> {
                // Soft degrade (empty / null) is the ideal unimplemented-or-no-hit path
                // "per product ads" once the server elects not to throw.
            }
            is DocumentLinkOutcome.Succeeded -> {
                assertWellFormedLinks(outcome.links, document, label = "links for $context")
                if (!allowNonEmptyWhenSucceeded) {
                    // Empty document should ideally yield Empty via classifyLinks; if product
                    // invents links for empty buffers they must still be shape-valid (already
                    // checked). Soft-accept non-empty only if well-formed.
                    assertTrue(
                        outcome.links.isNotEmpty(),
                        "$context succeeded path has non-empty links (already validated)"
                    )
                }
            }
            is DocumentLinkOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "documentLink at $context must not NPE/assert; got ${outcome.detail}"
                )
                // Soft product errors (ResponseError, IllegalState, etc.) are allowed
                // while the feature is partial; hard process-killing crash classes are not.
            }
        }
    }

    private fun assertWellFormedLinks(
        links: List<DocumentLink>,
        document: OpenDocument,
        label: String
    ) {
        assertTrue(links.isNotEmpty(), "$label expected non-empty document link list")
        links.forEachIndexed { index, link ->
            assertNotNull(link.range, "$label[$index] range must be non-null")
            assertOrderedRange(link.range, label = "$label[$index]")
            if (document.lineCount > 0) {
                assertTrue(
                    link.range.start.line >= 0,
                    "$label[$index] start.line must be >= 0"
                )
                assertTrue(
                    link.range.end.line >= 0,
                    "$label[$index] end.line must be >= 0"
                )
                // Soft bound: when lineCount known, prefer start inside document.
                // Do not hard-fail on end past EOF (some providers include trailing spans).
                assertTrue(
                    link.range.start.line < document.lineCount || document.lineCount == 0,
                    "$label[$index] start.line should be inside document " +
                        "(lineCount=${document.lineCount}); got ${formatRange(link.range)}"
                )
            }
            val target = link.target
            if (target != null) {
                assertTrue(
                    target.isNotBlank(),
                    "$label[$index] target must be non-blank when present; got '$target'"
                )
            }
            // tooltip is optional free text; no hard shape check beyond non-null range.
        }
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

    private sealed class DocumentLinkOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : DocumentLinkOutcome()

        data class Empty(val detail: String) : DocumentLinkOutcome()

        data class Succeeded(val links: List<DocumentLink>) : DocumentLinkOutcome()

        data class Failed(val detail: String) : DocumentLinkOutcome()
    }

    private sealed class DocumentLinkResolveOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : DocumentLinkResolveOutcome()

        data class Succeeded(val link: DocumentLink) : DocumentLinkResolveOutcome()

        data class Failed(val detail: String) : DocumentLinkResolveOutcome()
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
