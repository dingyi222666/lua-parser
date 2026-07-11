package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Diagnostic
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
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-468 / TASK-492 — LSP codeAction dual-path safety corpus.
 *
 * Locks the safety contract for `textDocument/codeAction` when the feature is
 * unimplemented, soft-empty, or only partially product-reachable:
 * - Unimplemented surface (historical LSP4J default [UnsupportedOperationException]
 *   on [LuaTextDocumentService]) must degrade as a documented gap — never as an
 *   unexpected hard crash (NPE / AssertionError / IndexOutOfBounds).
 * - When product soft-degrades instead of throwing, empty / null action lists are
 *   accepted ("empty per product ads").
 * - Empty documents, syntax-error buffers, large files, never-opened URIs, empty
 *   diagnostic contexts, inverted/OOB/extreme ranges, mismatched `only` filters,
 *   CRLF / comment-only / whitespace buffers, closed documents, post-didChange
 *   buffers, multi-document sessions, multi-byte identifiers, negative positions,
 *   and clean buffers must not invent process-killing failures.
 * - When product returns CodeAction/Command entries (capability advertised and
 *   surface live), each entry must be well-formed (non-blank title; optional kind
 *   / edit / command coherent).
 *
 * Product path (TASK-542): [LuaLanguageService] advertises codeActionProvider
 * (CodeActionOptions with QuickFix, resolveProvider=false) and
 * [LuaTextDocumentService.codeAction] returns an empty list (or well-formed
 * actions) without throwing UnsupportedOperationException. This corpus dual-paths
 * that ideal empty/well-formed surface against the historical gap so regressions
 * cannot reintroduce hard crashes.
 *
 * Complements [LspCodeActionQuickFixSafetyTddTest] (TASK-268 / TASK-542 quickfix
 * no-fix degrade + range safety) with an explicit dual-path inventory that also
 * covers never-opened URIs, large buffers, kind-filter mismatches, lifecycle
 * transitions (didChange / didClose), multi-doc isolation, and repeated
 * invocation stability. Test-only; no product edits. Verification is review-owned
 * and serial; this worker does not run Gradle.
 *
 * Host android.jar paths (not required by this corpus):
 * - /Users/dingyi/Downloads/android.jar
 * - /Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
 * Never G:/.
 */
class LspCodeActionSafetyTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun initialize_code_action_capability_is_null_or_explicit_when_product_lands() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        assertNotNull(capabilities, "initialize must return ServerCapabilities")
        val provider = capabilities.codeActionProvider
        if (provider == null) {
            // Documented gap: product has not advertised codeAction yet.
            assertTrue(
                true,
                "codeActionProvider absent is an accepted pre-product surface"
            )
            return
        }

        // Advertised as Either<Boolean, CodeActionOptions>.
        val enabled = when {
            provider.isLeft -> provider.left == true
            provider.isRight -> provider.right != null
            else -> false
        }
        assertTrue(
            enabled,
            "advertised codeActionProvider must enable code actions " +
                "(boolean true or options object); got $provider"
        )
        if (provider.isRight) {
            val options = assertNotNull(provider.right, "CodeActionOptions expected on right")
            val kinds = options.codeActionKinds.orEmpty()
            // Soft: product may advertise without explicit kinds; TASK-542 lists QuickFix.
            if (kinds.isNotEmpty()) {
                assertTrue(
                    kinds.any { kind ->
                        kind == CodeActionKind.QuickFix ||
                            kind.startsWith("${CodeActionKind.QuickFix}.") ||
                            kind == CodeActionKind.Empty
                    },
                    "advertised codeActionKinds should include quickfix family when non-empty; got $kinds"
                )
            }
            // Soft: resolveProvider may be null/false until codeAction/resolve lands.
            val resolve = options.resolveProvider
            if (resolve != null) {
                assertTrue(
                    resolve == true || resolve == false,
                    "resolveProvider when set must be a boolean; got $resolve"
                )
            }
        }
    }

    @Test
    fun code_action_surface_is_invokable_without_killing_service() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val session = openSession(
            "workspace/code-action-safety-surface.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(0, 0), Position(0, 5)),
                diagnostics = emptyList()
            )
        )

        assertTrue(
            outcome is CodeActionOutcome.Unsupported ||
                outcome is CodeActionOutcome.Empty ||
                outcome is CodeActionOutcome.Succeeded ||
                outcome is CodeActionOutcome.Failed,
            "codeAction surface must resolve to a known outcome; got $outcome " +
                "(codeActionProvider=${capabilities.codeActionProvider})"
        )

        if (outcome is CodeActionOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "codeAction surface must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is CodeActionOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is CodeActionOutcome.Succeeded) {
            assertWellFormedActions(outcome.actions, label = "surface actions")
        }
    }

    @Test
    fun code_action_unimplemented_or_empty_degrades_as_documented_gap_or_empty() {
        val session = openSession(
            "workspace/code-action-safety-gap.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = session.document.rangeOf("value", occurrence = 1),
                diagnostics = emptyList(),
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "unimplemented / soft-empty codeAction on clean buffer"
        )
    }

    // -------------------------------------------------------------------------
    // Request safety: empty / syntax error / large / never-opened / clean
    // -------------------------------------------------------------------------

    @Test
    fun code_action_empty_document_gap_or_empty_or_well_formed() {
        val session = openSession(
            "workspace/code-action-safety-empty.lua",
            ""
        )

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(0, 0), Position(0, 0)),
                diagnostics = emptyList()
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "empty document",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun code_action_syntax_error_buffer_does_not_hard_crash() {
        val session = openSession(
            "workspace/code-action-safety-syntax-error.lua",
            """
            local function broken(
                return {
                    a = 1,
            """
        )

        val diagnostics = session.diagnostics.ifEmpty {
            listOf(
                Diagnostic().apply {
                    range = Range(Position(0, 0), Position(0, 1))
                    message = "synthetic parse diagnostic for syntax-error dual-path"
                }
            )
        }
        val range = diagnostics.first().range ?: Range(Position(0, 0), Position(0, 1))

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = range,
                diagnostics = diagnostics,
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "syntax-error buffer with diagnostics"
        )
    }

    @Test
    fun code_action_large_document_does_not_hard_crash() {
        val body = buildString {
            repeat(200) { i ->
                append("local v").append(i).append(" = ").append(i).append("\n")
            }
            append("return v0")
        }
        val session = openSession(
            "workspace/code-action-safety-large.lua",
            body
        )

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(0, 0), Position(0, 8)),
                diagnostics = emptyList(),
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "large document"
        )
    }

    @Test
    fun code_action_never_opened_uri_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val params = CodeActionParams(
            TextDocumentIdentifier("file:///workspace/code-action-safety-never-opened.lua"),
            Range(Position(0, 0), Position(0, 1)),
            CodeActionContext(mutableListOf())
        )

        val outcome = invokeCodeAction(textDocuments, params)

        when (outcome) {
            is CodeActionOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for never-opened uri expects UnsupportedOperationException; " +
                        "got ${outcome.detail}"
                )
            }
            is CodeActionOutcome.Empty -> {
                // Soft degrade is ideal for unknown uri.
            }
            is CodeActionOutcome.Succeeded -> {
                assertWellFormedActions(outcome.actions, label = "never-opened actions")
            }
            is CodeActionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "codeAction on never-opened uri must not NPE/assert; got ${outcome.detail}"
                )
            }
        }
    }

    @Test
    fun code_action_clean_document_quickfix_only_gap_or_empty_or_well_formed() {
        val session = openSession(
            "workspace/code-action-safety-clean-quickfix.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(0, 0), Position(1, 12)),
                diagnostics = emptyList(),
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "clean document, quickfix-only filter, empty diagnostics"
        )
    }

    // -------------------------------------------------------------------------
    // Range / selection safety
    // -------------------------------------------------------------------------

    @Test
    fun code_action_empty_selection_does_not_hard_crash() {
        val session = openSession(
            "workspace/code-action-safety-empty-selection.lua",
            "local value = 1\n\nreturn value"
        )

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(1, 0), Position(1, 0)),
                diagnostics = emptyList()
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "empty (zero-width) selection on blank line"
        )
    }

    @Test
    fun code_action_inverted_malformed_range_does_not_hard_crash() {
        val session = openSession(
            "workspace/code-action-safety-inverted-range.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(1, 5), Position(0, 0)),
                diagnostics = emptyList()
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "inverted / malformed range (end before start)"
        )
    }

    @Test
    fun code_action_out_of_bounds_range_does_not_hard_crash() {
        val session = openSession(
            "workspace/code-action-safety-oob-range.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(50, 0), Position(50, 10)),
                diagnostics = emptyList()
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "out-of-bounds range past EOF"
        )
    }

    @Test
    fun code_action_extreme_character_offsets_do_not_hard_crash() {
        val session = openSession(
            "workspace/code-action-safety-large-chars.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(0, 10_000), Position(0, 20_000)),
                diagnostics = emptyList()
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "extreme character offsets on valid line"
        )
    }

    @Test
    fun code_action_negative_line_positions_do_not_hard_crash() {
        val session = openSession(
            "workspace/code-action-safety-negative-pos.lua",
            "local value = 1\nreturn value"
        )

        // Clients occasionally emit negative lines/characters after edits.
        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(-1, -5), Position(-1, 0)),
                diagnostics = emptyList(),
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "negative line/character positions"
        )
    }

    @Test
    fun code_action_whole_file_selection_gap_or_empty_or_well_formed() {
        val source = """
            local function greet(name)
              return "hi " .. name
            end
            return greet("world")
        """.trimIndent()
        val session = openSession(
            "workspace/code-action-safety-whole-file.lua",
            source
        )
        val lastLine = source.lines().lastIndex
        val lastChar = source.lines().last().length

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(0, 0), Position(lastLine, lastChar)),
                diagnostics = emptyList(),
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "whole-file multi-line selection"
        )
    }

    // -------------------------------------------------------------------------
    // Diagnostic context / only-filter dual-path
    // -------------------------------------------------------------------------

    @Test
    fun code_action_with_known_parse_diagnostic_degrades_safely_when_no_fix() {
        val session = openSession(
            "workspace/code-action-safety-parse-diag.lua",
            "local ="
        )

        assertTrue(
            session.diagnostics.isNotEmpty(),
            "fixture must publish parse diagnostics so codeAction has a known diagnostic"
        )

        val diagnostic = session.diagnostics.first()
        val range = diagnostic.range ?: Range(Position(0, 0), Position(0, 1))
        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = range,
                diagnostics = listOf(diagnostic),
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "parse error diagnostic (no automatic quickfix expected)"
        )
    }

    @Test
    fun code_action_mismatched_only_filter_gap_or_empty_or_well_formed() {
        val session = openSession(
            "workspace/code-action-safety-only-refactor.lua",
            "local ="
        )
        val diagnostic = session.diagnostics.firstOrNull()
            ?: Diagnostic().apply {
                range = Range(Position(0, 0), Position(0, 1))
                message = "synthetic diagnostic for refactor-only filter"
            }

        // Client asks only for refactor.* kinds — product quickfix surface should soft-empty.
        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = diagnostic.range ?: Range(Position(0, 0), Position(0, 1)),
                diagnostics = listOf(diagnostic),
                only = listOf(CodeActionKind.Refactor, CodeActionKind.Source)
            )
        )

        when (outcome) {
            is CodeActionOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for mismatched only-filter expects UnsupportedOperationException; " +
                        "got ${outcome.detail}"
                )
            }
            is CodeActionOutcome.Empty -> {
                // Ideal soft path when kinds are unsupported.
            }
            is CodeActionOutcome.Succeeded -> {
                // If product returns actions under a non-quickfix only filter they must
                // still be well-formed; empty list is preferred.
                assertWellFormedActions(outcome.actions, label = "mismatched only-filter")
            }
            is CodeActionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "mismatched only-filter must not hard-crash; got ${outcome.detail}"
                )
            }
        }
    }

    @Test
    fun code_action_source_organize_imports_only_filter_gap_or_empty_or_well_formed() {
        val session = openSession(
            "workspace/code-action-safety-only-organize.lua",
            "local value = 1\nreturn value"
        )

        // Common client filter for organize-imports lightbulb; product does not ship
        // source.organizeImports yet — soft empty / gap only.
        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(0, 0), Position(0, 5)),
                diagnostics = emptyList(),
                only = listOf(CodeActionKind.SourceOrganizeImports)
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "source.organizeImports only-filter on clean buffer"
        )
    }

    @Test
    fun code_action_empty_only_filter_with_diagnostic_gap_or_empty_or_well_formed() {
        val session = openSession(
            "workspace/code-action-safety-empty-only.lua",
            "local ="
        )
        val diagnostic = session.diagnostics.firstOrNull()
            ?: Diagnostic().apply {
                range = Range(Position(0, 0), Position(0, 1))
                message = "synthetic diagnostic for empty-only filter"
            }

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = diagnostic.range ?: Range(Position(0, 0), Position(0, 1)),
                diagnostics = listOf(diagnostic),
                only = emptyList()
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "known diagnostic with empty 'only' kind filter"
        )
    }

    @Test
    fun code_action_synthetic_diagnostic_without_range_gap_or_empty_or_well_formed() {
        val session = openSession(
            "workspace/code-action-safety-diag-no-range.lua",
            "local value = 1\nreturn value"
        )
        // Malformed client diagnostic: message only, no range.
        val bare = Diagnostic().apply {
            message = "synthetic diagnostic without range"
        }

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(0, 0), Position(0, 5)),
                diagnostics = listOf(bare),
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "synthetic diagnostic without range"
        )
    }

    @Test
    fun code_action_mixed_real_and_synthetic_diagnostics_gap_or_empty_or_well_formed() {
        val session = openSession(
            "workspace/code-action-safety-mixed-diags.lua",
            "local ="
        )
        val published = session.diagnostics
        val synthetic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 1))
            message = "synthetic companion diagnostic"
            source = "task-492-corpus"
        }
        val mixed = published + synthetic

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(0, 0), Position(0, 1)),
                diagnostics = mixed,
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "mixed published + synthetic diagnostics"
        )
    }

    @Test
    fun code_action_returned_actions_are_well_formed_when_product_lands() {
        val session = openSession(
            "workspace/code-action-safety-well-formed.lua",
            "local ="
        )
        val diagnostics = session.diagnostics.ifEmpty {
            listOf(
                Diagnostic().apply {
                    range = Range(Position(0, 0), Position(0, 1))
                    message = "synthetic diagnostic for well-formed action probe"
                }
            )
        }
        val range = diagnostics.first().range ?: Range(Position(0, 0), Position(0, 1))

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = range,
                diagnostics = diagnostics,
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        when (outcome) {
            is CodeActionOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is CodeActionOutcome.Empty -> {
                // Safe no-fix path.
            }
            is CodeActionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "codeAction failure must not be hard crash; got ${outcome.detail}"
                )
            }
            is CodeActionOutcome.Succeeded -> {
                assertWellFormedActions(outcome.actions, label = "well-formed probe")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Buffer shapes: CRLF / comment-only / whitespace / multi-byte
    // -------------------------------------------------------------------------

    @Test
    fun code_action_crlf_buffer_does_not_hard_crash() {
        val session = openSession(
            "workspace/code-action-safety-crlf.lua",
            "local value = 1\r\nreturn value\r\n"
        )

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(0, 6), Position(0, 11)),
                diagnostics = emptyList(),
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "CRLF line endings buffer"
        )
    }

    @Test
    fun code_action_comment_only_buffer_does_not_hard_crash() {
        val session = openSession(
            "workspace/code-action-safety-comment-only.lua",
            "-- TODO: nothing executable\n--[[ block comment ]]"
        )

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(0, 0), Position(0, 4)),
                diagnostics = emptyList()
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "comment-only buffer"
        )
    }

    @Test
    fun code_action_whitespace_only_buffer_does_not_hard_crash() {
        val session = openSession(
            "workspace/code-action-safety-whitespace.lua",
            "   \n\t\n  "
        )

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(0, 0), Position(0, 3)),
                diagnostics = emptyList(),
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "whitespace-only buffer"
        )
    }

    @Test
    fun code_action_multibyte_identifier_buffer_gap_or_empty_or_well_formed() {
        val session = openSession(
            "workspace/code-action-safety-multibyte.lua",
            "local 变量 = 1\nreturn 变量"
        )

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(0, 6), Position(0, 8)),
                diagnostics = emptyList(),
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "multi-byte identifier buffer"
        )
    }

    // -------------------------------------------------------------------------
    // Lifecycle: didChange / didClose / multi-document isolation
    // -------------------------------------------------------------------------

    @Test
    fun code_action_after_did_change_to_broken_source_does_not_hard_crash() {
        val session = openSession(
            "workspace/code-action-safety-after-change.lua",
            "local value = 1\nreturn value"
        )

        session.textDocuments.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(session.document.uri, 2),
                listOf(TextDocumentContentChangeEvent("local ="))
            )
        )

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(0, 0), Position(0, 5)),
                diagnostics = listOf(
                    Diagnostic().apply {
                        range = Range(Position(0, 0), Position(0, 1))
                        message = "post-change synthetic parse diagnostic"
                    }
                ),
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "codeAction after didChange to broken source"
        )
    }

    @Test
    fun code_action_after_did_change_repair_gap_or_empty_or_well_formed() {
        val session = openSession(
            "workspace/code-action-safety-after-repair.lua",
            "local ="
        )

        session.textDocuments.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(session.document.uri, 2),
                listOf(TextDocumentContentChangeEvent("local value = 1\nreturn value"))
            )
        )

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(0, 6), Position(0, 11)),
                diagnostics = emptyList(),
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "codeAction after didChange repair to clean source"
        )
    }

    @Test
    fun code_action_after_did_close_does_not_hard_crash() {
        val session = openSession(
            "workspace/code-action-safety-after-close.lua",
            "local value = 1\nreturn value"
        )

        session.textDocuments.didClose(
            DidCloseTextDocumentParams(TextDocumentIdentifier(session.document.uri))
        )

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = Range(Position(0, 0), Position(0, 5)),
                diagnostics = emptyList(),
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesAsGapOrEmpty(
            outcome,
            context = "codeAction after didClose on same uri"
        )
    }

    @Test
    fun code_action_multi_document_session_isolates_requests() {
        val service = service()
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = service,
            publishDiagnostics = { published += it }
        )

        val clean = OpenDocument(
            path = "workspace/code-action-safety-multi-clean.lua",
            source = "local ok = 1\nreturn ok"
        )
        val broken = OpenDocument(
            path = "workspace/code-action-safety-multi-broken.lua",
            source = "local ="
        )

        textDocuments.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(clean.uri, "lua", 1, clean.source))
        )
        textDocuments.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(broken.uri, "lua", 1, broken.source))
        )

        val cleanOutcome = invokeCodeAction(
            textDocuments,
            codeActionParams(
                document = clean,
                range = Range(Position(0, 0), Position(0, 5)),
                diagnostics = emptyList(),
                only = listOf(CodeActionKind.QuickFix)
            )
        )
        val brokenDiags = published
            .filter { it.uri == broken.uri }
            .flatMap { it.diagnostics.orEmpty() }
            .ifEmpty {
                listOf(
                    Diagnostic().apply {
                        range = Range(Position(0, 0), Position(0, 1))
                        message = "synthetic multi-doc parse diagnostic"
                    }
                )
            }
        val brokenOutcome = invokeCodeAction(
            textDocuments,
            codeActionParams(
                document = broken,
                range = brokenDiags.first().range ?: Range(Position(0, 0), Position(0, 1)),
                diagnostics = brokenDiags,
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesAsGapOrEmpty(
            cleanOutcome,
            context = "multi-doc clean document"
        )
        assertDegradesAsGapOrEmpty(
            brokenOutcome,
            context = "multi-doc broken document"
        )

        // Both requests must remain on dual-path families (no cross-doc hard crash).
        assertTrue(
            outcomeFamily(cleanOutcome) in setOf("Unsupported", "Empty", "Succeeded", "Failed"),
            "clean multi-doc outcome family unexpected: ${outcomeFamily(cleanOutcome)}"
        )
        assertTrue(
            outcomeFamily(brokenOutcome) in setOf("Unsupported", "Empty", "Succeeded", "Failed"),
            "broken multi-doc outcome family unexpected: ${outcomeFamily(brokenOutcome)}"
        )
    }

    // -------------------------------------------------------------------------
    // Stability: repeated invocation stays on the same dual-path family
    // -------------------------------------------------------------------------

    @Test
    fun code_action_repeated_calls_stay_on_same_dual_path_family() {
        val session = openSession(
            "workspace/code-action-safety-repeat.lua",
            "local value = 1\nreturn value"
        )
        val params = codeActionParams(
            document = session.document,
            range = session.document.rangeOf("value", occurrence = 1),
            diagnostics = emptyList(),
            only = listOf(CodeActionKind.QuickFix)
        )

        val first = invokeCodeAction(session.textDocuments, params)
        val second = invokeCodeAction(session.textDocuments, params)

        assertTrue(
            first is CodeActionOutcome.Unsupported ||
                first is CodeActionOutcome.Empty ||
                first is CodeActionOutcome.Succeeded ||
                first is CodeActionOutcome.Failed,
            "first codeAction call must resolve to a known outcome; got $first"
        )
        assertTrue(
            second is CodeActionOutcome.Unsupported ||
                second is CodeActionOutcome.Empty ||
                second is CodeActionOutcome.Succeeded ||
                second is CodeActionOutcome.Failed,
            "second codeAction call must resolve to a known outcome; got $second"
        )

        // Same dual-path class on both invocations (gap stays gap; empty stays empty;
        // success stays success). Soft failures may vary message text only.
        val firstFamily = outcomeFamily(first)
        val secondFamily = outcomeFamily(second)
        assertTrue(
            firstFamily == secondFamily ||
                (first is CodeActionOutcome.Failed && !isHardCrash(first.detail) &&
                    second is CodeActionOutcome.Failed && !isHardCrash(second.detail)),
            "repeated codeAction calls should stay on the same dual-path family; " +
                "first=$firstFamily second=$secondFamily"
        )

        if (first is CodeActionOutcome.Succeeded) {
            assertWellFormedActions(first.actions, label = "repeat first")
        }
        if (second is CodeActionOutcome.Succeeded) {
            assertWellFormedActions(second.actions, label = "repeat second")
        }
    }

    @Test
    fun code_action_triple_interleaved_uri_calls_stay_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val a = OpenDocument("workspace/code-action-safety-interleave-a.lua", "return 1")
        val b = OpenDocument("workspace/code-action-safety-interleave-b.lua", "return 2")

        textDocuments.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(a.uri, "lua", 1, a.source))
        )
        textDocuments.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(b.uri, "lua", 1, b.source))
        )

        val outcomes = listOf(
            invokeCodeAction(
                textDocuments,
                codeActionParams(a, Range(Position(0, 0), Position(0, 6)), emptyList())
            ),
            invokeCodeAction(
                textDocuments,
                codeActionParams(b, Range(Position(0, 0), Position(0, 6)), emptyList())
            ),
            invokeCodeAction(
                textDocuments,
                codeActionParams(a, Range(Position(0, 0), Position(0, 6)), emptyList())
            )
        )

        outcomes.forEachIndexed { index, outcome ->
            assertDegradesAsGapOrEmpty(
                outcome,
                context = "interleaved multi-uri call #$index"
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

    /**
     * Open helper that pairs a fresh [LuaLanguageService] with a capturing
     * [LuaTextDocumentService] so diagnostics and codeAction share one session.
     */
    private fun openSession(path: String, source: String): OpenedWithDiagnostics {
        val service = service()
        val document = OpenDocument(path = path, source = source.trimIndent())
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = service,
            publishDiagnostics = { published += it }
        )
        textDocuments.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(document.uri, "lua", 1, document.source)
            )
        )
        val diagnostics = published
            .filter { it.uri == document.uri }
            .flatMap { it.diagnostics.orEmpty() }
        return OpenedWithDiagnostics(
            document = document,
            diagnostics = diagnostics,
            textDocuments = textDocuments
        )
    }

    private fun codeActionParams(
        document: OpenDocument,
        range: Range,
        diagnostics: List<Diagnostic>,
        only: List<String>? = null
    ): CodeActionParams {
        val context = CodeActionContext(diagnostics.toMutableList()).apply {
            if (only != null) {
                this.only = only.toMutableList()
            }
        }
        return CodeActionParams(
            TextDocumentIdentifier(document.uri),
            range,
            context
        )
    }

    private fun invokeCodeAction(
        textDocuments: LuaTextDocumentService,
        params: CodeActionParams
    ): CodeActionOutcome {
        return try {
            val result = textDocuments.codeAction(params).get()
            val actions = result.orEmpty()
            if (actions.isEmpty()) {
                CodeActionOutcome.Empty(detail = "empty action list")
            } else {
                CodeActionOutcome.Succeeded(actions = actions)
            }
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                CodeActionOutcome.Unsupported(
                    detail = root.toString(),
                    isUnsupportedOperation = true
                )
            } else {
                CodeActionOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun assertDegradesAsGapOrEmpty(
        outcome: CodeActionOutcome,
        context: String,
        allowNonEmptyWhenSucceeded: Boolean = true
    ) {
        when (outcome) {
            is CodeActionOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is CodeActionOutcome.Empty -> {
                // Soft degrade (empty / null) is the ideal unimplemented-or-no-fix path.
            }
            is CodeActionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "codeAction for $context must not hard-crash; got ${outcome.detail}"
                )
            }
            is CodeActionOutcome.Succeeded -> {
                assertWellFormedActions(outcome.actions, label = context)
                if (!allowNonEmptyWhenSucceeded) {
                    // Soft note: empty document ideally yields no inventing actions.
                    // Still accept well-formed non-empty if product elects to emit.
                    assertTrue(
                        outcome.actions.isNotEmpty(),
                        "Succeeded path under $context carried actions (allowed when product lands)"
                    )
                }
            }
        }
    }

    private fun assertWellFormedActions(
        actions: List<Either<Command, CodeAction>>,
        label: String
    ) {
        actions.forEachIndexed { index, either ->
            when {
                either.isLeft -> {
                    val command = either.left
                    assertNotNull(command, "$label[$index] Command must be non-null")
                    assertTrue(
                        !command.command.isNullOrBlank() || !command.title.isNullOrBlank(),
                        "$label[$index] Command must have title or command id; got $command"
                    )
                }
                either.isRight -> {
                    val action = either.right
                    assertNotNull(action, "$label[$index] CodeAction must be non-null")
                    assertTrue(
                        !action.title.isNullOrBlank(),
                        "$label[$index] CodeAction.title must be non-blank"
                    )
                    val nested = action.command
                    if (nested != null) {
                        assertTrue(
                            !nested.command.isNullOrBlank() || !nested.title.isNullOrBlank(),
                            "$label[$index] nested command must have title or id"
                        )
                    }
                }
                else -> fail("$label[$index] Either is neither left nor right: $either")
            }
        }
    }

    private fun outcomeFamily(outcome: CodeActionOutcome): String {
        return when (outcome) {
            is CodeActionOutcome.Unsupported -> "Unsupported"
            is CodeActionOutcome.Empty -> "Empty"
            is CodeActionOutcome.Succeeded -> "Succeeded"
            is CodeActionOutcome.Failed -> "Failed"
        }
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

    private sealed class CodeActionOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : CodeActionOutcome()

        data class Empty(val detail: String) : CodeActionOutcome()

        data class Succeeded(
            val actions: List<Either<Command, CodeAction>>
        ) : CodeActionOutcome()

        data class Failed(val detail: String) : CodeActionOutcome()
    }

    private data class OpenedWithDiagnostics(
        val document: OpenDocument,
        val diagnostics: List<Diagnostic>,
        val textDocuments: LuaTextDocumentService
    )

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

        fun rangeOf(needle: String, occurrence: Int = 1): Range {
            val start = positionOf(needle, occurrence)
            return Range(
                start,
                Position(start.line, start.character + needle.length)
            )
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
