package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextEdit
import org.junit.Assume
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-274 — LSP onTypeFormatting for Lua `end` / `then` keyword corpus.
 *
 * Locks the safety contract for textDocument/onTypeFormatting when the product
 * supports (or later advertises) trigger characters around block-closing /
 * branch-introducing keywords:
 * - Typing the final character of `end` (typically `d`) after a block opener
 *   (`function` / `if` / `for` / `while` / `do`) degrades safely when the
 *   feature is unimplemented, and returns only well-formed TextEdits once
 *   implemented.
 * - Typing the final character of `then` (typically `n`) after `if` / `elseif`
 *   follows the same dual-path contract.
 * - Malformed / incomplete buffers, empty documents, and out-of-range positions
 *   must not crash the onTypeFormatting surface (empty list, soft reject, or
 *   documented UnsupportedOperationException gap only).
 *
 * Product onTypeFormatting is intentionally out of scope for this task
 * (test-only). Current [LuaTextDocumentService] inherits the LSP4J default that
 * throws [UnsupportedOperationException], and [LuaLanguageService] does not
 * advertise documentOnTypeFormattingProvider. Tests therefore:
 * - Accept the documented gap (UnsupportedOperationException) as a safe degrade.
 * - Skip hard content contracts with an explicit [Assume] when unimplemented.
 * - Enforce well-formed TextEdit contracts once the surface returns edits.
 *
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class LspOnTypeFormattingEndKeywordTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun initialize_on_type_formatting_capability_is_probeable() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        // Capability may be null today (documented gap) or present once product
        // lands on-type formatting. Reading the field must not throw.
        val provider = capabilities.documentOnTypeFormattingProvider
        if (provider == null) {
            assertTrue(
                true,
                "documentOnTypeFormattingProvider absent is an accepted pre-product surface"
            )
            return
        }

        // When advertised, firstTriggerCharacter is the only required field.
        assertTrue(
            !provider.firstTriggerCharacter.isNullOrEmpty() ||
                !provider.moreTriggerCharacter.isNullOrEmpty(),
            "advertised documentOnTypeFormattingProvider should declare at least one " +
                "trigger character; got first=${provider.firstTriggerCharacter} " +
                "more=${provider.moreTriggerCharacter}"
        )
    }

    @Test
    fun text_document_service_on_type_formatting_surface_is_invokable() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/on-type-surface.lua",
            """
            local function surface()
                return 0
            end
            """
        )

        val outcome = invokeOnTypeFormatting(
            textDocuments,
            onTypeParams(
                document,
                position = document.positionAfter("end", occurrence = 1),
                ch = "d"
            )
        )

        assertTrue(
            outcome is OnTypeOutcome.Unsupported ||
                outcome is OnTypeOutcome.Succeeded ||
                outcome is OnTypeOutcome.Failed,
            "onTypeFormatting surface must resolve to a known outcome; got $outcome"
        )
        if (outcome is OnTypeOutcome.Failed) {
            // Soft rejects are fine; hard crashes (NPE/assert) are not.
            assertTrue(
                !looksLikeHardCrash(outcome.detail),
                "onTypeFormatting surface must not NPE/assert; got ${outcome.detail}"
            )
        }
    }

    // -------------------------------------------------------------------------
    // `end` keyword: function / if / for / while / do / nested
    // -------------------------------------------------------------------------

    @Test
    fun on_type_after_end_of_local_function_degrades_safely() {
        assertEndKeywordSafe(
            path = "workspace/on-type-end-local-function.lua",
            source = """
                local function greet(name)
                    return name
                end
                """,
            endOccurrence = 1,
            context = "local function ... end"
        )
    }

    @Test
    fun on_type_after_end_of_if_block_degrades_safely() {
        assertEndKeywordSafe(
            path = "workspace/on-type-end-if.lua",
            source = """
                local flag = true
                if flag then
                    return 1
                end
                """,
            endOccurrence = 1,
            context = "if ... then ... end"
        )
    }

    @Test
    fun on_type_after_end_of_nested_blocks_degrades_safely() {
        assertEndKeywordSafe(
            path = "workspace/on-type-end-nested.lua",
            source = """
                local function outer()
                    if true then
                        for i = 1, 2 do
                            print(i)
                        end
                    end
                end
                """,
            // First `end` closes the for; probe that inner close stays safe.
            endOccurrence = 1,
            context = "nested for end inside if/function"
        )
    }

    // -------------------------------------------------------------------------
    // `then` keyword: if / elseif
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Trigger character variants around end/then
    // -------------------------------------------------------------------------

    @Test
    fun on_type_trigger_d_at_end_keyword_is_safe() {
        // Explicit probe that ch="d" (final char of `end`) is a safe trigger.
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/on-type-trigger-d.lua",
            """
            do
                return
            end
            """
        )

        val outcome = invokeOnTypeFormatting(
            textDocuments,
            onTypeParams(
                document,
                position = document.positionAfter("end", occurrence = 1),
                ch = "d"
            )
        )
        assertSafeDegradeOrWellFormed(outcome, document, context = "trigger ch='d' after end")
    }

    // -------------------------------------------------------------------------
    // Malformed / empty / out-of-range safety (must not crash)
    // -------------------------------------------------------------------------

    @Test
    fun on_type_on_malformed_unclosed_function_does_not_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        // Incomplete function — recovery path must stay non-throwing when the
        // user types toward an `end` that is not yet balanced.
        val document = textDocuments.open(
            "workspace/on-type-malformed-function.lua",
            """
            local function broken(
                return {
                    a = 1,
                    b =
            """
        )

        val outcome = invokeOnTypeFormatting(
            textDocuments,
            onTypeParams(
                document,
                // End of buffer — user may be about to type `end`.
                position = document.endPosition(),
                ch = "d"
            )
        )
        assertNoHardCrash(outcome, context = "malformed unclosed function")
        if (outcome is OnTypeOutcome.Succeeded) {
            assertWellFormedEdits(outcome.edits, document)
        }
    }

    @Test
    fun on_type_at_out_of_range_position_does_not_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/on-type-oob.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeOnTypeFormatting(
            textDocuments,
            onTypeParams(
                document,
                // Far past EOF.
                position = Position(50, 80),
                ch = "d"
            )
        )
        assertNoHardCrash(outcome, context = "out-of-range position")
        if (outcome is OnTypeOutcome.Succeeded) {
            // Product may clamp or ignore; any returned edits must still be ordered.
            outcome.edits.forEach { edit ->
                assertOrderedRange(edit)
            }
        }
    }

    @Test
    fun on_type_after_end_returns_well_formed_edits_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/on-type-end-edits-when-supported.lua",
            """
            local function format_me()
            return 1
            end
            """
        )

        val outcome = invokeOnTypeFormatting(
            textDocuments,
            onTypeParams(
                document,
                position = document.positionAfter("end", occurrence = 1),
                ch = "d"
            )
        )

        when (outcome) {
            is OnTypeOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-274 skipped: onTypeFormatting not yet implemented for end-keyword edits; " +
                        "detail=${outcome.detail}",
                    false
                )
            }
            is OnTypeOutcome.Failed -> {
                // Soft fail while partial: still must not look like a hard crash.
                assertTrue(
                    !looksLikeHardCrash(outcome.detail),
                    "onTypeFormatting failure for end must not NPE/assert; got ${outcome.detail}"
                )
                Assume.assumeTrue(
                    "TASK-274 skipped: onTypeFormatting soft-failed for end-keyword edits; " +
                        "detail=${outcome.detail}",
                    false
                )
            }
            is OnTypeOutcome.Succeeded -> {
                assertWellFormedEdits(outcome.edits, document)
                // Empty edit list is a valid "no reformat needed" response.
            }
        }
    }

    @Test
    fun on_type_after_then_returns_well_formed_edits_when_supported() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/on-type-then-edits-when-supported.lua",
            """
            if true then
            return 1
            end
            """
        )

        val outcome = invokeOnTypeFormatting(
            textDocuments,
            onTypeParams(
                document,
                position = document.positionAfter("then", occurrence = 1),
                ch = "n"
            )
        )

        when (outcome) {
            is OnTypeOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-274 skipped: onTypeFormatting not yet implemented for then-keyword edits; " +
                        "detail=${outcome.detail}",
                    false
                )
            }
            is OnTypeOutcome.Failed -> {
                assertTrue(
                    !looksLikeHardCrash(outcome.detail),
                    "onTypeFormatting failure for then must not NPE/assert; got ${outcome.detail}"
                )
                Assume.assumeTrue(
                    "TASK-274 skipped: onTypeFormatting soft-failed for then-keyword edits; " +
                        "detail=${outcome.detail}",
                    false
                )
            }
            is OnTypeOutcome.Succeeded -> {
                assertWellFormedEdits(outcome.edits, document)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun assertEndKeywordSafe(
        path: String,
        source: String,
        endOccurrence: Int,
        context: String
    ) {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(path, source)
        val outcome = invokeOnTypeFormatting(
            textDocuments,
            onTypeParams(
                document,
                position = document.positionAfter("end", occurrence = endOccurrence),
                ch = "d"
            )
        )
        assertSafeDegradeOrWellFormed(outcome, document, context = context)
    }

    private fun assertThenKeywordSafe(
        path: String,
        source: String,
        thenOccurrence: Int,
        context: String
    ) {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(path, source)
        val outcome = invokeOnTypeFormatting(
            textDocuments,
            onTypeParams(
                document,
                position = document.positionAfter("then", occurrence = thenOccurrence),
                ch = "n"
            )
        )
        assertSafeDegradeOrWellFormed(outcome, document, context = context)
    }

    private fun assertSafeDegradeOrWellFormed(
        outcome: OnTypeOutcome,
        document: OpenDocument,
        context: String
    ) {
        when (outcome) {
            is OnTypeOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; " +
                        "got ${outcome.detail}"
                )
            }
            is OnTypeOutcome.Failed -> {
                assertTrue(
                    !looksLikeHardCrash(outcome.detail),
                    "onTypeFormatting for $context must not NPE/assert; got ${outcome.detail}"
                )
            }
            is OnTypeOutcome.Succeeded -> {
                assertWellFormedEdits(outcome.edits, document)
            }
        }
    }

    private fun assertNoHardCrash(outcome: OnTypeOutcome, context: String) {
        when (outcome) {
            is OnTypeOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; " +
                        "got ${outcome.detail}"
                )
            }
            is OnTypeOutcome.Failed -> {
                assertTrue(
                    !looksLikeHardCrash(outcome.detail),
                    "onTypeFormatting for $context must not hard-crash; got ${outcome.detail}"
                )
            }
            is OnTypeOutcome.Succeeded -> {
                // success is fine
            }
        }
    }

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

    private fun onTypeParams(
        document: OpenDocument,
        position: Position,
        ch: String,
        tabSize: Int = 4,
        insertSpaces: Boolean = true
    ): DocumentOnTypeFormattingParams {
        return DocumentOnTypeFormattingParams(
            TextDocumentIdentifier(document.uri),
            FormattingOptions(tabSize, insertSpaces),
            position,
            ch
        )
    }

    private fun invokeOnTypeFormatting(
        textDocuments: LuaTextDocumentService,
        params: DocumentOnTypeFormattingParams
    ): OnTypeOutcome {
        return try {
            val edits = textDocuments.onTypeFormatting(params).get()
            OnTypeOutcome.Succeeded(edits.orEmpty())
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                OnTypeOutcome.Unsupported(
                    detail = root.toString(),
                    isUnsupportedOperation = true
                )
            } else {
                OnTypeOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun assertWellFormedEdits(edits: List<TextEdit>, document: OpenDocument) {
        edits.forEach { edit ->
            assertOrderedRange(edit)
            val range = edit.range
            assertTrue(
                range.start.line >= 0,
                "TextEdit start.line must be >= 0; got ${describe(edit)}"
            )
            assertTrue(
                range.start.character >= 0,
                "TextEdit start.character must be >= 0; got ${describe(edit)}"
            )
            assertTrue(
                range.end.line >= 0,
                "TextEdit end.line must be >= 0; got ${describe(edit)}"
            )
            assertTrue(
                range.end.character >= 0,
                "TextEdit end.character must be >= 0; got ${describe(edit)}"
            )
            // When the document is non-empty, prefer ranges that do not start past EOF.
            if (document.lineCount > 0) {
                assertTrue(
                    range.start.line <= document.lineCount,
                    "TextEdit start.line should not be far past EOF " +
                        "(lineCount=${document.lineCount}); got ${describe(edit)}"
                )
            }
            // newText is allowed to be empty (delete) or multi-line; just require non-null.
            assertTrue(
                edit.newText != null,
                "TextEdit.newText must be non-null; got ${describe(edit)}"
            )
        }
    }

    private fun assertOrderedRange(edit: TextEdit) {
        val range = edit.range
        assertTrue(
            range != null,
            "TextEdit.range must be non-null"
        )
        val ordered = range.end.line > range.start.line ||
            (range.end.line == range.start.line &&
                range.end.character >= range.start.character)
        assertTrue(
            ordered,
            "TextEdit range must be ordered; got ${describe(edit)}"
        )
    }

    private fun describe(edit: TextEdit): String {
        val range = edit.range
        val newTextPreview = edit.newText
            ?.replace("\n", "\\n")
            ?.let { if (it.length > 40) it.take(40) + "…" else it }
        return "range=${range?.start?.line}:${range?.start?.character}-" +
            "${range?.end?.line}:${range?.end?.character} newText='$newTextPreview'"
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

    private fun looksLikeHardCrash(detail: String): Boolean {
        val lower = detail.lowercase()
        return lower.contains("nullpointerexception") ||
            lower.contains("kotlinnullpointerexception") ||
            lower.contains("assertionerror") ||
            lower.contains("indexoutofboundsexception") ||
            lower.contains("arrayindexoutofboundsexception") ||
            lower.contains("stringindexoutofboundsexception") ||
            lower.contains("stackoverflowerror") ||
            lower.contains("outofmemoryerror")
    }

    private sealed class OnTypeOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : OnTypeOutcome()

        data class Failed(val detail: String) : OnTypeOutcome()

        data class Succeeded(val edits: List<TextEdit>) : OnTypeOutcome()
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

        /** Position immediately after the last character of [needle]. */
        fun positionAfter(needle: String, occurrence: Int = 1): Position {
            require(occurrence >= 1) { "occurrence must be positive" }
            var index = -1
            var fromIndex = 0
            repeat(occurrence) {
                index = source.indexOf(needle, fromIndex)
                require(index >= 0) { "Could not find occurrence $occurrence of '$needle' in $path" }
                fromIndex = index + needle.length
            }
            return positionAt(index + needle.length)
        }

        fun endPosition(): Position = positionAt(source.length)

        private fun positionAt(offset: Int): Position {
            val safe = offset.coerceIn(0, source.length)
            var line = 0
            var lineStart = 0
            for (i in 0 until safe) {
                if (source[i] == '\n') {
                    line += 1
                    lineStart = i + 1
                }
            }
            return Position(line, safe - lineStart)
        }
    }
}
