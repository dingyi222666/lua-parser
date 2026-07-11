package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-449 corpus: `textDocument/didChange` diagnostics publish-on-change.
 *
 * Complements [LspLifecycleDiagnosticsTddTest] (single-doc basics) and
 * [LspDidOpenMultiDocDiagnosticsTddTest] (didOpen isolation) with a focused
 * publish-on-change matrix: full-document sync, range edits, multi-URI isolation,
 * version gating, sequential edit streams, and server-connected client path.
 *
 * Product surface exercised only via public APIs:
 * - [LuaTextDocumentService.didOpen] / [LuaTextDocumentService.didChange] /
 *   [LuaTextDocumentService.didClose]
 * - [LuaLanguageService.didOpen] / [LuaLanguageService.didChange] /
 *   [LuaLanguageService.diagnostics] / [LuaLanguageService.diagnosticsForUri] /
 *   [LuaLanguageService.documentSymbols]
 * - Connected [LuaLanguageServer] client publish path
 *
 * Dual-path notes (no invented APIs; red OK until review when product gaps exist):
 * - Version gate: stale/equal versions must not publish (hard lock).
 * - Null version: product currently accepts and applies ([CURRENTLY_ACCEPTS] path);
 *   ideal may reject or treat as "always apply" — both non-crash outcomes accepted
 *   when explicitly dual-pathed.
 * - Same-text version bump: product currently republishes even when text is
 *   unchanged ([CURRENTLY_ACCEPTS]); ideal may suppress no-op publishes.
 * - Unknown / closed documents: no publish (hard lock).
 * - Sibling isolation: changing one open URI must not republish siblings (hard lock).
 *
 * Host android.jar is not required for this pure-LSP parse-diagnostics corpus.
 * Policy remains Downloads + SDK android-35 only; never G:/.
 *
 * Test-only. Verification is review-owned (TASK-043). Workers must not run Gradle.
 */
class LspDiagnosticsPublishOnChangeTddTest {

    @Test
    fun full_sync_valid_to_invalid_publishes_non_empty_errors_for_that_uri_only() {
        val harness = harness()
        val uri = "file:///workspace/change-valid-to-invalid.lua"
        val sibling = "file:///workspace/change-valid-to-invalid-sibling.lua"

        harness.textDocuments.didOpen(openParams(uri, VALID_A, version = 1))
        harness.textDocuments.didOpen(openParams(sibling, VALID_B, version = 1))
        harness.published.clear()

        harness.textDocuments.didChange(fullChange(uri, version = 2, text = INVALID))

        assertEquals(listOf(uri), harness.published.map { it.uri })
        assertTrue(harness.published.single().diagnostics.isNotEmpty())
        assertTrue(
            harness.published.single().diagnostics.all { it.severity == DiagnosticSeverity.Error }
        )
        assertTrue(
            harness.languageService.diagnostics("workspace/change-valid-to-invalid-sibling.lua")
                .diagnostics.isEmpty(),
            "sibling must stay clean after invalid change on peer"
        )
    }

    @Test
    fun full_sync_invalid_to_valid_clears_diagnostics_and_refreshes_symbols() {
        val harness = harness()
        val uri = "file:///workspace/change-invalid-to-valid.lua"
        val path = "workspace/change-invalid-to-valid.lua"

        harness.textDocuments.didOpen(openParams(uri, INVALID, version = 1))
        assertTrue(lastPublishedFor(harness.published, uri).diagnostics.isNotEmpty())
        harness.published.clear()

        harness.textDocuments.didChange(fullChange(uri, version = 2, text = VALID_REPAIRED))

        assertEquals(listOf(uri), harness.published.map { it.uri })
        assertTrue(harness.published.single().diagnostics.isEmpty())
        assertTrue(
            harness.languageService.documentSymbols(path).any { it.name == "repaired" },
            "valid replacement must refresh document symbols"
        )
        assertDiagnosticsMatch(
            harness.published.single(),
            harness.languageService.diagnosticsForUri(uri)
        )
    }

