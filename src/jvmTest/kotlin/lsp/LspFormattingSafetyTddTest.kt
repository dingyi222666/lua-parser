package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextEdit
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-494 — LSP formatting safety dual-path corpus.
 *
 * Complements the specialized formatting corpora:
 * - [LspFormattingFullDocumentSafetyTddTest] (TASK-275 full-document)
 * - [LspRangeFormattingTableBodyTddTest] (TASK-276 range / table body)
 * - [LspOnTypeFormattingEndKeywordTddTest] (TASK-274 onType end/then)
 *
 * with an explicit dual-path inventory that covers all three formatting
 * surfaces together under one safety contract:
 *
 * Hard contracts once product implements formatting:
 * - textDocument/formatting, rangeFormatting, and onTypeFormatting must not
 *   crash (NPE / AssertionError / IndexOutOfBounds / StackOverflow).
 * - Successful responses yield empty or well-formed [TextEdit] lists only
 *   (ordered ranges, non-null newText, ranges inside the open document).
 * - Syntax-error / empty / missing / OOB inputs degrade to empty edits or a
 *   soft failure detail — never process-killing exceptions.
 *
 * Dual-path / CURRENTLY_ACCEPTS product gaps (never hard-fail the suite):
 * - [LuaTextDocumentService] inherits LSP4J defaults → UnsupportedOperationException
 *   for formatting / rangeFormatting / onTypeFormatting until product lands.
 * - Server capabilities currently omit documentFormattingProvider,
 *   documentRangeFormattingProvider, and documentOnTypeFormattingProvider.
 * - Capability may lag implementation; once edits are returned they must still
 *   be well-formed even if the initialize flag is still null.
 *
 * Test-only; no product edits. Verification is review-owned and serial; this
 * worker does not run Gradle. Host android.jar paths (not required by this
 * corpus):
 * - /Users/dingyi/Downloads/android.jar
 * - /Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
 * Never G:/.
 */
class LspFormattingSafetyTddTest {

    // -------------------------------------------------------------------------
    // Capability dual-path probes
    // -------------------------------------------------------------------------

    @Test
    fun document_formatting_capability_is_absent_or_enabled_when_advertised() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val provider = capabilities.documentFormattingProvider

        if (provider == null) {
            // CURRENTLY_ACCEPTS: product does not advertise documentFormatting yet.
            return
        }

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
    fun range_formatting_capability_is_absent_or_enabled_when_advertised() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val provider = capabilities.documentRangeFormattingProvider

        if (provider == null) {
            // CURRENTLY_ACCEPTS: product does not advertise rangeFormatting yet.
            return
        }

