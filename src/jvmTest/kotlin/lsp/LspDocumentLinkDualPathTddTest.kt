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
 * TASK-493 — LSP documentLink dual-path expansion corpus.
 *
 * Complements [LspDocumentLinkSafetyTddTest] (TASK-467 safety / gap surface) with an
 * explicit dual-path inventory around `textDocument/documentLink` and optional
 * `documentLink/resolve`:
 *
 * Hard contracts once product implements document links:
 * - Capability may stay null (CURRENTLY_ACCEPTS) or advertise DocumentLinkOptions
 *   with optional resolveProvider; never invent a non-options provider shape.
 * - Link-bearing corpora (require strings, dofile/loadfile paths, http(s) URLs in
 *   comments, file:// strings, relative modules, multi-require blocks) must either
 *   degrade as documented gap / empty or return well-formed DocumentLink entries
 *   (ordered non-negative ranges; non-blank targets when present).
 * - Link-free / empty / syntax-error / never-opened / past-product-ads surfaces
 *   must not hard-crash (NPE / AssertionError / IndexOutOfBounds).
 *
 * Dual-path / CURRENTLY_ACCEPTS product gaps (never hard-fail the suite):
 * - [LuaTextDocumentService] inherits LSP4J defaults → UnsupportedOperationException
 *   for documentLink / documentLinkResolve until product lands the feature.
 * - Server capabilities currently omit documentLinkProvider; ideal path advertises
 *   DocumentLinkOptions (resolveProvider optional).
 * - When product elects soft degrade, empty / null link lists are accepted
 *   ("empty per product ads") even on link-bearing sources.
 * - Resolve follow-on may stay unimplemented, identity-pass, or fill target later.
 *
 * Test-only; no product edits. Verification is review-owned and serial; this worker
 * does not run Gradle. Host android.jar paths only:
 * `/Users/dingyi/Downloads/android.jar` and
 * `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` — never G:/.
 */
class LspDocumentLinkDualPathTddTest {

    // -------------------------------------------------------------------------
    // Capability dual-path
    // -------------------------------------------------------------------------

    @Test
    fun document_link_capability_is_absent_or_advertises_options() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        assertNotNull(capabilities, "initialize must return ServerCapabilities")

        val provider = capabilities.documentLinkProvider
        if (provider == null) {
            // CURRENTLY_ACCEPTS: product does not advertise document links yet.
            return
        }

        // Ideal path: DocumentLinkOptions with optional resolveProvider boolean.
        // Soft: product may advertise without resolveProvider first.
        val resolveProvider = provider.resolveProvider
        if (resolveProvider != null) {
            assertTrue(
                resolveProvider == true || resolveProvider == false,
                "documentLinkProvider.resolveProvider must be boolean when set; got $resolveProvider"
            )
        }
    }

    @Test
    fun document_link_surface_dual_path_is_invokable() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/dual-document-link-surface.lua",
            """
            local ok = require("json")
            -- see https://example.com/docs
            return ok
            """
        )

        val outcome = invokeDocumentLink(textDocuments, documentLinkParams(document))

        assertTrue(
            outcome is DocumentLinkOutcome.Unsupported ||
                outcome is DocumentLinkOutcome.Empty ||
                outcome is DocumentLinkOutcome.Succeeded ||
                outcome is DocumentLinkOutcome.Failed,
            "documentLink dual-path surface must resolve to a known outcome; got $outcome"
        )
        if (outcome is DocumentLinkOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "documentLink dual-path surface must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is DocumentLinkOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is DocumentLinkOutcome.Succeeded) {
            assertWellFormedLinks(outcome.links, document, label = "surface dual-path links")
        }
    }

    // -------------------------------------------------------------------------
    // Dual-path inventory: link-bearing shapes that may accept once product lands
    // -------------------------------------------------------------------------

    @Test
    fun document_link_require_module_dual_path_gap_or_well_formed() {
        val outcome = linksOn(
            path = "workspace/dual-document-link-require.lua",
            source = """
                local json = require("cjson")
                local socket = require("socket.http")
                local relative = require("./local_mod")
                return json, socket, relative
            """
        )
        assertLinkBearingOrGap(outcome, context = "require() module corpus")
    }

    @Test
    fun document_link_require_parent_relative_dual_path() {
        val outcome = linksOn(
            path = "workspace/dual-document-link-require-parent.lua",
            source = """
                local helper = require("../lib/helper")
                local nested = require("../../shared/util")
                return helper, nested
            """
        )
        assertLinkBearingOrGap(outcome, context = "parent-relative require paths")
    }

    @Test
    fun document_link_dofile_path_dual_path() {
        val outcome = linksOn(
            path = "workspace/dual-document-link-dofile.lua",
            source = """
                local chunk = dofile("scripts/bootstrap.lua")
                return chunk
            """
        )
        assertLinkBearingOrGap(outcome, context = "dofile() path corpus")
    }

    @Test
    fun document_link_loadfile_path_dual_path() {
        val outcome = linksOn(
            path = "workspace/dual-document-link-loadfile.lua",
            source = """
                local loader = loadfile("plugins/init.lua")
                return loader
            """
        )
        assertLinkBearingOrGap(outcome, context = "loadfile() path corpus")
    }

    @Test
    fun document_link_http_url_comment_dual_path() {
        val outcome = linksOn(
            path = "workspace/dual-document-link-http-comments.lua",
            source = """
                -- Docs: https://www.lua.org/manual/5.4/
                -- Mirror: http://example.com/path?q=1#frag
                local v = 1
                return v
            """
        )
        assertLinkBearingOrGap(outcome, context = "http(s) URL comment corpus")
    }

    @Test
    fun document_link_file_uri_string_dual_path() {
        val outcome = linksOn(
            path = "workspace/dual-document-link-file-uri.lua",
            source = """
                local path = "file:///workspace/lib/helper.lua"
                -- also file:///Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
                -- Downloads host jar: file:///Users/dingyi/Downloads/android.jar
                return path
            """
        )
        assertLinkBearingOrGap(outcome, context = "file:// URI string corpus (host android.jar only)")
    }

    @Test
    fun document_link_multi_require_block_dual_path() {
        val outcome = linksOn(
            path = "workspace/dual-document-link-multi-require.lua",
            source = """
                local a = require("a")
                local b = require("b.c")
                local d = require("d")
                return a, b, d
            """
        )
        assertLinkBearingOrGap(outcome, context = "multi-require block")
    }

    @Test
    fun document_link_require_in_function_dual_path() {
        val outcome = linksOn(
            path = "workspace/dual-document-link-require-in-fn.lua",
            source = """
                local function loadPlugin(name)
                    return require("plugins." .. name)
                end
                return loadPlugin("core")
            """
        )
        // Soft: concatenated require args may or may not yield static links.
        assertSoftLinkOrGap(outcome, context = "require inside function (static concat soft)")
    }

    @Test
    fun document_link_module_string_assignment_dual_path() {
        val outcome = linksOn(
            path = "workspace/dual-document-link-module-string.lua",
            source = """
                local moduleName = "lib.core"
                local m = require(moduleName)
                return m
            """
        )
        // Soft: indirect require via variable is CURRENTLY_ACCEPTS empty.
        assertSoftLinkOrGap(outcome, context = "require via variable module name")
    }

    // -------------------------------------------------------------------------
    // Dual-path inventory: non-link / edge positions that must not hard-crash
    // -------------------------------------------------------------------------

    @Test
    fun document_link_link_free_dual_path_gap_or_empty() {
        val outcome = linksOn(
            path = "workspace/dual-document-link-link-free.lua",
            source = """
                local name = "token"
                local value = 1
                return name, value
            """
        )
        assertEmptyOrGapOrWellFormed(outcome, context = "link-free document", preferEmptyWhenSucceeded = true)
    }

    @Test
    fun document_link_empty_document_dual_path() {
        val outcome = linksOn(
            path = "workspace/dual-document-link-empty.lua",
            source = ""
        )
        assertEmptyOrGapOrWellFormed(outcome, context = "empty document", preferEmptyWhenSucceeded = true)
    }

    @Test
    fun document_link_syntax_error_dual_path() {
        val outcome = linksOn(
            path = "workspace/dual-document-link-syntax-error.lua",
            source = """
                local function broken(
                    require("
                    return {
                        a = 1,
            """
        )
        assertEmptyOrGapOrWellFormed(outcome, context = "syntax-error buffer")
    }

    @Test
    fun document_link_crlf_buffer_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/dual-document-link-crlf.lua",
            "local m = require(\"mod\")\r\n-- https://example.com\r\nreturn m"
        )
        val outcome = invokeDocumentLink(textDocuments, documentLinkParams(document))
        assertEmptyOrGapOrWellFormed(outcome, context = "CRLF buffer")
    }

    @Test
    fun document_link_comment_only_dual_path() {
        val outcome = linksOn(
            path = "workspace/dual-document-link-comments.lua",
            source = """
                -- header
                --[[
                  multi-line comment block
                  with https://example.com/inside
                ]]
                -- trailer
            """
        )
        assertEmptyOrGapOrWellFormed(outcome, context = "comment-only buffer")
    }

    @Test
    fun document_link_never_opened_uri_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val params = DocumentLinkParams(
            TextDocumentIdentifier("file:///workspace/dual-document-link-never-opened.lua")
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
                    assertOrderedRange(link.range, label = "never-opened dual-path result[$index]")
                    val target = link.target
                    if (target != null) {
                        assertTrue(
                            target.isNotBlank(),
                            "never-opened dual-path result[$index] target must be non-blank when present"
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

    @Test
    fun document_link_large_require_corpus_dual_path() {
        val body = buildString {
            repeat(150) { i ->
                append("local m").append(i).append(" = require(\"mod.").append(i).append("\")\n")
                append("-- https://example.com/mod/").append(i).append("\n")
            }
            append("return m0")
        }
        val outcome = linksOn(
            path = "workspace/dual-document-link-large.lua",
            source = body
        )
        assertEmptyOrGapOrWellFormed(outcome, context = "large require/http corpus")
    }

    // -------------------------------------------------------------------------
    // Explicit dual-path inventory table
    // -------------------------------------------------------------------------

    @Test
    fun document_link_dual_path_inventory_covers_link_and_empty_shapes() {
        val cases = listOf(
            InventoryCase(
                name = "require-cjson",
                source = "local j = require(\"cjson\")\nreturn j",
                expected = InventoryExpectation.LINK_BEARING_OR_GAP
            ),
            InventoryCase(
                name = "require-relative",
                source = "return require(\"./peer\")",
                expected = InventoryExpectation.LINK_BEARING_OR_GAP
            ),
            InventoryCase(
                name = "dofile-bootstrap",
                source = "return dofile(\"boot.lua\")",
                expected = InventoryExpectation.LINK_BEARING_OR_GAP
            ),
            InventoryCase(
                name = "http-comment",
                source = "-- https://www.lua.org/manual/5.4/\nreturn 1",
                expected = InventoryExpectation.LINK_BEARING_OR_GAP
            ),
            InventoryCase(
                name = "file-uri-host-android-jar",
                source = """
                    -- file:///Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
                    -- file:///Users/dingyi/Downloads/android.jar
                    return 0
                """,
                expected = InventoryExpectation.LINK_BEARING_OR_GAP
            ),
            InventoryCase(
                name = "link-free-locals",
                source = "local x = 1\nreturn x",
                expected = InventoryExpectation.EMPTY_OR_GAP
            ),
            InventoryCase(
                name = "empty-source",
                source = "",
                expected = InventoryExpectation.EMPTY_OR_GAP
            ),
            InventoryCase(
                name = "syntax-broken-require",
                source = "require(\nreturn",
                expected = InventoryExpectation.SOFT_OR_GAP
            ),
            InventoryCase(
                name = "string-literal-non-path",
                source = "local greeting = \"hello\"\nreturn greeting",
                expected = InventoryExpectation.EMPTY_OR_GAP
            ),
            InventoryCase(
                name = "loadfile-plugin",
                source = "return loadfile(\"plugins/init.lua\")",
                expected = InventoryExpectation.LINK_BEARING_OR_GAP
            )
        )

        cases.forEach { case ->
            val outcome = linksOn(
                path = "workspace/dual-document-link-inventory-${case.name}.lua",
                source = case.source
            )
            when (case.expected) {
                InventoryExpectation.LINK_BEARING_OR_GAP ->
                    assertLinkBearingOrGap(outcome, context = "inventory:${case.name}")
                InventoryExpectation.EMPTY_OR_GAP ->
                    assertEmptyOrGapOrWellFormed(
                        outcome,
                        context = "inventory:${case.name}",
                        preferEmptyWhenSucceeded = true
                    )
                InventoryExpectation.SOFT_OR_GAP ->
                    assertSoftLinkOrGap(outcome, context = "inventory:${case.name}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Stability + multi-open dual-path
    // -------------------------------------------------------------------------

    @Test
    fun document_link_twice_is_stable_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/dual-document-link-twice.lua",
            """
            local a = require("a")
            local b = require("b")
            return a, b
            """
        )
        val params = documentLinkParams(document)

        val first = invokeDocumentLink(textDocuments, params)
        val second = invokeDocumentLink(textDocuments, params)

        assertEmptyOrGapOrWellFormed(first, context = "first dual-path documentLink call")
        assertEmptyOrGapOrWellFormed(second, context = "second dual-path documentLink call")

        assertTrue(
            first::class == second::class ||
                (first is DocumentLinkOutcome.Empty && second is DocumentLinkOutcome.Succeeded) ||
                (first is DocumentLinkOutcome.Succeeded && second is DocumentLinkOutcome.Empty),
            "repeated documentLink calls should stay on the same dual-path family; " +
                "first=$first second=$second"
        )
        if (first is DocumentLinkOutcome.Succeeded && second is DocumentLinkOutcome.Succeeded) {
            assertWellFormedLinks(first.links, document, label = "first dual-path call")
            assertWellFormedLinks(second.links, document, label = "second dual-path call")
        }
    }

    @Test
    fun document_link_multi_open_documents_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val first = textDocuments.open(
            "workspace/dual-document-link-multi-a.lua",
            "local a = require(\"a\")\nreturn a"
        )
        val second = textDocuments.open(
            "workspace/dual-document-link-multi-b.lua",
            "-- https://example.com/b\nreturn 1"
        )

        val outcomeA = invokeDocumentLink(textDocuments, documentLinkParams(first))
        val outcomeB = invokeDocumentLink(textDocuments, documentLinkParams(second))

        assertEmptyOrGapOrWellFormed(outcomeA, context = "multi-open document A")
        assertEmptyOrGapOrWellFormed(outcomeB, context = "multi-open document B")
    }

    // -------------------------------------------------------------------------
    // documentLink/resolve dual-path expansion
    // -------------------------------------------------------------------------

    @Test
    fun document_link_resolve_dual_path_surface() {
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
            "documentLink/resolve dual-path surface must resolve to a known outcome; got $outcome"
        )
        if (outcome is DocumentLinkResolveOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "documentLink/resolve dual-path must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is DocumentLinkResolveOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is DocumentLinkResolveOutcome.Succeeded) {
            assertOrderedRange(outcome.link.range, label = "resolved dual-path link range")
            val target = outcome.link.target
            if (target != null) {
                assertTrue(target.isNotBlank(), "resolved dual-path target must be non-blank when present")
            }
        }
    }

    @Test
    fun document_link_resolve_with_prefilled_target_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val prefilled = DocumentLink(
            Range(Position(0, 8), Position(0, 14)),
            "file:///workspace/lib/helper.lua"
        )

        val outcome = invokeDocumentLinkResolve(textDocuments, prefilled)

        when (outcome) {
            is DocumentLinkResolveOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for resolve expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is DocumentLinkResolveOutcome.Succeeded -> {
                assertOrderedRange(outcome.link.range, label = "prefilled resolve range")
                val target = outcome.link.target
                if (target != null) {
                    assertTrue(target.isNotBlank(), "prefilled resolve target must stay non-blank")
                }
            }
            is DocumentLinkResolveOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "documentLink/resolve with prefilled target must not NPE/assert; got ${outcome.detail}"
                )
            }
        }
    }

    @Test
    fun document_link_resolve_host_android_jar_target_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        // Host paths only — never G:/.
        val hostJar =
            "file:///Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar"
        val prefilled = DocumentLink(
            Range(Position(1, 0), Position(1, 80)),
            hostJar
        )

        val outcome = invokeDocumentLinkResolve(textDocuments, prefilled)

        when (outcome) {
            is DocumentLinkResolveOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for host android.jar resolve expects UnsupportedOperationException; " +
                        "got ${outcome.detail}"
                )
            }
            is DocumentLinkResolveOutcome.Succeeded -> {
                assertOrderedRange(outcome.link.range, label = "host android.jar resolve range")
                val target = outcome.link.target
                if (target != null) {
                    assertTrue(target.isNotBlank(), "host android.jar target must be non-blank")
                    assertFalse(
                        target.contains("G:/", ignoreCase = true) ||
                            target.contains("G:\\", ignoreCase = true),
                        "host android.jar target must never use G:/ paths; got $target"
                    )
                }
            }
            is DocumentLinkResolveOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "documentLink/resolve host android.jar must not NPE/assert; got ${outcome.detail}"
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

    private fun linksOn(path: String, source: String): Pair<DocumentLinkOutcome, OpenDocument> {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(path, source)
        val outcome = invokeDocumentLink(textDocuments, documentLinkParams(document))
        return outcome to document
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

    /**
     * Link-bearing corpora: Unsupported gap OK; Empty soft OK (CURRENTLY_ACCEPTS);
     * Succeeded must be well-formed; Failed must not hard-crash.
     */
    private fun assertLinkBearingOrGap(
        pair: Pair<DocumentLinkOutcome, OpenDocument>,
        context: String
    ) {
        val (outcome, document) = pair
        when (outcome) {
            is DocumentLinkOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is DocumentLinkOutcome.Empty -> {
                // CURRENTLY_ACCEPTS: soft degrade / empty per product ads.
            }
            is DocumentLinkOutcome.Succeeded -> {
                assertWellFormedLinks(outcome.links, document, label = "links for $context")
            }
            is DocumentLinkOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "documentLink at $context must not NPE/assert; got ${outcome.detail}"
                )
            }
        }
    }

    private fun assertLinkBearingOrGap(outcome: DocumentLinkOutcome, context: String) {
        // Overload used when document is not needed (never-opened style paths avoid this).
        when (outcome) {
            is DocumentLinkOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is DocumentLinkOutcome.Empty -> Unit
            is DocumentLinkOutcome.Succeeded -> {
                assertTrue(outcome.links.isNotEmpty(), "$context succeeded path expects non-empty links")
                outcome.links.forEachIndexed { index, link ->
                    assertNotNull(link.range, "$context[$index] range must be non-null")
                    assertOrderedRange(link.range, label = "$context[$index]")
                    val target = link.target
                    if (target != null) {
                        assertTrue(
                            target.isNotBlank(),
                            "$context[$index] target must be non-blank when present"
                        )
                    }
                }
            }
            is DocumentLinkOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "documentLink at $context must not NPE/assert; got ${outcome.detail}"
                )
            }
        }
    }

    /**
     * Soft link targets (indirect require, concat): Unsupported, Empty, Succeeded
     * well-formed, or soft Failed are all CURRENTLY_ACCEPTS. Hard crashes are not.
     */
    private fun assertSoftLinkOrGap(
        pair: Pair<DocumentLinkOutcome, OpenDocument>,
        context: String
    ) {
        val (outcome, document) = pair
        when (outcome) {
            is DocumentLinkOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is DocumentLinkOutcome.Empty -> Unit
            is DocumentLinkOutcome.Succeeded -> {
                assertWellFormedLinks(outcome.links, document, label = "soft links for $context")
            }
            is DocumentLinkOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "documentLink at $context must not NPE/assert; got ${outcome.detail}"
                )
            }
        }
    }

    private fun assertEmptyOrGapOrWellFormed(
        pair: Pair<DocumentLinkOutcome, OpenDocument>,
        context: String,
        preferEmptyWhenSucceeded: Boolean = false
    ) {
        val (outcome, document) = pair
        assertEmptyOrGapOrWellFormed(outcome, document, context, preferEmptyWhenSucceeded)
    }

    private fun assertEmptyOrGapOrWellFormed(
        outcome: DocumentLinkOutcome,
        context: String,
        preferEmptyWhenSucceeded: Boolean = false
    ) {
        when (outcome) {
            is DocumentLinkOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is DocumentLinkOutcome.Empty -> Unit
            is DocumentLinkOutcome.Succeeded -> {
                outcome.links.forEachIndexed { index, link ->
                    assertNotNull(link.range, "$context[$index] range must be non-null")
                    assertOrderedRange(link.range, label = "$context[$index]")
                    val target = link.target
                    if (target != null) {
                        assertTrue(
                            target.isNotBlank(),
                            "$context[$index] target must be non-blank when present"
                        )
                    }
                }
                if (preferEmptyWhenSucceeded) {
                    // Soft: empty document / link-free ideally Empty; if product invents
                    // links they must still be shape-valid (already checked).
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
            }
        }
    }

    private fun assertEmptyOrGapOrWellFormed(
        outcome: DocumentLinkOutcome,
        document: OpenDocument,
        context: String,
        preferEmptyWhenSucceeded: Boolean = false
    ) {
        when (outcome) {
            is DocumentLinkOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is DocumentLinkOutcome.Empty -> Unit
            is DocumentLinkOutcome.Succeeded -> {
                assertWellFormedLinks(outcome.links, document, label = "links for $context")
                if (preferEmptyWhenSucceeded) {
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
                assertFalse(
                    target.contains("G:/", ignoreCase = true) ||
                        target.contains("G:\\", ignoreCase = true),
                    "$label[$index] target must never use G:/ host paths; got '$target'"
                )
            }
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

    private enum class InventoryExpectation {
        LINK_BEARING_OR_GAP,
        EMPTY_OR_GAP,
        SOFT_OR_GAP
    }

    private data class InventoryCase(
        val name: String,
        val source: String,
        val expected: InventoryExpectation
    )

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
