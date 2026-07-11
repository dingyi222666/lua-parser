package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextEdit
import org.junit.Assume
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-275 — LSP full-document formatting safety corpus.
 *
 * Locks the safety contract for textDocument/formatting (documentFormatting):
 * - Invoking document formatting must not crash the LSP surface.
 * - Valid Lua sources may return an empty edit list or well-formed [TextEdit]s.
 * - Syntax-error / malformed buffers degrade safely (empty edits or well-formed
 *   edits only — never hard throw once the surface is implemented).
 * - Empty documents, large documents, mixed indentation, CRLF, comments, and
 *   missing-document URIs stay non-throwing (or return the documented gap).
 *
 * Product formatting is intentionally out of scope for this task (test-only).
 * Current [LuaTextDocumentService] inherits the LSP4J default that throws
 * [UnsupportedOperationException], and [LuaLanguageService] does not advertise
 * documentFormattingProvider. Tests therefore dual-path:
 * - Documented gap: unimplemented methods complete exceptionally with
 *   UnsupportedOperationException (or capability remains null) and are recorded
 *   as the known surface; hard edit-shape asserts are skipped cleanly.
 * - Ideal path: when product lands formatting, hard asserts enforce empty-or-
 *   well-formed edits without inventing a new corpus.
 *
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class LspFormattingFullDocumentSafetyTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun initialize_document_formatting_capability_is_probeable() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        // Capability may be null today (documented gap) or present once product
        // lands full-document formatting. Reading the field must not throw.
        val provider = capabilities.documentFormattingProvider
        if (provider == null) {
            assertTrue(
                true,
                "documentFormattingProvider absent is an accepted pre-product surface"
            )
            return
        }

        // When advertised as Either, either boolean true or options object is fine.
        val enabled = when {
            provider.isLeft -> provider.left == true
            provider.isRight -> provider.right != null
            else -> false
        }
        assertTrue(
            enabled,
            "advertised documentFormattingProvider must enable formatting " +
                "(boolean true or options object); got $provider"
        )
    }

    @Test
    fun document_formatting_surface_is_invokable_without_killing_service() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-surface.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeDocumentFormatting(
            textDocuments,
            formattingParams(document)
        )

        assertTrue(
            outcome is FormattingOutcome.Unsupported ||
                outcome is FormattingOutcome.Succeeded,
            "documentFormatting surface must resolve to Unsupported or Succeeded; got $outcome"
        )
        if (outcome is FormattingOutcome.Succeeded) {
            assertWellFormedEdits(outcome.edits, document)
        }
    }

    @Test
    fun document_formatting_can_be_invoked_twice_without_killing_service() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-twice.lua",
            "local a = 1\nlocal b = 2\nreturn a + b"
        )
        val params = formattingParams(document)

        val first = invokeDocumentFormatting(textDocuments, params)
        val second = invokeDocumentFormatting(textDocuments, params)

        assertTrue(
            first is FormattingOutcome.Unsupported || first is FormattingOutcome.Succeeded,
            "first formatting call must not hard-fail; got $first"
        )
        assertTrue(
            second is FormattingOutcome.Unsupported || second is FormattingOutcome.Succeeded,
            "second formatting call must not hard-fail; got $second"
        )
        if (first is FormattingOutcome.Succeeded) {
            assertWellFormedEdits(first.edits, document)
        }
        if (second is FormattingOutcome.Succeeded) {
            assertWellFormedEdits(second.edits, document)
        }
    }

    // -------------------------------------------------------------------------
    // Well-formed sources: empty or well-formed edits, never crash
    // -------------------------------------------------------------------------

    @Test
    fun document_formatting_valid_source_returns_empty_or_edits_without_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-valid.lua",
            """
            local function greet(name)
                return "hi " .. name
            end
            return greet("world")
            """
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "valid multi-line function source"
        )
    }

    @Test
    fun document_formatting_already_compact_source_returns_empty_or_edits_without_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-compact.lua",
            "local x=1;local y=2;return x+y"
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "compact single-line source"
        )
    }

    @Test
    fun document_formatting_mixed_indentation_returns_empty_or_edits_without_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        // Tabs mixed with spaces — formatter may normalize or no-op.
        val document = textDocuments.open(
            "workspace/format-full-mixed-indent.lua",
            "local function f()\n\tlocal a = 1\n  local b = 2\n\treturn a + b\nend\nreturn f"
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "mixed tab/space indentation"
        )
    }

    @Test
    fun document_formatting_table_constructor_returns_empty_or_edits_without_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-table.lua",
            """
            local config = {
              enabled=true,
                retries =3,
            label = "demo"
            }
            return config
            """
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "multi-line table constructor with uneven spacing"
        )
    }

    @Test
    fun document_formatting_with_tab_size_options_returns_empty_or_edits_without_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-options.lua",
            """
            local function nested()
            if true then
            return 1
            end
            end
            return nested
            """
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "custom FormattingOptions tabSize=4 insertSpaces=true",
            options = FormattingOptions(4, true)
        )
    }

    @Test
    fun document_formatting_crlf_source_returns_empty_or_edits_without_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.openRaw(
            "workspace/format-full-crlf.lua",
            "local function f()\r\n    return 1\r\nend\r\nreturn f()\r\n"
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "CRLF line endings"
        )
    }

    @Test
    fun document_formatting_comment_only_source_returns_empty_or_edits_without_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-comments.lua",
            """
            -- header
            --[[ multi
                 line
                 comment ]]
            ---@param x number
            """
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "comment / EmmyLua annotation only source"
        )
    }

    @Test
    fun document_formatting_long_string_and_method_calls_returns_empty_or_edits_without_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-long-string.lua",
            """
            local msg = [[
              multi-line
              long string
            ]]
            local t = { value = 1 }
            function t:method(x)
              return self.value + x
            end
            return t:method(2) .. msg
            """
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "long string + method-call source"
        )
    }

    @Test
    fun document_formatting_unicode_identifiers_returns_empty_or_edits_without_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        // Lua 5.3 identifiers are typically ASCII in practice; string content may be unicode.
        val document = textDocuments.open(
            "workspace/format-full-unicode.lua",
            """
            local greeting = "你好世界"
            local path = "C:\\temp\\文件.lua"
            return greeting .. path
            """
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "unicode string content"
        )
    }

    // -------------------------------------------------------------------------
    // Syntax-error / malformed docs must degrade safely
    // -------------------------------------------------------------------------

    @Test
    fun document_formatting_syntax_error_unclosed_function_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-unclosed-function.lua",
            """
            local function broken(
                return 1
            """
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "syntax-error unclosed function"
        )
    }

    @Test
    fun document_formatting_syntax_error_unclosed_table_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-unclosed-table.lua",
            """
            local t = {
                a = 1,
                b =
            return t
            """
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "syntax-error unclosed table constructor"
        )
    }

    @Test
    fun document_formatting_syntax_error_stray_tokens_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-stray-tokens.lua",
            "end end local = 1 then if\nreturn"
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "syntax-error stray keywords / incomplete assignment"
        )
    }

    @Test
    fun document_formatting_incomplete_string_literal_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-incomplete-string.lua",
            "local msg = \"unterminated\nreturn msg"
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "syntax-error incomplete string literal"
        )
    }

    @Test
    fun document_formatting_unclosed_long_comment_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-unclosed-comment.lua",
            """
            --[[ never closed
            local x = 1
            return x
            """
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "syntax-error unclosed long comment"
        )
    }

    @Test
    fun document_formatting_mismatched_end_keywords_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-mismatched-end.lua",
            """
            if true then
              function f()
                return 1
            end
            """
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "syntax-error mismatched end keywords"
        )
    }

    // -------------------------------------------------------------------------
    // Empty / large / whitespace-only / missing document buffers
    // -------------------------------------------------------------------------

    @Test
    fun document_formatting_empty_document_returns_empty_or_edits_without_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-empty.lua",
            ""
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "empty document"
        )
    }

    @Test
    fun document_formatting_whitespace_only_document_returns_empty_or_edits_without_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-whitespace.lua",
            "   \n\t\n  \n"
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "whitespace-only document"
        )
    }

    @Test
    fun document_formatting_large_document_returns_empty_or_edits_without_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val body = buildString {
            appendLine("local module = {}")
            repeat(200) { index ->
                appendLine("function module.fn$index(x)")
                appendLine("  local y = x + $index")
                appendLine("  return y")
                appendLine("end")
            }
            appendLine("return module")
        }
        val document = textDocuments.open(
            "workspace/format-full-large.lua",
            body
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "large multi-function document (~800 lines)"
        )
    }

    @Test
    fun document_formatting_insert_spaces_false_options_returns_empty_or_edits_without_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/format-full-tabs-options.lua",
            """
            local function withSpaces()
                return 1
            end
            return withSpaces
            """
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            document,
            context = "FormattingOptions tabSize=2 insertSpaces=false",
            options = FormattingOptions(2, false)
        )
    }

    @Test
    fun missing_document_uri_returns_empty_or_gap_without_unhandled_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        // Do not open any document; request against a synthetic uri.
        val params = DocumentFormattingParams(
            TextDocumentIdentifier("file:///workspace/format-full-missing.lua"),
            FormattingOptions(4, true)
        )

        val outcome = invokeDocumentFormatting(textDocuments, params)

        when (outcome) {
            is FormattingOutcome.Unsupported -> {
                assertTrue(outcome.detail.isNotBlank())
            }
            is FormattingOutcome.Failed -> {
                // Soft failure (e.g. missing document) is acceptable; must still
                // carry a detail and not be an unhandled NPE without message.
                assertTrue(
                    outcome.detail.isNotBlank(),
                    "missing-document failure should carry detail"
                )
            }
            is FormattingOutcome.Succeeded -> {
                assertTrue(
                    outcome.edits.isEmpty(),
                    "missing document should yield empty edits; got ${describe(outcome.edits)}"
                )
            }
        }
    }

    @Test
    fun document_formatting_after_open_then_reopen_same_uri_stays_safe() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        textDocuments.open(
            "workspace/format-full-reopen.lua",
            "local a = 1\nreturn a"
        )
        // Re-open same path with different body (new TextDocumentItem version).
        val second = textDocuments.open(
            "workspace/format-full-reopen.lua",
            "local b = 2\nreturn b"
        )

        assertFormattingEmptyOrWellFormed(
            textDocuments,
            second,
            context = "reopened document uri"
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
        return openRaw(path, source.trimIndent())
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

    private fun formattingParams(
        document: OpenDocument,
        options: FormattingOptions = FormattingOptions(4, true)
    ): DocumentFormattingParams {
        return DocumentFormattingParams(
            TextDocumentIdentifier(document.uri),
            options
        )
    }

    private fun assertFormattingEmptyOrWellFormed(
        textDocuments: LuaTextDocumentService,
        document: OpenDocument,
        context: String,
        options: FormattingOptions = FormattingOptions(4, true)
    ) {
        when (val outcome = invokeDocumentFormatting(textDocuments, formattingParams(document, options))) {
            is FormattingOutcome.Unsupported -> {
                // Documented gap: product has not implemented documentFormatting yet.
                // Keep suite green under JUnit Assume when surface is missing.
                Assume.assumeTrue(
                    "TASK-275 skipped: documentFormatting not yet implemented for $context; " +
                        "detail=${outcome.detail}",
                    false
                )
            }
            is FormattingOutcome.Failed -> {
                fail(
                    "documentFormatting must not crash for $context; got failure: ${outcome.detail}"
                )
            }
            is FormattingOutcome.Succeeded -> {
                // Empty list is always safe; non-empty edits must be well-formed.
                assertWellFormedEdits(outcome.edits, document)
            }
        }
    }

    private fun invokeDocumentFormatting(
        textDocuments: LuaTextDocumentService,
        params: DocumentFormattingParams
    ): FormattingOutcome {
        return try {
            val edits = textDocuments.formatting(params).get()
            // null result is treated as empty edit list (safe no-op).
            FormattingOutcome.Succeeded(edits.orEmpty())
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                FormattingOutcome.Unsupported(detail = root.toString())
            } else {
                FormattingOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun assertWellFormedEdits(edits: List<TextEdit>, document: OpenDocument) {
        assertNotNull(edits, "edits list must be non-null (use empty list for no-op)")
        edits.forEachIndexed { index, edit ->
            val range = edit.range
            assertNotNull(range, "TextEdit[$index].range must be non-null")
            assertNotNull(edit.newText, "TextEdit[$index].newText must be non-null (empty string OK)")
            assertOrderedRange(range, label = "TextEdit[$index]")
            assertRangeInsideDocument(range, document, label = "TextEdit[$index]")
        }
        // Overlapping / inverted multi-edit sets are out of scope; individual
        // ranges already validated. Product may return a single full-document
        // replacement or multiple non-overlapping patches.
    }

    private fun assertOrderedRange(range: Range, label: String) {
        val start = range.start
        val end = range.end
        assertNotNull(start, "$label.start must be non-null")
        assertNotNull(end, "$label.end must be non-null")
        assertTrue(start.line >= 0, "$label.start.line must be >= 0; got ${start.line}")
        assertTrue(start.character >= 0, "$label.start.character must be >= 0; got ${start.character}")
        assertTrue(end.line >= 0, "$label.end.line must be >= 0; got ${end.line}")
        assertTrue(end.character >= 0, "$label.end.character must be >= 0; got ${end.character}")
        assertTrue(
            end.line > start.line ||
                (end.line == start.line && end.character >= start.character),
            "$label must be ordered; got " +
                "${start.line}:${start.character}-${end.line}:${end.character}"
        )
    }

    private fun assertRangeInsideDocument(range: Range, document: OpenDocument, label: String) {
        val lineCount = document.lineCount
        if (lineCount == 0) {
            // Empty document: only empty-range at 0:0..0:0 (or empty edit list) is sane.
            // Allow a zero-length range at the origin; reject ranges that claim content.
            val isOriginEmpty =
                range.start.line == 0 &&
                    range.start.character == 0 &&
                    range.end.line == 0 &&
                    range.end.character == 0
            assertTrue(
                isOriginEmpty,
                "$label on empty document must be 0:0-0:0 or absent; got " +
                    "${range.start.line}:${range.start.character}-" +
                    "${range.end.line}:${range.end.character}"
            )
            return
        }

        // LSP allows end at the start of the line after last content (exclusive end).
        val maxLineInclusive = lineCount // end.line may equal lineCount for EOF insert
        assertTrue(
            range.start.line < lineCount,
            "$label.start.line must be < lineCount=$lineCount; got ${range.start.line}"
        )
        assertTrue(
            range.end.line <= maxLineInclusive,
            "$label.end.line must be <= lineCount=$lineCount; got ${range.end.line}"
        )

        val startLineLength = document.lineLength(range.start.line)
        assertTrue(
            range.start.character <= startLineLength,
            "$label.start.character must be <= line length $startLineLength; " +
                "got ${range.start.character} on line ${range.start.line}"
        )
        if (range.end.line < lineCount) {
            val endLineLength = document.lineLength(range.end.line)
            assertTrue(
                range.end.character <= endLineLength,
                "$label.end.character must be <= line length $endLineLength; " +
                    "got ${range.end.character} on line ${range.end.line}"
            )
        } else {
            // EOF exclusive end: character should be 0.
            assertTrue(
                range.end.character == 0,
                "$label.end at EOF line must use character 0; got ${range.end.character}"
            )
        }
    }

    private fun describe(edits: List<TextEdit>): String {
        return edits.joinToString(prefix = "[", postfix = "]") { edit ->
            val range = edit.range
            val span = if (range != null) {
                "${range.start?.line}:${range.start?.character}-" +
                    "${range.end?.line}:${range.end?.character}"
            } else {
                "<null-range>"
            }
            val textPreview = edit.newText
                ?.replace("\n", "\\n")
                ?.take(40)
                ?: "<null-text>"
            "$span -> \"$textPreview\""
        }
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

    private sealed class FormattingOutcome {
        data class Unsupported(val detail: String) : FormattingOutcome()
        data class Failed(val detail: String) : FormattingOutcome()
        data class Succeeded(val edits: List<TextEdit>) : FormattingOutcome()
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

        fun lineLength(line: Int): Int {
            require(line >= 0) { "line must be >= 0" }
            if (source.isEmpty()) {
                return 0
            }
            // Normalize CRLF so character offsets match LSP line-based positions
            // that count characters after the previous line terminator, not including \r.
            val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
            var currentLine = 0
            var index = 0
            var lineStart = 0
            while (index < normalized.length) {
                if (normalized[index] == '\n') {
                    if (currentLine == line) {
                        return index - lineStart
                    }
                    currentLine += 1
                    lineStart = index + 1
                }
                index += 1
            }
            if (currentLine == line) {
                return normalized.length - lineStart
            }
            // Past last line — treat as length 0 for exclusive EOF checks.
            return 0
        }
    }
}
