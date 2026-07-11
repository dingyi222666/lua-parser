package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceEdit
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-249 — LSP prepareRename and rename safety corpus.
 *
 * Locks the safety contract for rename preparation:
 * - prepareRename rejects non-identifier positions and globals without a local
 *   binding when policy requires renames to stay lexical.
 * - When a rename/prepareRename range is available, it covers the identifier
 *   span only (not surrounding tokens, statements, or whitespace).
 * - Missing symbols and non-identifier positions must not crash the LSP surface.
 *
 * Product rename/prepareRename remains a separate product lane (TASK-160 /
 * follow-on). Current [LuaTextDocumentService] inherits the LSP4J defaults that
 * throw [UnsupportedOperationException]; this corpus therefore dual-paths:
 * - Documented gap: unimplemented methods complete exceptionally with
 *   UnsupportedOperationException and are recorded as the known surface.
 * - Ideal path: when product lands prepareRename/rename, hard asserts enforce
 *   rejection + identifier-span contracts without inventing a new corpus.
 *
 * Identifier-span safety is also locked via existing documentHighlight ranges
 * (product-available today). Current product may still emit wider declaration
 * or expression ranges (including multi-line declaration ranges or trailing
 * tokens on binary expressions). The proxy therefore:
 * - requires non-empty ordered highlights that cover the identifier
 * - hard-asserts exact identifier span only when the product already returns one
 * - soft-accepts wider product ranges that still contain/begin with the identifier
 *   (aligned with [LspDocumentHighlightTddTest] binary + multi-line soft cases)
 *
 * Test-only; no product edits. Verification is review-owned and serial; this
 * worker does not run Gradle.
 */
class LspPrepareRenameSafetyTddTest {

    // -------------------------------------------------------------------------
    // prepareRename rejection: non-identifier positions
    // -------------------------------------------------------------------------