    @Test
    fun sequential_full_sync_stream_publishes_one_payload_per_accepted_change() {
        val harness = harness()
        val uri = "file:///workspace/change-stream.lua"

        harness.textDocuments.didOpen(openParams(uri, VALID_A, version = 1))
        harness.published.clear()

        val sequence = listOf(
            2 to INVALID,
            3 to VALID_B,
            4 to "local =",
            5 to VALID_C
        )
        sequence.forEach { (version, text) ->
            harness.textDocuments.didChange(fullChange(uri, version = version, text = text))
        }

        assertEquals(
            listOf(uri, uri, uri, uri),
            harness.published.map { it.uri },
            "each accepted didChange must publish exactly once for the edited URI"
        )
        assertTrue(harness.published[0].diagnostics.isNotEmpty())
        assertTrue(harness.published[1].diagnostics.isEmpty())
        assertTrue(harness.published[2].diagnostics.isNotEmpty())
        assertTrue(harness.published[3].diagnostics.isEmpty())
    }

    @Test
    fun multi_doc_change_isolates_publish_and_query_snapshots_per_uri() {
        val harness = harness()
        val first = Doc("file:///workspace/change-multi-a.lua", "workspace/change-multi-a.lua", VALID_A)
        val second = Doc("file:///workspace/change-multi-b.lua", "workspace/change-multi-b.lua", VALID_B)
        val third = Doc("file:///workspace/change-multi-c.lua", "workspace/change-multi-c.lua", VALID_C)

        harness.textDocuments.didOpen(openParams(first.uri, first.source, version = 1))
        harness.textDocuments.didOpen(openParams(second.uri, second.source, version = 1))
        harness.textDocuments.didOpen(openParams(third.uri, third.source, version = 1))
        harness.published.clear()

        harness.textDocuments.didChange(fullChange(second.uri, version = 2, text = INVALID))

        assertEquals(listOf(second.uri), harness.published.map { it.uri })
        assertTrue(harness.published.single().diagnostics.isNotEmpty())

        assertTrue(harness.languageService.diagnostics(first.path).diagnostics.isEmpty())
        assertTrue(harness.languageService.diagnostics(third.path).diagnostics.isEmpty())
        assertTrue(harness.languageService.diagnostics(second.path).diagnostics.isNotEmpty())
        assertTrue(
            harness.languageService.documentSymbols(first.path).any { it.name == "valueA" }
        )
        assertTrue(
            harness.languageService.documentSymbols(third.path).any { it.name == "valueC" }
        )
    }

    @Test
    fun changing_second_then_first_keeps_independent_last_snapshots() {
        val harness = harness()
        val alpha = Doc("file:///workspace/change-indep-alpha.lua", "workspace/change-indep-alpha.lua", VALID_A)
        val beta = Doc("file:///workspace/change-indep-beta.lua", "workspace/change-indep-beta.lua", VALID_B)

        harness.textDocuments.didOpen(openParams(alpha.uri, alpha.source, version = 1))
        harness.textDocuments.didOpen(openParams(beta.uri, beta.source, version = 1))
        harness.published.clear()

        harness.textDocuments.didChange(fullChange(beta.uri, version = 2, text = INVALID))
        harness.textDocuments.didChange(fullChange(alpha.uri, version = 2, text = "local = 1"))

        assertEquals(listOf(beta.uri, alpha.uri), harness.published.map { it.uri })

        val byUri = lastPublishedByUri(harness.published)
        assertTrue(byUri.getValue(alpha.uri).diagnostics.isNotEmpty())
        assertTrue(byUri.getValue(beta.uri).diagnostics.isNotEmpty())
        assertDiagnosticsMatch(byUri.getValue(alpha.uri), harness.languageService.diagnosticsForUri(alpha.uri))
        assertDiagnosticsMatch(byUri.getValue(beta.uri), harness.languageService.diagnosticsForUri(beta.uri))
    }

    @Test
    fun stale_version_does_not_publish_or_mutate_diagnostics() {
        val harness = harness()
        val uri = "file:///workspace/change-stale-version.lua"
        val path = "workspace/change-stale-version.lua"

        harness.textDocuments.didOpen(openParams(uri, VALID_A, version = 5))
        harness.published.clear()

        harness.textDocuments.didChange(fullChange(uri, version = 4, text = INVALID))
        harness.textDocuments.didChange(fullChange(uri, version = 5, text = INVALID))

        assertTrue(
            harness.published.isEmpty(),
            "stale or equal versions must not publish diagnostics"
        )
        assertTrue(
            harness.languageService.diagnostics(path).diagnostics.isEmpty(),
            "stale change must not mutate the open document snapshot"
        )
        assertTrue(
            harness.languageService.documentSymbols(path).any { it.name == "valueA" },
            "symbols must remain from the last accepted open/change"
        )
    }