        val enabled = when {
            provider.isLeft -> provider.left == true
            provider.isRight -> provider.right != null
            else -> false
        }
        assertTrue(
            enabled,
            "advertised documentRangeFormattingProvider must enable range formatting; got $provider"
        )
    }

    @Test
    fun on_type_formatting_capability_is_absent_or_declares_triggers_when_advertised() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val provider = capabilities.documentOnTypeFormattingProvider

        if (provider == null) {
            // CURRENTLY_ACCEPTS: product does not advertise onTypeFormatting yet.
            return
        }

        assertTrue(
            !provider.firstTriggerCharacter.isNullOrEmpty() ||
                !provider.moreTriggerCharacter.isNullOrEmpty(),
            "advertised documentOnTypeFormattingProvider should declare at least one " +
                "trigger character; got first=${provider.firstTriggerCharacter} " +
                "more=${provider.moreTriggerCharacter}"
        )
    }

    // -------------------------------------------------------------------------
    // Surface invokability dual-path (all three methods)
    // -------------------------------------------------------------------------

    @Test
    fun document_formatting_surface_is_invokable_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/fmt-safety-doc-surface.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeDocumentFormatting(
            textDocuments,
            documentFormattingParams(document)
        )
        assertKnownOutcome(outcome, context = "documentFormatting surface")
        if (outcome is FormatOutcome.Succeeded) {
            assertWellFormedEdits(outcome.edits, document)
        }
    }

    @Test
    fun range_formatting_surface_is_invokable_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/fmt-safety-range-surface.lua",
            """
            local t = {
                a = 1,
                b = 2
            }
            return t
            """
        )

        val outcome = invokeRangeFormatting(
            textDocuments,
            rangeFormattingParams(
                document,
                Range(Position(0, 10), Position(3, 1))
            )
        )
        assertKnownOutcome(outcome, context = "rangeFormatting surface")
        if (outcome is FormatOutcome.Succeeded) {
            assertWellFormedEdits(outcome.edits, document)
        }
    }

    @Test
    fun on_type_formatting_surface_is_invokable_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/fmt-safety-ontype-surface.lua",
            """
            local function f()
                return 1
            end
            """
        )

        val outcome = invokeOnTypeFormatting(
            textDocuments,
            onTypeFormattingParams(
                document,
                position = document.positionAfter("end", occurrence = 1),
                ch = "d"
            )
        )
        assertKnownOutcome(outcome, context = "onTypeFormatting surface")
        if (outcome is FormatOutcome.Succeeded) {
            assertWellFormedEdits(outcome.edits, document)
        }
    }

    @Test
    fun document_formatting_can_be_invoked_twice_without_killing_service() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/fmt-safety-doc-twice.lua",
            "local a = 1\nlocal b = 2\nreturn a + b"
        )
        val params = documentFormattingParams(document)

        val first = invokeDocumentFormatting(textDocuments, params)
        val second = invokeDocumentFormatting(textDocuments, params)

        assertNoHardCrash(first, context = "documentFormatting first call")
        assertNoHardCrash(second, context = "documentFormatting second call")
        if (first is FormatOutcome.Succeeded) {
            assertWellFormedEdits(first.edits, document)
        }
        if (second is FormatOutcome.Succeeded) {
            assertWellFormedEdits(second.edits, document)
        }
    }

    // -------------------------------------------------------------------------
    // Full-document formatting dual-path shapes
    // -------------------------------------------------------------------------

    @Test
    fun document_formatting_valid_function_dual_path() {
        assertDocumentFormattingSafe(
            path = "workspace/fmt-safety-doc-function.lua",
            source = """
                local function greet(name)
                    return "hi " .. name
                end
                return greet("world")
            """,
            context = "valid multi-line function"
        )
    }

    @Test
    fun document_formatting_compact_source_dual_path() {
        assertDocumentFormattingSafe(
            path = "workspace/fmt-safety-doc-compact.lua",
            source = "local x=1;local y=2;return x+y",
            context = "compact single-line source"
        )
    }

    @Test
    fun document_formatting_mixed_indent_dual_path() {
        assertDocumentFormattingSafe(
            path = "workspace/fmt-safety-doc-mixed-indent.lua",
            source = "local function f()\n\tlocal a = 1\n  local b = 2\n\treturn a + b\nend\nreturn f",
            context = "mixed tab/space indentation"
        )
    }

    @Test
    fun document_formatting_table_constructor_dual_path() {
        assertDocumentFormattingSafe(
            path = "workspace/fmt-safety-doc-table.lua",
            source = """
                local config = {
                  enabled=true,
                    retries =3,
                label = "demo"
                }
                return config
            """,
            context = "uneven multi-line table constructor"
        )
    }

    @Test
    fun document_formatting_crlf_source_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.openRaw(
            "workspace/fmt-safety-doc-crlf.lua",
            "local function f()\r\n    return 1\r\nend\r\nreturn f()\r\n"
        )
        val outcome = invokeDocumentFormatting(
            textDocuments,
            documentFormattingParams(document)
        )
        assertNoHardCrash(outcome, context = "CRLF line endings")
        if (outcome is FormatOutcome.Succeeded) {
            assertWellFormedEdits(outcome.edits, document)
        }
    }

    @Test
    fun document_formatting_syntax_error_unclosed_function_dual_path() {
        assertDocumentFormattingSafe(
            path = "workspace/fmt-safety-doc-unclosed-fn.lua",
            source = """
                local function broken(
                    return 1
            """,
            context = "syntax-error unclosed function"
        )
    }

    @Test
    fun document_formatting_syntax_error_stray_tokens_dual_path() {
        assertDocumentFormattingSafe(
            path = "workspace/fmt-safety-doc-stray.lua",
            source = "end end local = 1 then if\nreturn",
            context = "syntax-error stray keywords"
        )
    }

    @Test
    fun document_formatting_empty_document_dual_path() {
        assertDocumentFormattingSafe(
            path = "workspace/fmt-safety-doc-empty.lua",
            source = "",
            context = "empty document"
        )
    }

    @Test
    fun document_formatting_whitespace_only_dual_path() {
        assertDocumentFormattingSafe(
            path = "workspace/fmt-safety-doc-ws.lua",
            source = "   \n\t\n  \n",
            context = "whitespace-only document"
        )
    }

    @Test
    fun document_formatting_large_document_dual_path() {
        val body = buildString {
            appendLine("local module = {}")
            repeat(120) { index ->
                appendLine("function module.fn$index(x)")
                appendLine("  local y = x + $index")
                appendLine("  return y")
                appendLine("end")
            }
            appendLine("return module")
        }
        assertDocumentFormattingSafe(
            path = "workspace/fmt-safety-doc-large.lua",
            source = body,
            context = "large multi-function document"
        )
    }

    @Test
    fun document_formatting_missing_uri_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val params = DocumentFormattingParams(
            TextDocumentIdentifier("file:///workspace/fmt-safety-doc-missing.lua"),
            FormattingOptions(4, true)
        )
        val outcome = invokeDocumentFormatting(textDocuments, params)
        when (outcome) {
            is FormatOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for missing-uri documentFormatting expects " +
                        "UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is FormatOutcome.Failed -> {
                assertFalse(
                    looksLikeHardCrash(outcome.detail),
                    "missing-uri documentFormatting must not hard-crash; got ${outcome.detail}"
                )
            }
            is FormatOutcome.Succeeded -> {
                assertTrue(
                    outcome.edits.isEmpty(),
                    "missing document should yield empty edits; got ${describe(outcome.edits)}"
                )
            }
        }
    }

    @Test
    fun document_formatting_custom_options_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/fmt-safety-doc-options.lua",
            """
            local function nested()
            if true then
            return 1
            end
            end
            return nested
            """
        )

        val spaces = invokeDocumentFormatting(
            textDocuments,
            documentFormattingParams(document, FormattingOptions(4, true))
        )
        val tabs = invokeDocumentFormatting(
            textDocuments,
            documentFormattingParams(document, FormattingOptions(2, false))
        )

        assertNoHardCrash(spaces, context = "documentFormatting tabSize=4 insertSpaces=true")
        assertNoHardCrash(tabs, context = "documentFormatting tabSize=2 insertSpaces=false")
        if (spaces is FormatOutcome.Succeeded) {
            assertWellFormedEdits(spaces.edits, document)
        }
        if (tabs is FormatOutcome.Succeeded) {
            assertWellFormedEdits(tabs.edits, document)
        }
    }

    // -------------------------------------------------------------------------
    // Range formatting dual-path shapes
    // -------------------------------------------------------------------------

    @Test
    fun range_formatting_table_body_dual_path() {
        assertRangeFormattingSafe(
            path = "workspace/fmt-safety-range-table.lua",
            source = """
                local config = {
                    enabled = true,
                    retries = 3
                }
                return config
            """,
            range = Range(Position(0, 15), Position(3, 1)),
            context = "table constructor body range"
        )
    }

    @Test
    fun range_formatting_function_body_dual_path() {
        assertRangeFormattingSafe(
            path = "workspace/fmt-safety-range-function.lua",
            source = """
                local function paint(x)
                return x
                end
                return paint
            """,
            range = Range(Position(0, 0), Position(2, 3)),
            context = "function body range"
        )
    }

    @Test
    fun range_formatting_nested_table_dual_path() {
        assertRangeFormattingSafe(
            path = "workspace/fmt-safety-range-nested.lua",
            source = """
                local t = {
                  outer = {
                    inner = 1,
                  },
                }
                return t
            """,
            range = Range(Position(0, 10), Position(4, 1)),
            context = "nested table range"
        )
    }

    @Test
    fun range_formatting_inverted_range_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/fmt-safety-range-inverted.lua",
            "local value = 1\nreturn value"
        )
        val outcome = invokeRangeFormatting(
            textDocuments,
            rangeFormattingParams(
                document,
                // Inverted: end before start.
                Range(Position(1, 5), Position(0, 0))
            )
        )
        assertNoHardCrash(outcome, context = "inverted range")
        if (outcome is FormatOutcome.Succeeded) {
            // Ideal: empty or well-formed only — never invent inverted edit ranges.
            assertWellFormedEdits(outcome.edits, document)
        }
    }

    @Test
    fun range_formatting_oob_range_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/fmt-safety-range-oob.lua",
            "local value = 1\nreturn value"
        )
        val outcome = invokeRangeFormatting(
            textDocuments,
            rangeFormattingParams(
                document,
                Range(Position(40, 0), Position(50, 10))
            )
        )
        assertNoHardCrash(outcome, context = "out-of-bounds range")
        if (outcome is FormatOutcome.Succeeded) {
            // Product may clamp; returned edits must still be ordered.
            outcome.edits.forEach { edit ->
                assertOrderedRange(edit.range, label = "OOB TextEdit")
            }
        }
    }

    @Test
    fun range_formatting_empty_range_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/fmt-safety-range-empty.lua",
            "local value = 1\nreturn value"
        )
        val outcome = invokeRangeFormatting(
            textDocuments,
            rangeFormattingParams(
                document,
                Range(Position(0, 6), Position(0, 6))
            )
        )
        assertNoHardCrash(outcome, context = "empty zero-width range")
        if (outcome is FormatOutcome.Succeeded) {
            assertWellFormedEdits(outcome.edits, document)
        }
    }

    @Test
    fun range_formatting_malformed_table_dual_path() {
        assertRangeFormattingSafe(
            path = "workspace/fmt-safety-range-malformed.lua",
            source = """
                local t = {
                    a = 1,
                    b =
                return t
            """,
            range = Range(Position(0, 10), Position(3, 1)),
            context = "malformed unclosed table range"
        )
    }

    @Test
    fun range_formatting_missing_uri_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val params = DocumentRangeFormattingParams(
            TextDocumentIdentifier("file:///workspace/fmt-safety-range-missing.lua"),
            FormattingOptions(4, true),
            Range(Position(0, 0), Position(0, 1))
        )
        val outcome = invokeRangeFormatting(textDocuments, params)
        when (outcome) {
            is FormatOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for missing-uri rangeFormatting expects " +
                        "UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is FormatOutcome.Failed -> {
                assertFalse(
                    looksLikeHardCrash(outcome.detail),
                    "missing-uri rangeFormatting must not hard-crash; got ${outcome.detail}"
                )
            }
            is FormatOutcome.Succeeded -> {
                assertTrue(
                    outcome.edits.isEmpty(),
                    "missing document rangeFormatting should yield empty edits; " +
                        "got ${describe(outcome.edits)}"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // On-type formatting dual-path shapes
    // -------------------------------------------------------------------------

    @Test
    fun on_type_after_end_keyword_dual_path() {
        assertOnTypeFormattingSafe(
            path = "workspace/fmt-safety-ontype-end.lua",
            source = """
                local function f()
                    return 1
                end
            """,
            positionOf = { it.positionAfter("end", occurrence = 1) },
            ch = "d",
            context = "onType after end keyword"
        )
    }

    @Test
    fun on_type_after_then_keyword_dual_path() {
        assertOnTypeFormattingSafe(
            path = "workspace/fmt-safety-ontype-then.lua",
            source = """
                if true then
                    return 1
                end
            """,
            positionOf = { it.positionAfter("then", occurrence = 1) },
            ch = "n",
            context = "onType after then keyword"
        )
    }

    @Test
    fun on_type_after_do_block_end_dual_path() {
        assertOnTypeFormattingSafe(
            path = "workspace/fmt-safety-ontype-do-end.lua",
            source = """
                do
                    return
                end
            """,
            positionOf = { it.positionAfter("end", occurrence = 1) },
            ch = "d",
            context = "onType after do-block end"
        )
    }

    @Test
    fun on_type_after_for_end_dual_path() {
        assertOnTypeFormattingSafe(
            path = "workspace/fmt-safety-ontype-for-end.lua",
            source = """
                for i = 1, 3 do
                    print(i)
                end
            """,
            positionOf = { it.positionAfter("end", occurrence = 1) },
            ch = "d",
            context = "onType after for-loop end"
        )
    }

    @Test
    fun on_type_partial_end_token_dual_path() {
        assertOnTypeFormattingSafe(
            path = "workspace/fmt-safety-ontype-partial-end.lua",
            source = """
                local function f()
                    return 1
                en
            """,
            positionOf = { it.positionAfter("en", occurrence = 1) },
            ch = "d",
            context = "onType finishing partial 'en' toward end"
        )
    }

    @Test
    fun on_type_empty_document_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/fmt-safety-ontype-empty.lua",
            ""
        )
        val outcome = invokeOnTypeFormatting(
            textDocuments,
            onTypeFormattingParams(document, Position(0, 0), ch = "d")
        )
        assertNoHardCrash(outcome, context = "onType empty document")
        if (outcome is FormatOutcome.Succeeded) {
            assertWellFormedEdits(outcome.edits, document)
        }
    }

    @Test
    fun on_type_malformed_unclosed_function_dual_path() {
        assertOnTypeFormattingSafe(
            path = "workspace/fmt-safety-ontype-malformed.lua",
            source = """
                local function broken(
                    return {
                        a = 1,
                        b =
            """,
            positionOf = { it.endPosition() },
            ch = "d",
            context = "onType malformed unclosed function"
        )
    }

    @Test
    fun on_type_out_of_range_position_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/fmt-safety-ontype-oob.lua",
            "local value = 1\nreturn value"
        )
        val outcome = invokeOnTypeFormatting(
            textDocuments,
            onTypeFormattingParams(document, Position(50, 80), ch = "d")
        )
        assertNoHardCrash(outcome, context = "onType out-of-range position")
        if (outcome is FormatOutcome.Succeeded) {
            outcome.edits.forEach { edit ->
                assertOrderedRange(edit.range, label = "OOB onType TextEdit")
            }
        }
    }

    @Test
    fun on_type_unknown_trigger_dual_path() {
        assertOnTypeFormattingSafe(
            path = "workspace/fmt-safety-ontype-unknown.lua",
            source = """
                local function f()
                    return 1
                end
            """,
            positionOf = { it.positionAfter("end", occurrence = 1) },
            ch = "z",
            context = "onType unknown trigger ch='z'"
        )
    }

    @Test
    fun on_type_missing_uri_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val params = DocumentOnTypeFormattingParams(
            TextDocumentIdentifier("file:///workspace/fmt-safety-ontype-missing.lua"),
            FormattingOptions(4, true),
            Position(0, 0),
            "d"
        )
        val outcome = invokeOnTypeFormatting(textDocuments, params)
        when (outcome) {
            is FormatOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for missing-uri onTypeFormatting expects " +
                        "UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is FormatOutcome.Failed -> {
                assertFalse(
                    looksLikeHardCrash(outcome.detail),
                    "missing-uri onTypeFormatting must not hard-crash; got ${outcome.detail}"
                )
            }
            is FormatOutcome.Succeeded -> {
                assertTrue(
                    outcome.edits.isEmpty(),
                    "missing document onTypeFormatting should yield empty edits; " +
                        "got ${describe(outcome.edits)}"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Explicit dual-path inventory table
    // -------------------------------------------------------------------------

    @Test
    fun formatting_dual_path_inventory_covers_all_surfaces() {
        val cases = listOf(
            InventoryCase(
                name = "doc-valid-function",
                surface = InventorySurface.DOCUMENT,
                source = "local function f()\n  return 1\nend\nreturn f",
                range = null,
                onTypeCh = null,
                onTypeNeedle = null
            ),
            InventoryCase(
                name = "doc-compact",
                surface = InventorySurface.DOCUMENT,
                source = "local x=1;return x",
                range = null,
                onTypeCh = null,
                onTypeNeedle = null
            ),
            InventoryCase(
                name = "doc-syntax-error",
                surface = InventorySurface.DOCUMENT,
                source = "function broken(\nreturn",
                range = null,
                onTypeCh = null,
                onTypeNeedle = null
            ),
            InventoryCase(
                name = "doc-empty",
                surface = InventorySurface.DOCUMENT,
                source = "",
                range = null,
                onTypeCh = null,
                onTypeNeedle = null
            ),
            InventoryCase(
                name = "range-table-body",
                surface = InventorySurface.RANGE,
                source = "local t = {\n  a = 1,\n  b = 2\n}\nreturn t",
                range = Range(Position(0, 10), Position(3, 1)),
                onTypeCh = null,
                onTypeNeedle = null
            ),
            InventoryCase(
                name = "range-function-body",
                surface = InventorySurface.RANGE,
                source = "local function f()\nreturn 1\nend\nreturn f",
                range = Range(Position(0, 0), Position(2, 3)),
                onTypeCh = null,
                onTypeNeedle = null
            ),
            InventoryCase(
                name = "range-inverted",
                surface = InventorySurface.RANGE,
                source = "local value = 1\nreturn value",
                range = Range(Position(1, 5), Position(0, 0)),
                onTypeCh = null,
                onTypeNeedle = null
            ),
            InventoryCase(
                name = "ontype-end",
                surface = InventorySurface.ON_TYPE,
                source = "local function f()\n  return 1\nend",
                range = null,
                onTypeCh = "d",
                onTypeNeedle = "end"
            ),
            InventoryCase(
                name = "ontype-then",
                surface = InventorySurface.ON_TYPE,
                source = "if true then\n  return 1\nend",
                range = null,
                onTypeCh = "n",
                onTypeNeedle = "then"
            ),
            InventoryCase(
                name = "ontype-unknown-trigger",
                surface = InventorySurface.ON_TYPE,
                source = "local function f()\n  return 1\nend",
                range = null,
                onTypeCh = "z",
                onTypeNeedle = "end"
            )
        )

        cases.forEach { case ->
            val service = service()
            val textDocuments = LuaTextDocumentService(service)
            val document = textDocuments.open(
                "workspace/fmt-safety-inventory-${case.name}.lua",
                case.source
            )
            val outcome = when (case.surface) {
                InventorySurface.DOCUMENT -> invokeDocumentFormatting(
                    textDocuments,
                    documentFormattingParams(document)
                )
                InventorySurface.RANGE -> invokeRangeFormatting(
                    textDocuments,
                    rangeFormattingParams(
                        document,
                        requireNotNull(case.range) { "range required for ${case.name}" }
                    )
                )
                InventorySurface.ON_TYPE -> {
                    val needle = requireNotNull(case.onTypeNeedle) {
                        "onTypeNeedle required for ${case.name}"
                    }
                    val ch = requireNotNull(case.onTypeCh) {
                        "onTypeCh required for ${case.name}"
                    }
                    invokeOnTypeFormatting(
                        textDocuments,
                        onTypeFormattingParams(
                            document,
                            position = document.positionAfter(needle, occurrence = 1),
                            ch = ch
                        )
                    )
                }
            }
            assertNoHardCrash(outcome, context = "inventory:${case.name}")
            if (outcome is FormatOutcome.Succeeded) {
                // For inverted / OOB-style inventory cases, only require ordered ranges.
                if (case.name.contains("inverted") || case.name.contains("oob")) {
                    outcome.edits.forEach { edit ->
                        assertOrderedRange(edit.range, label = "inventory:${case.name}")
                    }
                } else {
                    assertWellFormedEdits(outcome.edits, document)
                }
            }
        }
    }

    @Test
    fun formatting_capability_inventory_is_readable_without_throw() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        assertNotNull(capabilities, "initialize must return ServerCapabilities")

        // Reading all three formatting capability fields must never throw.
        val doc = capabilities.documentFormattingProvider
        val range = capabilities.documentRangeFormattingProvider
        val onType = capabilities.documentOnTypeFormattingProvider

        // Dual-path: any combination of null / present is CURRENTLY_ACCEPTS.
        // Soft inventory only — product may land them independently.
        assertTrue(
            true,
            "formatting capabilities readable: doc=$doc range=$range onType=$onType"
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

    private fun documentFormattingParams(
        document: OpenDocument,
        options: FormattingOptions = FormattingOptions(4, true)
    ): DocumentFormattingParams {
        return DocumentFormattingParams(
            TextDocumentIdentifier(document.uri),
            options
        )
    }

    private fun rangeFormattingParams(
        document: OpenDocument,
        range: Range,
        options: FormattingOptions = FormattingOptions(4, true)
    ): DocumentRangeFormattingParams {
        return DocumentRangeFormattingParams(
            TextDocumentIdentifier(document.uri),
            options,
            range
        )
    }

    private fun onTypeFormattingParams(
        document: OpenDocument,
        position: Position,
        ch: String,
        options: FormattingOptions = FormattingOptions(4, true)
    ): DocumentOnTypeFormattingParams {
        return DocumentOnTypeFormattingParams(
            TextDocumentIdentifier(document.uri),
            options,
            position,
            ch
        )
    }

    private fun assertDocumentFormattingSafe(
        path: String,
        source: String,
        context: String
    ) {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(path, source)
        val outcome = invokeDocumentFormatting(
            textDocuments,
            documentFormattingParams(document)
        )
        assertNoHardCrash(outcome, context = "documentFormatting $context")
        if (outcome is FormatOutcome.Succeeded) {
            assertWellFormedEdits(outcome.edits, document)
        }
    }

    private fun assertRangeFormattingSafe(
        path: String,
        source: String,
        range: Range,
        context: String
    ) {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(path, source)
        val outcome = invokeRangeFormatting(
            textDocuments,
            rangeFormattingParams(document, range)
        )
        assertNoHardCrash(outcome, context = "rangeFormatting $context")
        if (outcome is FormatOutcome.Succeeded) {
            assertWellFormedEdits(outcome.edits, document)
        }
    }

    private fun assertOnTypeFormattingSafe(
        path: String,
        source: String,
        positionOf: (OpenDocument) -> Position,
        ch: String,
        context: String
    ) {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(path, source)
        val outcome = invokeOnTypeFormatting(
            textDocuments,
            onTypeFormattingParams(document, positionOf(document), ch)
        )
        assertNoHardCrash(outcome, context = "onTypeFormatting $context")
        if (outcome is FormatOutcome.Succeeded) {
            assertWellFormedEdits(outcome.edits, document)
        }
    }

    private fun invokeDocumentFormatting(
        textDocuments: LuaTextDocumentService,
        params: DocumentFormattingParams
    ): FormatOutcome {
        return try {
            val edits = textDocuments.formatting(params).get()
            FormatOutcome.Succeeded(edits.orEmpty())
        } catch (error: Throwable) {
            toOutcome(error)
        }
    }

    private fun invokeRangeFormatting(
        textDocuments: LuaTextDocumentService,
        params: DocumentRangeFormattingParams
    ): FormatOutcome {
        return try {
            val edits = textDocuments.rangeFormatting(params).get()
            FormatOutcome.Succeeded(edits.orEmpty())
        } catch (error: Throwable) {
            toOutcome(error)
        }
    }

    private fun invokeOnTypeFormatting(
        textDocuments: LuaTextDocumentService,
        params: DocumentOnTypeFormattingParams
    ): FormatOutcome {
        return try {
            val edits = textDocuments.onTypeFormatting(params).get()
            FormatOutcome.Succeeded(edits.orEmpty())
        } catch (error: Throwable) {
            toOutcome(error)
        }
    }

    private fun toOutcome(error: Throwable): FormatOutcome {
        val root = unwrap(error)
        return if (isUnsupportedOperation(root)) {
            FormatOutcome.Unsupported(
                detail = root.toString(),
                isUnsupportedOperation = true
            )
        } else {
            FormatOutcome.Failed(detail = root.toString())
        }
    }

    private fun assertKnownOutcome(outcome: FormatOutcome, context: String) {
        assertTrue(
            outcome is FormatOutcome.Unsupported ||
                outcome is FormatOutcome.Succeeded ||
                outcome is FormatOutcome.Failed,
            "$context must resolve to a known dual-path outcome; got $outcome"
        )
        if (outcome is FormatOutcome.Failed) {
            assertFalse(
                looksLikeHardCrash(outcome.detail),
                "$context must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is FormatOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "Documented gap for $context expects UnsupportedOperationException; " +
                    "got ${outcome.detail}"
            )
        }
    }

    private fun assertNoHardCrash(outcome: FormatOutcome, context: String) {
        when (outcome) {
            is FormatOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; " +
                        "got ${outcome.detail}"
                )
            }
            is FormatOutcome.Failed -> {
                assertFalse(
                    looksLikeHardCrash(outcome.detail),
                    "formatting for $context must not hard-crash; got ${outcome.detail}"
                )
            }
            is FormatOutcome.Succeeded -> {
                // success is fine
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
    }

    private fun assertOrderedRange(range: Range?, label: String) {
        assertNotNull(range, "$label.range must be non-null")
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
        assertTrue(
            range.start.line < lineCount,
            "$label.start.line must be < lineCount=$lineCount; got ${range.start.line}"
        )
        assertTrue(
            range.end.line <= lineCount,
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

    private enum class InventorySurface {
        DOCUMENT,
        RANGE,
        ON_TYPE
    }

    private data class InventoryCase(
        val name: String,
        val surface: InventorySurface,
        val source: String,
        val range: Range?,
        val onTypeCh: String?,
        val onTypeNeedle: String?
    )

    private sealed class FormatOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : FormatOutcome()

        data class Failed(val detail: String) : FormatOutcome()

        data class Succeeded(val edits: List<TextEdit>) : FormatOutcome()
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
            // Normalize CRLF so character offsets match LSP line-based positions.
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
            return 0
        }

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