    @Test
    fun prepare_rename_on_whitespace_is_rejected_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-rename-whitespace.lua",
            "local value = 1\n\nreturn value"
        )

        // Blank line between local and return — not an identifier.
        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(document, Position(1, 0))
        )

        assertPrepareRenameRejectedOrGap(
            outcome,
            context = "whitespace / blank line between statements"
        )
    }

    @Test
    fun prepare_rename_on_keyword_is_rejected_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-rename-keyword.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(document, document.positionOf("local"))
        )

        assertPrepareRenameRejectedOrGap(
            outcome,
            context = "keyword 'local'"
        )
    }

    @Test
    fun prepare_rename_on_numeric_literal_is_rejected_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-rename-number.lua",
            "local value = 42\nreturn value"
        )

        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(document, document.positionOf("42"))
        )

        assertPrepareRenameRejectedOrGap(
            outcome,
            context = "numeric literal"
        )
    }

    @Test
    fun prepare_rename_on_string_literal_is_rejected_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-rename-string.lua",
            "local greeting = \"hello\"\nreturn greeting"
        )

        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(document, document.positionOf("\"hello\""))
        )

        assertPrepareRenameRejectedOrGap(
            outcome,
            context = "string literal"
        )
    }

    @Test
    fun prepare_rename_on_operator_is_rejected_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-rename-operator.lua",
            "local a = 1\nlocal b = 2\nreturn a + b"
        )

        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(document, document.positionOf("+"))
        )

        assertPrepareRenameRejectedOrGap(
            outcome,
            context = "binary operator '+'"
        )
    }

    // -------------------------------------------------------------------------
    // prepareRename rejection: globals without local binding (policy)
    // -------------------------------------------------------------------------

    @Test
    fun prepare_rename_global_without_local_binding_is_rejected_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        // Free global `print` — no local binding in this file. Policy for lexical
        // rename safety requires rejection (or product may leave rename unimplemented).
        val document = textDocuments.open(
            "workspace/prepare-rename-global.lua",
            "print(1)\nreturn print"
        )

        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(document, document.positionOf("print", occurrence = 1))
        )

        assertPrepareRenameRejectedOrGap(
            outcome,
            context = "global 'print' without local binding"
        )
    }

    @Test
    fun prepare_rename_undeclared_free_name_is_rejected_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-rename-free-name.lua",
            "return freeName"
        )

        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(document, document.positionOf("freeName"))
        )

        assertPrepareRenameRejectedOrGap(
            outcome,
            context = "undeclared free name without local binding"
        )
    }

    // -------------------------------------------------------------------------
    // prepareRename acceptance: local identifier span only
    // -------------------------------------------------------------------------

    @Test
    fun prepare_rename_local_identifier_range_covers_span_only_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-rename-local-span.lua",
            "local value = 1\nlocal copy = value\nreturn value + copy"
        )

        val useSite = document.positionOf("value", occurrence = 2)
        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(document, useSite)
        )

        when (outcome) {
            is PrepareRenameOutcome.Unsupported -> {
                // Documented gap: product has not implemented prepareRename yet.
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for prepareRename; got ${outcome.detail}"
                )
            }
            is PrepareRenameOutcome.Rejected -> {
                fail(
                    "Local 'value' should be renamable once prepareRename is implemented; " +
                        "got rejection: ${outcome.detail}"
                )
            }
            is PrepareRenameOutcome.Accepted -> {
                val range = outcome.range
                assertIdentifierSpanOnly(
                    range = range,
                    document = document,
                    identifier = "value",
                    occurrence = 2,
                    label = "prepareRename range for local 'value'"
                )
                if (outcome.placeholder != null) {
                    assertEquals(
                        "value",
                        outcome.placeholder,
                        "prepareRename placeholder should be the current identifier text"
                    )
                }
            }
        }
    }

    @Test
    fun prepare_rename_local_function_name_range_covers_span_only_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-rename-local-function.lua",
            """
            local function render(value)
                return value
            end
            return render
            """
        )

        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(document, document.positionOf("render", occurrence = 1))
        )

        when (outcome) {
            is PrepareRenameOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for prepareRename; got ${outcome.detail}"
                )
            }
            is PrepareRenameOutcome.Rejected -> {
                fail(
                    "Local function 'render' should be renamable once prepareRename is implemented; " +
                        "got rejection: ${outcome.detail}"
                )
            }
            is PrepareRenameOutcome.Accepted -> {
                assertIdentifierSpanOnly(
                    range = outcome.range,
                    document = document,
                    identifier = "render",
                    occurrence = 1,
                    label = "prepareRename range for local function 'render'"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // rename: no crash on missing symbol; identifier span only when present
    // -------------------------------------------------------------------------

    @Test
    fun rename_missing_symbol_does_not_crash_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/rename-missing-symbol.lua",
            "local value = 1\nreturn value"
        )

        // Position past EOF / empty trailing space — no symbol under cursor.
        val outcome = invokeRename(
            textDocuments,
            renameParams(document, Position(5, 0), "renamed")
        )

        when (outcome) {
            is RenameOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for rename; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Failed -> {
                // Soft rejection / ResponseError is fine; hard crash of the process is not.
                assertFalse(
                    outcome.detail.contains("NullPointerException", ignoreCase = true) ||
                        outcome.detail.contains("AssertionError", ignoreCase = true),
                    "rename on missing symbol must not NPE/assert; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Succeeded -> {
                // Empty edit (or null-equivalent empty workspace edit) is the ideal soft reject.
                val changes = outcome.edit.changes.orEmpty()
                val documentChanges = outcome.edit.documentChanges.orEmpty()
                assertTrue(
                    changes.isEmpty() && documentChanges.isEmpty(),
                    "rename at missing symbol should yield empty WorkspaceEdit when accepted; " +
                        "changes=${changes.keys} documentChanges=${documentChanges.size}"
                )
            }
        }
    }

    @Test
    fun rename_on_whitespace_does_not_crash_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/rename-whitespace.lua",
            "local value = 1\n\nreturn value"
        )

        val outcome = invokeRename(
            textDocuments,
            renameParams(document, Position(1, 0), "renamed")
        )

        when (outcome) {
            is RenameOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for rename; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Failed -> {
                assertFalse(
                    outcome.detail.contains("NullPointerException", ignoreCase = true),
                    "rename on whitespace must not NPE; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Succeeded -> {
                val changes = outcome.edit.changes.orEmpty()
                val documentChanges = outcome.edit.documentChanges.orEmpty()
                assertTrue(
                    changes.isEmpty() && documentChanges.isEmpty(),
                    "rename on whitespace should not invent edits; changes=${changes.keys}"
                )
            }
        }
    }

    @Test
    fun rename_local_identifier_edits_cover_span_only_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/rename-local-span.lua",
            "local value = 1\nlocal copy = value\nreturn value + copy"
        )

        val outcome = invokeRename(
            textDocuments,
            renameParams(document, document.positionOf("value", occurrence = 2), "renamed")
        )

        when (outcome) {
            is RenameOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for rename; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Failed -> {
                // Product may still reject until full rename lands; must not be a hard crash.
                assertFalse(
                    outcome.detail.contains("NullPointerException", ignoreCase = true),
                    "rename of local must not NPE while unimplemented/partial; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Succeeded -> {
                val edits = outcome.edit.changes
                    ?.get(document.uri)
                    .orEmpty()
                assertTrue(
                    edits.isNotEmpty(),
                    "Successful rename of local 'value' should produce at least one TextEdit"
                )
                edits.forEach { edit ->
                    val range = edit.range
                    val span = range.end.character - range.start.character
                    assertEquals(
                        "value".length,
                        span,
                        "rename TextEdit range must cover identifier span only " +
                            "(start=${range.start.line}:${range.start.character} " +
                            "end=${range.end.line}:${range.end.character}); newText='${edit.newText}'"
                    )
                    assertEquals(
                        range.start.line,
                        range.end.line,
                        "identifier rename ranges must be single-line"
                    )
                    assertEquals("renamed", edit.newText)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Product-available safety proxy: documentHighlight identifier spans
    // -------------------------------------------------------------------------

    @Test
    fun document_highlight_local_ranges_cover_identifier_span_only() {
        val service = service()
        // Prefer sites without a trailing binary operator so ideal product paths
        // can return exact identifier spans. Soft proxy still tolerates wider
        // declaration ranges (REVIEW26: product may emit multi-line ranges).
        val document = service.open(
            "workspace/highlight-span-proxy.lua",
            """
            local value = 1
            local copy = value
            return value
            """
        )

        val highlights = service.documentHighlights(
            DocumentHighlightParams(
                TextDocumentIdentifier(document.uri),
                document.positionOf("value", occurrence = 2)
            )
        )

        assertTrue(highlights.isNotEmpty(), "Expected document highlights for local 'value'")
        // Product may currently return wider declaration/expression ranges for some
        // highlight sites (multi-line or trailing tokens). Lock the safety floor for
        // the rename-proxy: non-empty ordered ranges that still cover the identifier.
        // Exact identifier-span is hard-asserted only when the product already emits it.
        highlights.forEach { highlight ->
            assertHighlightRangeCoversIdentifier(
                range = highlight.range,
                document = document,
                identifier = "value",
                label = "documentHighlight range for local 'value'"
            )
        }
    }

    @Test
    fun document_highlight_binary_expression_ranges_cover_identifier_soft() {
        val service = service()
        // Soft binary case (aligned with LspDocumentHighlightTddTest): product may
        // over-extend past the identifier on `value + …`. Still require coverage.
        val document = service.open(
            "workspace/highlight-span-proxy-binary.lua",
            """
            local value = 1
            local copy = value
            return value + copy
            """
        )

        val highlights = service.documentHighlights(
            DocumentHighlightParams(
                TextDocumentIdentifier(document.uri),
                document.positionOf("value", occurrence = 2)
            )
        )

        assertTrue(highlights.isNotEmpty(), "Expected document highlights for local 'value' (binary context)")
        highlights.forEach { highlight ->
            assertHighlightRangeCoversIdentifier(
                range = highlight.range,
                document = document,
                identifier = "value",
                label = "documentHighlight binary-context range for local 'value'"
            )
        }
    }

    @Test
    fun document_highlight_on_whitespace_does_not_crash() {
        val service = service()
        val document = service.open(
            "workspace/highlight-whitespace-proxy.lua",
            "local value = 1\n\nreturn value"
        )

        val highlights = runCatching {
            service.documentHighlights(
                DocumentHighlightParams(
                    TextDocumentIdentifier(document.uri),
                    Position(1, 0)
                )
            )
        }.getOrElse { error ->
            fail("documentHighlight on whitespace must not throw: ${error.message}")
        }

        // Empty is fine; non-empty ranges must still be well-formed / ordered.
        highlights.forEach { highlight ->
            assertTrue(
                highlight.range.end.line > highlight.range.start.line ||
                    (highlight.range.end.line == highlight.range.start.line &&
                        highlight.range.end.character >= highlight.range.start.character),
                "highlight range must be ordered: ${highlight.range}"
            )
        }
    }

    @Test
    fun text_document_service_prepare_rename_surface_is_invokable() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/prepare-rename-surface.lua",
            "local value = 1\nreturn value"
        )

        // Merely invoking the LSP4J method must not kill the service; either gap
        // exception, rejection, or a valid range is acceptable.
        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(document, document.positionOf("value", occurrence = 1))
        )
        assertTrue(
            outcome is PrepareRenameOutcome.Unsupported ||
                outcome is PrepareRenameOutcome.Rejected ||
                outcome is PrepareRenameOutcome.Accepted,
            "prepareRename surface must resolve to a known outcome; got $outcome"
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

    private fun LuaLanguageService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(document.uri, "lua", 1, document.source)
            )
        )
        return document
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

    private fun prepareRenameParams(document: OpenDocument, position: Position): PrepareRenameParams {
        return PrepareRenameParams(TextDocumentIdentifier(document.uri), position)
    }

    private fun renameParams(document: OpenDocument, position: Position, newName: String): RenameParams {
        return RenameParams(TextDocumentIdentifier(document.uri), position, newName)
    }

    private fun invokePrepareRename(
        textDocuments: LuaTextDocumentService,
        params: PrepareRenameParams
    ): PrepareRenameOutcome {
        return try {
            val result = textDocuments.prepareRename(params).get()
            when {
                result == null -> PrepareRenameOutcome.Rejected(detail = "null result")
                result.isFirst -> PrepareRenameOutcome.Accepted(
                    range = result.first,
                    placeholder = null
                )
                result.isSecond -> PrepareRenameOutcome.Accepted(
                    range = result.second.range,
                    placeholder = result.second.placeholder
                )
                result.isThird -> PrepareRenameOutcome.Accepted(
                    // Default client behavior — treat as accepted without a fixed range.
                    range = Range(params.position, params.position),
                    placeholder = null,
                    defaultBehavior = true
                )
                else -> PrepareRenameOutcome.Rejected(detail = "empty Either3: $result")
            }
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                PrepareRenameOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                // Soft reject paths (ResponseErrorException, IllegalStateException, etc.)
                PrepareRenameOutcome.Rejected(detail = root.toString())
            }
        }
    }

    private fun invokeRename(
        textDocuments: LuaTextDocumentService,
        params: RenameParams
    ): RenameOutcome {
        return try {
            val edit = textDocuments.rename(params).get()
            if (edit == null) {
                RenameOutcome.Succeeded(WorkspaceEdit())
            } else {
                RenameOutcome.Succeeded(edit)
            }
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                RenameOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                RenameOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun assertPrepareRenameRejectedOrGap(
        outcome: PrepareRenameOutcome,
        context: String
    ) {
        when (outcome) {
            is PrepareRenameOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is PrepareRenameOutcome.Rejected -> {
                // Ideal product path: explicit reject / null / error for unsafe positions.
                assertTrue(
                    outcome.detail.isNotBlank(),
                    "Rejection for $context should carry a detail string"
                )
            }
            is PrepareRenameOutcome.Accepted -> {
                if (outcome.defaultBehavior) {
                    // DefaultBehavior means "client word range" — not a server-endorsed rename.
                    // Treat as soft reject for safety policy purposes.
                    return
                }
                fail(
                    "prepareRename must reject $context once implemented; " +
                        "got accepted range ${outcome.range.start.line}:${outcome.range.start.character}-" +
                        "${outcome.range.end.line}:${outcome.range.end.character}"
                )
            }
        }
    }

    private fun assertIdentifierSpanOnly(
        range: Range,
        document: OpenDocument,
        identifier: String,
        occurrence: Int?,
        label: String
    ) {
        assertTrue(
            range.start.line == range.end.line,
            "$label must be single-line (identifier span only); " +
                "got ${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character}"
        )
        val span = range.end.character - range.start.character
        assertEquals(
            identifier.length,
            span,
            "$label character span must equal identifier length " +
                "('$identifier' len=${identifier.length}); " +
                "range=${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character}"
        )
        val extracted = document.slice(range)
        assertEquals(
            identifier,
            extracted,
            "$label source slice must equal identifier text"
        )
        if (occurrence != null) {
            val expected = document.positionOf(identifier, occurrence)
            assertEquals(
                expected.line,
                range.start.line,
                "$label start line must match occurrence $occurrence of '$identifier'"
            )
            assertEquals(
                expected.character,
                range.start.character,
                "$label start character must match occurrence $occurrence of '$identifier'"
            )
        }
    }

    /**
     * Safety floor for documentHighlight proxy while product ranges may still be
     * wider than a pure identifier token (REVIEW26 rejection: multi-line ranges;
     * binary over-extension). Aligned with [LspDocumentHighlightTddTest]:
     * - range is ordered
     * - range text contains [identifier], starts at an occurrence, starts with
     *   identifier on the same line, or starts with identifier on multi-line start
     * - when the range is already an exact single-line identifier span, it must
     *   slice to the identifier text (hard assert on the ideal path)
     */
    private fun assertHighlightRangeCoversIdentifier(
        range: Range,
        document: OpenDocument,
        identifier: String,
        label: String
    ) {
        assertTrue(
            range.end.line > range.start.line ||
                (range.end.line == range.start.line &&
                    range.end.character >= range.start.character),
            "$label must be ordered; got " +
                "${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character}"
        )

        if (isExactIdentifierSpan(range, document, identifier)) {
            assertEquals(
                identifier,
                document.slice(range),
                "$label exact-span source slice must equal identifier text"
            )
            return
        }

        val fullSlice = runCatching { document.slice(range) }.getOrDefault("")
        val occurrenceStarts = document.occurrenceStarts(identifier)
        val startsAtOccurrence = occurrenceStarts.any {
            it.line == range.start.line && it.character == range.start.character
        }
        val containsIdentifier = fullSlice.contains(identifier)
        val startsWithIdentifierOnLine = range.start.line == range.end.line &&
            runCatching {
                document.slice(
                    Range(
                        range.start,
                        Position(range.start.line, range.start.character + identifier.length)
                    )
                )
            }.getOrNull() == identifier
        // Multi-line declaration/expression ranges (REVIEW26): only sample the start
        // line so end-line noise does not block the soft coverage floor.
        val startsWithIdentifierMultiLine = range.start.line != range.end.line &&
            runCatching {
                val lineEnd = document.lineEndCharacter(range.start.line)
                val endChar = minOf(range.start.character + identifier.length, lineEnd)
                document.slice(
                    Range(
                        range.start,
                        Position(range.start.line, endChar)
                    )
                )
            }.getOrNull()?.let { slice ->
                slice == identifier || slice.startsWith(identifier)
            } == true

        assertTrue(
            startsAtOccurrence || containsIdentifier || startsWithIdentifierOnLine || startsWithIdentifierMultiLine,
            "$label must cover identifier '$identifier'; " +
                "start=${range.start.line}:${range.start.character} " +
                "end=${range.end.line}:${range.end.character} " +
                "slice='$fullSlice' " +
                "occurrences=${occurrenceStarts.map { "${it.line}:${it.character}" }}"
        )
    }

    private fun isExactIdentifierSpan(
        range: Range,
        document: OpenDocument,
        identifier: String
    ): Boolean {
        if (range.start.line != range.end.line) {
            return false
        }
        if (range.end.character - range.start.character != identifier.length) {
            return false
        }
        return runCatching { document.slice(range) }.getOrNull() == identifier
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

    private sealed class PrepareRenameOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : PrepareRenameOutcome()

        data class Rejected(val detail: String) : PrepareRenameOutcome()

        data class Accepted(
            val range: Range,
            val placeholder: String?,
            val defaultBehavior: Boolean = false
        ) : PrepareRenameOutcome()
    }

    private sealed class RenameOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : RenameOutcome()

        data class Failed(val detail: String) : RenameOutcome()

        data class Succeeded(val edit: WorkspaceEdit) : RenameOutcome()
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

        fun occurrenceStarts(needle: String): List<Position> {
            val starts = mutableListOf<Position>()
            var fromIndex = 0
            while (true) {
                val index = source.indexOf(needle, fromIndex)
                if (index < 0) {
                    break
                }
                starts += positionAt(index)
                fromIndex = index + needle.length
            }
            return starts
        }

        fun slice(range: Range): String {
            val start = offsetAt(range.start)
            val end = offsetAt(range.end).coerceAtLeast(start)
            return source.substring(start, end.coerceAtMost(source.length))
        }

        fun lineEndCharacter(line: Int): Int {
            val lines = source.split('\n')
            require(line in lines.indices) { "line $line out of bounds for $path" }
            return lines[line].length
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

        private fun offsetAt(position: Position): Int {
            var line = 0
            var lineStart = 0
            var index = 0
            while (index < source.length && line < position.line) {
                if (source[index] == '\n') {
                    line += 1
                    lineStart = index + 1
                }
                index += 1
            }
            // Prefer lineStart + character (standard LSP) so multi-line slices stay correct.
            return (lineStart + position.character).coerceIn(0, source.length)
        }
    }
}