    @Test
    fun equal_version_after_accepted_change_is_ignored() {
        val harness = harness()
        val uri = "file:///workspace/change-equal-version.lua"

        harness.textDocuments.didOpen(openParams(uri, VALID_A, version = 1))
        harness.textDocuments.didChange(fullChange(uri, version = 2, text = VALID_B))
        harness.published.clear()

        harness.textDocuments.didChange(fullChange(uri, version = 2, text = INVALID))

        assertTrue(harness.published.isEmpty(), "equal version must not republish")
        assertTrue(
            harness.languageService.diagnosticsForUri(uri).diagnostics.isEmpty(),
            "equal-version invalid payload must not clobber accepted valid snapshot"
        )
    }

    @Test
    fun null_version_change_dual_path_does_not_crash() {
        val harness = harness()
        val uri = "file:///workspace/change-null-version.lua"
        val path = "workspace/change-null-version.lua"

        harness.textDocuments.didOpen(openParams(uri, VALID_A, version = 1))
        harness.published.clear()

        // Product currently applies null-version changes (CURRENTLY_ACCEPTS).
        // Ideal may reject; both must be non-crashing and never poison siblings.
        harness.textDocuments.didChange(fullChange(uri, version = null, text = INVALID))

        val publishedAfter = harness.published.toList()
        val queried = harness.languageService.diagnostics(path)

        val applied = publishedAfter.any { it.uri == uri && it.diagnostics.isNotEmpty() } ||
            queried.diagnostics.isNotEmpty()
        val rejectedQuietly = publishedAfter.isEmpty() && queried.diagnostics.isEmpty()

        assertTrue(
            applied || rejectedQuietly,
            "null-version didChange dual-path: CURRENTLY_ACCEPTS apply-or-publish, " +
                "or ideal quiet reject; got publishes=${publishedAfter.map { it.uri to it.diagnostics.size }} " +
                "queryEmpty=${queried.diagnostics.isEmpty()}"
        )
        // Hard lock: never crash / never invent second URI publishes.
        assertTrue(publishedAfter.all { it.uri == uri })
    }

