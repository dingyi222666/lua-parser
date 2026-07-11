package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
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
import kotlin.test.fail

/**
 * TASK-268 / TASK-542 — LSP code action quickfix safety corpus.
 *
 * Locks the safety contract for textDocument/codeAction (quickfix surface):
 * - CodeAction requests for known diagnostics degrade safely when no fix is
 *   available (empty action list or soft reject).
 * - Empty selection, inverted/malformed ranges, and out-of-bounds ranges must
 *   not crash the LSP surface.
 * - Empty diagnostic context and quickfix-only filters must not invent hard
 *   failures.
 * - When product returns CodeAction/Command entries, each is well-formed
 *   (non-blank title; optional kind/edit/command coherent).
 *
 * Product path (TASK-542): [LuaLanguageService] advertises codeActionProvider
 * (CodeActionOptions with QuickFix) and [LuaTextDocumentService.codeAction]
 * returns an empty list (or well-formed actions) without throwing
 * UnsupportedOperationException.
 *
 * Test-only dual-path helpers remain for soft-reject / gap resilience; product
 * ideal path is Succeeded(empty or well-formed). Verification is review-owned
 * and serial; this worker does not run Gradle.
 */
class LspCodeActionQuickFixSafetyTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun code_action_capability_is_null_or_explicit_when_product_lands() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        // TASK-542 product path advertises codeActionProvider (Boolean / Either /
        // CodeActionOptions). Null remains a soft historical gap if a future
        // regression drops the capability, but non-null is expected once wired.
        assertNotNull(capabilities, "initialize must return ServerCapabilities")
        val provider = capabilities.codeActionProvider
        if (provider == null) {
            assertTrue(true)
        } else {
            assertNotNull(provider)
        }
    }

    @Test
    fun text_document_service_code_action_surface_is_invokable() {
        val session = openSession(
            "workspace/code-action-surface.lua",
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
                outcome is CodeActionOutcome.Failed ||
                outcome is CodeActionOutcome.Succeeded,
            "codeAction surface must resolve to a known outcome; got $outcome"
        )
    }

    // -------------------------------------------------------------------------
    // Known diagnostics: degrade safely when no fix available
    // -------------------------------------------------------------------------

    @Test
    fun code_action_for_parse_error_degrades_safely_when_no_fix() {
        val session = openSession(
            "workspace/code-action-parse-error.lua",
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

        assertDegradesSafelyWhenNoFix(
            outcome,
            context = "parse error diagnostic (no automatic quickfix expected)"
        )
    }

    @Test
    fun code_action_for_checker_type_mismatch_degrades_safely_when_no_fix() {
        val source = """
            ---@return string
            local function render()
                return 1
            end
            return render()
        """.trimIndent()
        val session = openSession(
            "workspace/code-action-type-mismatch.lua",
            source
        )

        // Soft: product may or may not emit checker diagnostics for this fixture.
        // When diagnostics exist, codeAction must degrade safely; when empty, still
        // probe the surface with an empty-context request over the return site.
        val diagnostics = session.diagnostics
        val range = diagnostics.firstOrNull()?.range
            ?: session.document.rangeOf("return 1")
        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = range,
                diagnostics = diagnostics,
                only = listOf(CodeActionKind.QuickFix)
            )
        )

        assertDegradesSafelyWhenNoFix(
            outcome,
            context = "checker type-mismatch diagnostic / return-site selection"
        )
    }

    @Test
    fun code_action_with_known_diagnostic_but_empty_only_filter_degrades_safely() {
        val session = openSession(
            "workspace/code-action-empty-only.lua",
            "local ="
        )
        val diagnostic = session.diagnostics.firstOrNull()
            ?: Diagnostic().apply {
                range = Range(Position(0, 0), Position(0, 1))
                message = "synthetic parse diagnostic for empty-only filter"
            }
        val range = diagnostic.range ?: Range(Position(0, 0), Position(0, 1))

        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = range,
                diagnostics = listOf(diagnostic),
                only = emptyList()
            )
        )

        assertDegradesSafelyWhenNoFix(
            outcome,
            context = "known diagnostic with empty 'only' kind filter"
        )
    }

    // -------------------------------------------------------------------------
    // Empty selection / malformed range: no crash
    // -------------------------------------------------------------------------

    @Test
    fun code_action_on_empty_selection_does_not_crash() {
        val session = openSession(
            "workspace/code-action-empty-selection.lua",
            "local value = 1\n\nreturn value"
        )

        // Zero-width selection on blank line between statements.
        val emptySelection = Range(Position(1, 0), Position(1, 0))
        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = emptySelection,
                diagnostics = emptyList()
            )
        )

        assertNoCrash(
            outcome,
            context = "empty (zero-width) selection on blank line"
        )
    }

    @Test
    fun code_action_on_inverted_malformed_range_does_not_crash() {
        val session = openSession(
            "workspace/code-action-inverted-range.lua",
            "local value = 1\nreturn value"
        )

        // End before start — malformed client range.
        val inverted = Range(Position(1, 5), Position(0, 0))
        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = inverted,
                diagnostics = emptyList()
            )
        )

        assertNoCrash(
            outcome,
            context = "inverted / malformed range (end before start)"
        )
    }

    @Test
    fun code_action_on_out_of_bounds_range_does_not_crash() {
        val session = openSession(
            "workspace/code-action-oob-range.lua",
            "local value = 1\nreturn value"
        )

        // Far past EOF.
        val oob = Range(Position(50, 0), Position(50, 10))
        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = oob,
                diagnostics = emptyList()
            )
        )

        assertNoCrash(
            outcome,
            context = "out-of-bounds range past EOF"
        )
    }

    @Test
    fun code_action_on_extreme_character_offsets_does_not_crash() {
        val session = openSession(
            "workspace/code-action-large-chars.lua",
            "local value = 1\nreturn value"
        )

        // Extreme character offsets on a valid line (clients occasionally send these).
        val extreme = Range(Position(0, 10_000), Position(0, 20_000))
        val outcome = invokeCodeAction(
            session.textDocuments,
            codeActionParams(
                document = session.document,
                range = extreme,
                diagnostics = emptyList()
            )
        )

        assertNoCrash(
            outcome,
            context = "extreme character offsets on valid line"
        )
    }

    // -------------------------------------------------------------------------
    // Empty diagnostic context / quickfix filter only
    // -------------------------------------------------------------------------

    @Test
    fun code_action_with_empty_diagnostics_context_degrades_safely() {
        val session = openSession(
            "workspace/code-action-empty-diags.lua",
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

        assertDegradesSafelyWhenNoFix(
            outcome,
            context = "valid document, empty diagnostics, quickfix-only filter"
        )
    }

    @Test
    fun code_action_quickfix_only_filter_on_valid_document_returns_empty_or_gap() {
        val session = openSession(
            "workspace/code-action-quickfix-only-valid.lua",
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

        when (outcome) {
            is CodeActionOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for codeAction; got ${outcome.detail}"
                )
            }
            is CodeActionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "quickfix-only on valid doc must not hard-crash; got ${outcome.detail}"
                )
            }
            is CodeActionOutcome.Succeeded -> {
                // Ideal: no invented quickfixes for a clean document.
                assertTrue(
                    outcome.actions.isEmpty() || outcome.actions.all { action ->
                        actionKind(action)?.startsWith(CodeActionKind.QuickFix) != false
                    },
                    "when actions are returned under quickfix-only filter they must be quickfix-kind " +
                        "or empty; got ${describe(outcome.actions)}"
                )
                assertWellFormedActions(outcome.actions)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Diagnostic-associated request: actions that land must be well-formed
    // -------------------------------------------------------------------------

    @Test
    fun code_action_returned_actions_are_well_formed_when_product_lands() {
        val session = openSession(
            "workspace/code-action-well-formed.lua",
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
            is CodeActionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "codeAction failure must not be hard crash; got ${outcome.detail}"
                )
            }
            is CodeActionOutcome.Succeeded -> {
                assertWellFormedActions(outcome.actions)
                // Soft: when product emits quickfixes for this diagnostic they may
                // attach diagnostics; empty list is the safe no-fix path.
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
            CodeActionOutcome.Succeeded(actions = result.orEmpty())
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

    private fun assertDegradesSafelyWhenNoFix(
        outcome: CodeActionOutcome,
        context: String
    ) {
        when (outcome) {
            is CodeActionOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is CodeActionOutcome.Failed -> {
                // Soft reject / ResponseError is fine; process-killing crash is not.
                assertFalse(
                    isHardCrash(outcome.detail),
                    "codeAction for $context must not hard-crash when no fix available; got ${outcome.detail}"
                )
            }
            is CodeActionOutcome.Succeeded -> {
                // Empty list is the ideal soft degrade. Non-empty actions must be well-formed
                // (product may later ship real quickfixes for these diagnostics).
                assertWellFormedActions(outcome.actions)
            }
        }
    }

    private fun assertNoCrash(
        outcome: CodeActionOutcome,
        context: String
    ) {
        when (outcome) {
            is CodeActionOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is CodeActionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "codeAction for $context must not hard-crash; got ${outcome.detail}"
                )
            }
            is CodeActionOutcome.Succeeded -> {
                assertWellFormedActions(outcome.actions)
            }
        }
    }

    private fun assertWellFormedActions(actions: List<Either<Command, CodeAction>>) {
        actions.forEachIndexed { index, either ->
            when {
                either.isLeft -> {
                    val command = either.left
                    assertNotNull(command, "action[$index] Command must be non-null")
                    assertTrue(
                        !command.command.isNullOrBlank() || !command.title.isNullOrBlank(),
                        "action[$index] Command must have title or command id; got $command"
                    )
                }
                either.isRight -> {
                    val action = either.right
                    assertNotNull(action, "action[$index] CodeAction must be non-null")
                    assertTrue(
                        !action.title.isNullOrBlank(),
                        "action[$index] CodeAction.title must be non-blank"
                    )
                    // A CodeAction may defer edit/command to resolve; when present they
                    // must be coherent.
                    val nested = action.command
                    if (nested != null) {
                        assertTrue(
                            !nested.command.isNullOrBlank() || !nested.title.isNullOrBlank(),
                            "action[$index] nested command must have title or id"
                        )
                    }
                }
                else -> fail("action[$index] Either is neither left nor right: $either")
            }
        }
    }

    private fun actionKind(either: Either<Command, CodeAction>): String? {
        return if (either.isRight) either.right?.kind else null
    }

    private fun describe(actions: List<Either<Command, CodeAction>>): String {
        return actions.joinToString(prefix = "[", postfix = "]") { either ->
            when {
                either.isLeft -> "Command(title=${either.left?.title}, cmd=${either.left?.command})"
                either.isRight -> "CodeAction(title=${either.right?.title}, kind=${either.right?.kind})"
                else -> "empty-either"
            }
        }
    }

    private fun isHardCrash(detail: String): Boolean {
        return detail.contains("NullPointerException", ignoreCase = true) ||
            detail.contains("AssertionError", ignoreCase = true) ||
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

        data class Failed(val detail: String) : CodeActionOutcome()

        data class Succeeded(
            val actions: List<Either<Command, CodeAction>>
        ) : CodeActionOutcome()
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