    @Test
    fun range_edit_introducing_parse_error_publishes_diagnostics() {
        val harness = harness()
        val uri = "file:///workspace/change-range-error.lua"
        // Two valid locals; replace the entire second line with a broken assignment.
        val openText = "local first = 1\nlocal second = 2\nreturn first + second"
        harness.textDocuments.didOpen(openParams(uri, openText, version = 1))
        harness.published.clear()

        harness.textDocuments.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 2),
                listOf(
                    TextDocumentContentChangeEvent(
                        Range(Position(1, 0), Position(1, 16)),
                        16,
                        "local ="
                    )
                )
            )
        )

        assertEquals(listOf(uri), harness.published.map { it.uri })
        assertTrue(
            harness.published.single().diagnostics.isNotEmpty(),
            "range edit that breaks parse must publish non-empty diagnostics"
        )
        assertTrue(
            harness.published.single().diagnostics.all { it.severity == DiagnosticSeverity.Error }
        )
    }


    @Test
    fun range_edit_repairing_parse_error_clears_diagnostics() {
        val harness = harness()
        val uri = "file:///workspace/change-range-repair.lua"
        val broken = "local first =\nreturn first"
        harness.textDocuments.didOpen(openParams(uri, broken, version = 1))
        assertTrue(lastPublishedFor(harness.published, uri).diagnostics.isNotEmpty())
        harness.published.clear()

        // Insert " 1" after '=' on line 0: "local first = 1"
        harness.textDocuments.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 2),
                listOf(
                    TextDocumentContentChangeEvent(
                        Range(Position(0, 13), Position(0, 13)),
                        0,
                        " 1"
                    )
                )
            )
        )

        assertEquals(listOf(uri), harness.published.map { it.uri })
        assertTrue(
            harness.published.single().diagnostics.isEmpty(),
            "range repair of incomplete local must clear diagnostics"
        )
        assertTrue(
            harness.languageService.documentSymbols("workspace/change-range-repair.lua")
                .any { it.name == "first" }
        )
    }

    @Test
    fun multi_range_content_changes_in_one_notification_apply_in_order() {
        val harness = harness()
        val uri = "file:///workspace/change-multi-range.lua"
        val openText = "local alpha = 1\nlocal beta = 2\nreturn alpha + beta"
        harness.textDocuments.didOpen(openParams(uri, openText, version = 1))
        harness.published.clear()

        // Two sequential full-line range replacements inside one didChange.
        // First renames alpha→gamma on line 0; second renames beta→delta on line 1.
        // After both, symbols should be gamma/delta and diagnostics empty.
        harness.textDocuments.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 2),
                listOf(
                    TextDocumentContentChangeEvent(
                        Range(Position(0, 6), Position(0, 11)),
                        5,
                        "gamma"
                    ),
                    TextDocumentContentChangeEvent(
                        Range(Position(1, 6), Position(1, 10)),
                        4,
                        "delta"
                    )
                )
            )
        )

        assertEquals(listOf(uri), harness.published.map { it.uri })
        assertTrue(harness.published.single().diagnostics.isEmpty())

        val symbols = harness.languageService
            .documentSymbols("workspace/change-multi-range.lua")
            .map { it.name }
        assertTrue("gamma" in symbols, "first range rename must apply")
        assertTrue("delta" in symbols, "second range rename must apply")
        assertFalse("alpha" in symbols)
        assertFalse("beta" in symbols)
    }

    @Test
    fun multi_content_change_ending_invalid_publishes_errors_once() {
        val harness = harness()
        val uri = "file:///workspace/change-multi-to-invalid.lua"
        harness.textDocuments.didOpen(openParams(uri, VALID_A, version = 1))
        harness.published.clear()

        // First change is full valid rewrite; second full invalid rewrite — one notification.
        harness.textDocuments.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 2),
                listOf(
                    TextDocumentContentChangeEvent(VALID_B),
                    TextDocumentContentChangeEvent(INVALID)
                )
            )
        )

        assertEquals(listOf(uri), harness.published.map { it.uri })
        assertTrue(
            harness.published.single().diagnostics.isNotEmpty(),
            "final content of multi-change notification must drive diagnostics"
        )
    }

    @Test
    fun unknown_document_change_does_not_publish() {
        val harness = harness()
        harness.textDocuments.didChange(
            fullChange("file:///workspace/change-never-opened.lua", version = 1, text = INVALID)
        )
        assertTrue(
            harness.published.isEmpty(),
            "didChange for an unopened document must not publish diagnostics"
        )
    }

    @Test
    fun change_after_close_is_rejected_without_publish() {
        val harness = harness()
        val uri = "file:///workspace/change-after-close.lua"

        harness.textDocuments.didOpen(openParams(uri, VALID_A, version = 1))
        harness.textDocuments.didClose(closeParams(uri))
        harness.published.clear()

        harness.textDocuments.didChange(fullChange(uri, version = 2, text = INVALID))

        assertTrue(
            harness.published.isEmpty(),
            "didChange after didClose must not publish; document is no longer open"
        )
        assertTrue(
            harness.languageService.diagnostics("workspace/change-after-close.lua")
                .diagnostics.isEmpty()
        )
    }

    @Test
    fun same_text_version_bump_dual_path_republish_or_suppress() {
        val harness = harness()
        val uri = "file:///workspace/change-same-text.lua"

        harness.textDocuments.didOpen(openParams(uri, VALID_A, version = 1))
        harness.published.clear()

        harness.textDocuments.didChange(fullChange(uri, version = 2, text = VALID_A))

        // CURRENTLY_ACCEPTS: product always publishes after accepted version bump.
        // Ideal may suppress no-op text publishes. Either is dual-path safe.
        if (harness.published.isEmpty()) {
            // Ideal suppress path — still require query stays clean.
            assertTrue(harness.languageService.diagnosticsForUri(uri).diagnostics.isEmpty())
        } else {
            assertEquals(listOf(uri), harness.published.map { it.uri })
            assertTrue(
                harness.published.single().diagnostics.isEmpty(),
                "same-text republish must remain empty diagnostics"
            )
        }
    }

    @Test
    fun queried_diagnostics_match_last_published_after_each_change() {
        val harness = harness()
        val uri = "file:///workspace/change-query-match.lua"
        val path = "workspace/change-query-match.lua"

        harness.textDocuments.didOpen(openParams(uri, VALID_A, version = 1))

        val steps = listOf(
            2 to INVALID,
            3 to VALID_B,
            4 to "local value =\nreturn value",
            5 to VALID_C
        )
        steps.forEach { (version, text) ->
            harness.textDocuments.didChange(fullChange(uri, version = version, text = text))
            val lastPublished = lastPublishedFor(harness.published, uri)
            assertDiagnosticsMatch(lastPublished, harness.languageService.diagnostics(path))
            assertDiagnosticsMatch(lastPublished, harness.languageService.diagnosticsForUri(uri))
        }
    }

    @Test
    fun language_service_did_change_return_matches_text_document_publish() {
        // Dual drive: TextDocumentService publish path vs direct LanguageService return.
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { published += it }
        )
        val uri = "file:///workspace/change-return-match.lua"

        textDocuments.didOpen(openParams(uri, VALID_A, version = 1))
        published.clear()

        textDocuments.didChange(fullChange(uri, version = 2, text = INVALID))
        val publishedPayload = published.single()

        // Fresh service for direct return comparison with same open→change sequence.
        val direct = LuaLanguageService().also { it.initialize(InitializeParams()) }
        direct.didOpen(openParams(uri, VALID_A, version = 1))
        val returned = direct.didChange(fullChange(uri, version = 2, text = INVALID))

        assertEquals(uri, publishedPayload.uri)
        assertEquals(uri, returned.uri)
        assertTrue(publishedPayload.diagnostics.isNotEmpty())
        assertTrue(returned.diagnostics.isNotEmpty())
        assertEquals(
            publishedPayload.diagnostics.map { diagnosticKey(it) }.sorted(),
            returned.diagnostics.map { diagnosticKey(it) }.sorted(),
            "publish path and direct didChange return must agree on diagnostic multiset"
        )
    }

    @Test
    fun server_connected_client_receives_change_publishes_in_edit_order() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()

        val first = "file:///workspace/change-server-first.lua"
        val second = "file:///workspace/change-server-second.lua"

        server.textDocumentService.didOpen(openParams(first, VALID_A, version = 1))
        server.textDocumentService.didOpen(openParams(second, VALID_B, version = 1))
        client.published.clear()

        server.textDocumentService.didChange(fullChange(first, version = 2, text = INVALID))
        server.textDocumentService.didChange(fullChange(second, version = 2, text = "local ="))
        server.textDocumentService.didChange(fullChange(first, version = 3, text = VALID_C))

        assertEquals(listOf(first, second, first), client.published.map { it.uri })
        assertTrue(client.published[0].diagnostics.isNotEmpty())
        assertTrue(client.published[1].diagnostics.isNotEmpty())
        assertTrue(client.published[2].diagnostics.isEmpty())
        assertTrue(client.published[0].diagnostics.all { it.severity == DiagnosticSeverity.Error })
        assertTrue(client.published[1].diagnostics.all { it.severity == DiagnosticSeverity.Error })
    }

    @Test
    fun change_then_close_publishes_empty_clear_for_that_uri_only() {
        val harness = harness()
        val keep = Doc("file:///workspace/change-close-keep.lua", "workspace/change-close-keep.lua", VALID_A)
        val target = Doc("file:///workspace/change-close-target.lua", "workspace/change-close-target.lua", VALID_B)

        harness.textDocuments.didOpen(openParams(keep.uri, keep.source, version = 1))
        harness.textDocuments.didOpen(openParams(target.uri, target.source, version = 1))
        harness.textDocuments.didChange(fullChange(target.uri, version = 2, text = INVALID))
        assertTrue(lastPublishedFor(harness.published, target.uri).diagnostics.isNotEmpty())
        harness.published.clear()

        harness.textDocuments.didClose(closeParams(target.uri))

        assertEquals(listOf(target.uri), harness.published.map { it.uri })
        assertTrue(harness.published.single().diagnostics.isEmpty())
        assertTrue(harness.languageService.diagnostics(keep.path).diagnostics.isEmpty())
        assertTrue(harness.languageService.documentSymbols(keep.path).any { it.name == "valueA" })
        assertTrue(harness.languageService.documentSymbols(target.path).isEmpty())
    }

    @Test
    fun corpus_table_valid_invalid_repair_matrix_across_uris() {
        val harness = harness()

        data class Case(
            val uri: String,
            val path: String,
            val openSource: String,
            val changeVersion: Int,
            val changeSource: String,
            val expectEmptyAfterChange: Boolean,
            val expectedSymbol: String?
        )

        val cases = listOf(
            Case(
                "file:///workspace/change-table-0.lua",
                "workspace/change-table-0.lua",
                VALID_A,
                2,
                INVALID,
                false,
                null
            ),
            Case(
                "file:///workspace/change-table-1.lua",
                "workspace/change-table-1.lua",
                INVALID,
                2,
                VALID_B,
                true,
                "valueB"
            ),
            Case(
                "file:///workspace/change-table-2.lua",
                "workspace/change-table-2.lua",
                VALID_C,
                2,
                "local value =\nreturn value",
                false,
                null
            ),
            Case(
                "file:///workspace/change-table-3.lua",
                "workspace/change-table-3.lua",
                "local temp =",
                2,
                VALID_REPAIRED,
                true,
                "repaired"
            ),
            Case(
                "file:///workspace/change-table-4.lua",
                "workspace/change-table-4.lua",
                VALID_A,
                2,
                VALID_A,
                true,
                "valueA"
            )
        )

        cases.forEach { case ->
            harness.textDocuments.didOpen(openParams(case.uri, case.openSource, version = 1))
        }
        harness.published.clear()

        cases.forEach { case ->
            harness.textDocuments.didChange(
                fullChange(case.uri, version = case.changeVersion, text = case.changeSource)
            )
        }

        assertEquals(
            cases.map { it.uri },
            harness.published.map { it.uri },
            "table changes must publish once per URI in change order"
        )

        cases.forEachIndexed { index, case ->
            val payload = harness.published[index]
            assertEquals(case.uri, payload.uri)
            if (case.expectEmptyAfterChange) {
                assertTrue(payload.diagnostics.isEmpty(), "${case.uri} expected clean after change")
            } else {
                assertTrue(payload.diagnostics.isNotEmpty(), "${case.uri} expected diagnostics")
                assertTrue(payload.diagnostics.all { it.severity == DiagnosticSeverity.Error })
            }
            assertDiagnosticsMatch(payload, harness.languageService.diagnostics(case.path))
            case.expectedSymbol?.let { symbol ->
                assertTrue(
                    harness.languageService.documentSymbols(case.path).any { it.name == symbol },
                    "${case.uri} should expose symbol $symbol after change"
                )
            }
        }
    }

    @Test
    fun advancing_versions_out_of_order_across_documents_do_not_cross_gate() {
        val harness = harness()
        val left = "file:///workspace/change-gate-left.lua"
        val right = "file:///workspace/change-gate-right.lua"

        harness.textDocuments.didOpen(openParams(left, VALID_A, version = 10))
        harness.textDocuments.didOpen(openParams(right, VALID_B, version = 3))
        harness.published.clear()

        // left: stale relative to 10
        harness.textDocuments.didChange(fullChange(left, version = 9, text = INVALID))
        // right: accepted
        harness.textDocuments.didChange(fullChange(right, version = 4, text = INVALID))
        // left: accepted
        harness.textDocuments.didChange(fullChange(left, version = 11, text = INVALID))
        // right: equal to last accepted — ignored
        harness.textDocuments.didChange(fullChange(right, version = 4, text = VALID_C))

        assertEquals(listOf(right, left), harness.published.map { it.uri })
        assertTrue(harness.published[0].diagnostics.isNotEmpty())
        assertTrue(harness.published[1].diagnostics.isNotEmpty())
        assertTrue(
            harness.languageService.diagnostics("workspace/change-gate-right.lua")
                .diagnostics.isNotEmpty(),
            "equal-version repair on right must not apply after invalid accept"
        )
    }

    @Test
    fun empty_content_changes_list_dual_path_safe() {
        val harness = harness()
        val uri = "file:///workspace/change-empty-list.lua"
        val path = "workspace/change-empty-list.lua"

        harness.textDocuments.didOpen(openParams(uri, VALID_A, version = 1))
        harness.published.clear()

        harness.textDocuments.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 2),
                emptyList()
            )
        )

        // CURRENTLY_ACCEPTS: empty change list still advances version and republishes
        // the prior text (clean). Ideal may no-op entirely. Never invent errors.
        if (harness.published.isNotEmpty()) {
            assertEquals(listOf(uri), harness.published.map { it.uri })
            assertTrue(
                harness.published.single().diagnostics.isEmpty(),
                "empty contentChanges must not invent parse errors"
            )
        }
        assertTrue(harness.languageService.diagnostics(path).diagnostics.isEmpty())
        assertTrue(harness.languageService.documentSymbols(path).any { it.name == "valueA" })
    }

    // --- helpers -----------------------------------------------------------------

    private fun harness(): Harness {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { published += it }
        )
        return Harness(languageService, textDocuments, published)
    }

    private data class Harness(
        val languageService: LuaLanguageService,
        val textDocuments: LuaTextDocumentService,
        val published: MutableList<PublishDiagnosticsParams>
    )

    private data class Doc(
        val uri: String,
        val path: String,
        val source: String
    )

    private fun openParams(uri: String, text: String, version: Int = 1): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", version, text))
    }

    private fun fullChange(uri: String, version: Int?, text: String): DidChangeTextDocumentParams {
        return DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, version),
            listOf(TextDocumentContentChangeEvent(text))
        )
    }

    private fun closeParams(uri: String): DidCloseTextDocumentParams {
        return DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
    }

    private fun lastPublishedByUri(
        published: List<PublishDiagnosticsParams>
    ): Map<String, PublishDiagnosticsParams> {
        val result = linkedMapOf<String, PublishDiagnosticsParams>()
        published.forEach { result[it.uri] = it }
        return result
    }

    private fun lastPublishedFor(
        published: List<PublishDiagnosticsParams>,
        uri: String
    ): PublishDiagnosticsParams {
        val matching = published.filter { it.uri == uri }
        assertTrue(matching.isNotEmpty(), "expected at least one publish for $uri")
        return matching.last()
    }

    private fun assertDiagnosticsMatch(
        published: PublishDiagnosticsParams,
        queried: PublishDiagnosticsParams
    ) {
        assertEquals(published.uri, queried.uri)
        assertEquals(
            published.diagnostics.map { diagnosticKey(it) }.sorted(),
            queried.diagnostics.map { diagnosticKey(it) }.sorted(),
            "queried diagnostics must match last published snapshot for ${published.uri}"
        )
    }

    private fun diagnosticKey(diagnostic: Diagnostic): String {
        return listOf(
            diagnostic.severity?.toString().orEmpty(),
            diagnostic.message.orEmpty(),
            diagnostic.range?.start?.line?.toString().orEmpty(),
            diagnostic.range?.start?.character?.toString().orEmpty(),
            diagnostic.range?.end?.line?.toString().orEmpty(),
            diagnostic.range?.end?.character?.toString().orEmpty()
        ).joinToString("|")
    }

    private class RecordingLanguageClient : InvocationHandler {
        val published = mutableListOf<PublishDiagnosticsParams>()

        fun asClient(): LanguageClient {
            return Proxy.newProxyInstance(
                LanguageClient::class.java.classLoader,
                arrayOf(LanguageClient::class.java),
                this
            ) as LanguageClient
        }

        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            if (method.name == "publishDiagnostics") {
                published += args?.single() as PublishDiagnosticsParams
                return null
            }
            if (method.declaringClass == Object::class.java) {
                return when (method.name) {
                    "toString" -> "RecordingLanguageClient"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
            return when (method.returnType) {
                java.lang.Void.TYPE -> null
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                CompletableFuture::class.java -> CompletableFuture.completedFuture<Any?>(null)
                else -> null
            }
        }
    }

    companion object {
        private const val VALID_A = "local valueA = 1\nreturn valueA"
        private const val VALID_B = "local valueB = 2\nreturn valueB"
        private const val VALID_C = "local valueC = 3\nreturn valueC"
        private const val VALID_REPAIRED = "local repaired = 1\nreturn repaired"
        private const val INVALID = "local ="
    }
}
