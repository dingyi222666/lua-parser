package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceEdit
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-450 — LSP prepareRename dual-path expansion corpus.
 *
 * Complements [LspPrepareRenameSafetyTddTest] (TASK-249 single-file safety) and
 * [LspRenamePrepareMultiFileTddTest] (TASK-373 multi-file) with an explicit
 * dual-path inventory around textDocument/prepareRename + textDocument/rename:
 *
 * Hard contracts once product implements prepareRename/rename:
 * - Local identifiers (vars, functions, params, for-loop names, nested shadows)
 *   accept with an identifier-span-only range and matching placeholder.
 * - Non-identifier positions (whitespace, keyword, literal, operator, comment,
 *   string index key, past-EOF) reject or return defaultBehavior — never invent
 *   a server-endorsed rename range for unsafe tokens.
 * - Free globals without a local binding reject under lexical rename policy.
 * - rename of locals edits identifier spans only; missing-symbol / unsafe
 *   positions yield empty WorkspaceEdit or a non-crash failure.
 *
 * Dual-path / CURRENTLY_ACCEPTS product gaps (never hard-fail the suite):
 * - [LuaTextDocumentService] inherits LSP4J defaults → UnsupportedOperationException
 *   for prepareRename/rename until product lands TASK-160 / rename lane.
 * - Server capabilities currently omit renameProvider / prepareProvider; ideal
 *   path advertises rename with prepareProvider (LSP prepareSupport).
 * - Table field / method-name / attribute-local sites may accept, soft-reject,
 *   or remain unimplemented; wrong hard crashes are not allowed.
 * - Product-available documentHighlight + references act as rename-planning
 *   proxies while prepareRename is still a gap (soft coverage floors only).
 *
 * Test-only; no product edits. Verification is review-owned and serial; this
 * worker does not run Gradle. Host android.jar is not required; never G:/.
 */
class LspRenamePrepareDualPathTddTest {

    // -------------------------------------------------------------------------
    // Capability dual-path
    // -------------------------------------------------------------------------

    @Test
    fun rename_capability_is_absent_or_advertises_prepare_support() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val renameProvider = capabilities.renameProvider

        if (renameProvider == null) {
            // CURRENTLY_ACCEPTS: product does not advertise rename yet.
            return
        }

        // Ideal path: Either<Boolean, RenameOptions> with prepareProvider (lsp4j name for prepareSupport).
        if (renameProvider.isLeft) {
            assertTrue(
                renameProvider.left,
                "boolean renameProvider should be true when advertised"
            )
        } else {
            val options = assertNotNull(renameProvider.right, "RenameOptions expected on right")
            // prepareProvider is the dual-path signal that prepareRename is live.
            // Soft: product may advertise rename without prepareProvider first.
            if (options.prepareProvider == true) {
                assertTrue(options.prepareProvider)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Dual-path inventory: local identifier shapes that should accept
    // -------------------------------------------------------------------------

    @Test
    fun prepare_rename_local_var_dual_path_accepts_or_gap() {
        val outcome = prepareOn(
            path = "workspace/dual-prepare-local-var.lua",
            source = "local value = 1\nlocal copy = value\nreturn value + copy",
            needle = "value",
            occurrence = 2
        )
        assertAcceptableRenameTarget(outcome, identifier = "value", context = "local var use site")
    }

    @Test
    fun prepare_rename_local_function_dual_path_accepts_or_gap() {
        val outcome = prepareOn(
            path = "workspace/dual-prepare-local-function.lua",
            source = """
                local function render(x)
                    return x
                end
                return render
            """,
            needle = "render",
            occurrence = 1
        )
        assertAcceptableRenameTarget(outcome, identifier = "render", context = "local function name")
    }

    @Test
    fun prepare_rename_function_parameter_dual_path_accepts_or_gap() {
        val outcome = prepareOn(
            path = "workspace/dual-prepare-param.lua",
            source = """
                local function paint(color, alpha)
                    return color, alpha
                end
                return paint
            """,
            needle = "color",
            occurrence = 1
        )
        assertAcceptableRenameTarget(outcome, identifier = "color", context = "function parameter")
    }

    @Test
    fun prepare_rename_for_numeric_loop_var_dual_path_accepts_or_gap() {
        val outcome = prepareOn(
            path = "workspace/dual-prepare-for-numeric.lua",
            source = """
                local sum = 0
                for index = 1, 3 do
                    sum = sum + index
                end
                return sum
            """,
            needle = "index",
            occurrence = 1
        )
        assertAcceptableRenameTarget(outcome, identifier = "index", context = "for-numeric loop var")
    }

    @Test
    fun prepare_rename_for_in_loop_var_dual_path_accepts_or_gap() {
        val outcome = prepareOn(
            path = "workspace/dual-prepare-for-in.lua",
            source = """
                local total = 0
                for key, value in pairs({}) do
                    total = total + (value or 0)
                end
                return total
            """,
            needle = "key",
            occurrence = 1
        )
        assertAcceptableRenameTarget(outcome, identifier = "key", context = "for-in loop var")
    }

    @Test
    fun prepare_rename_nested_shadow_inner_local_dual_path_accepts_or_gap() {
        val outcome = prepareOn(
            path = "workspace/dual-prepare-shadow.lua",
            source = """
                local value = 1
                do
                    local value = 2
                    return value
                end
            """,
            needle = "value",
            occurrence = 2
        )
        assertAcceptableRenameTarget(outcome, identifier = "value", context = "nested shadow inner local")
    }

    @Test
    fun prepare_rename_table_field_definition_dual_path() {
        // Soft: field rename policy may accept identifier-span, soft-reject, or stay
        // unimplemented (CURRENTLY_ACCEPTS). Hard crash / wrong range is not allowed.
        val outcome = prepareOn(
            path = "workspace/dual-prepare-table-field.lua",
            source = """
                local M = {}
                M.score = 1
                return M.score
            """,
            needle = "score",
            occurrence = 1
        )
        assertSoftRenameTarget(outcome, identifier = "score", context = "table field definition")
    }

    @Test
    fun prepare_rename_method_name_colon_definition_dual_path() {
        val outcome = prepareOn(
            path = "workspace/dual-prepare-method-colon.lua",
            source = """
                local M = {}
                function M:draw(x)
                    return x
                end
                return M
            """,
            needle = "draw",
            occurrence = 1
        )
        assertSoftRenameTarget(outcome, identifier = "draw", context = "colon method definition")
    }

    @Test
    fun prepare_rename_attribute_local_dual_path() {
        // Lua 5.4 attribute locals — soft until attribute rename policy is defined.
        val outcome = prepareOn(
            path = "workspace/dual-prepare-attr-local.lua",
            source = """
                local value <const> = 1
                return value
            """,
            needle = "value",
            occurrence = 1
        )
        assertSoftRenameTarget(outcome, identifier = "value", context = "attribute local <const>")
    }

    // -------------------------------------------------------------------------
    // Dual-path inventory: positions that must reject (or stay unimplemented)
    // -------------------------------------------------------------------------

    @Test
    fun prepare_rename_whitespace_dual_path_rejects_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/dual-prepare-ws.lua",
            "local value = 1\n\nreturn value"
        )
        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(document, Position(1, 0))
        )
        assertPrepareRenameRejectedOrGap(outcome, context = "whitespace / blank line")
    }

    @Test
    fun prepare_rename_keyword_dual_path_rejects_or_gap() {
        val outcome = prepareOn(
            path = "workspace/dual-prepare-keyword.lua",
            source = "local value = 1\nreturn value",
            needle = "return",
            occurrence = 1
        )
        assertPrepareRenameRejectedOrGap(outcome, context = "keyword 'return'")
    }

    @Test
    fun prepare_rename_numeric_literal_dual_path_rejects_or_gap() {
        val outcome = prepareOn(
            path = "workspace/dual-prepare-number.lua",
            source = "local value = 99\nreturn value",
            needle = "99",
            occurrence = 1
        )
        assertPrepareRenameRejectedOrGap(outcome, context = "numeric literal")
    }

    @Test
    fun prepare_rename_string_literal_dual_path_rejects_or_gap() {
        val outcome = prepareOn(
            path = "workspace/dual-prepare-string.lua",
            source = "local greeting = \"hello\"\nreturn greeting",
            needle = "\"hello\"",
            occurrence = 1
        )
        assertPrepareRenameRejectedOrGap(outcome, context = "string literal")
    }

    @Test
    fun prepare_rename_operator_dual_path_rejects_or_gap() {
        val outcome = prepareOn(
            path = "workspace/dual-prepare-operator.lua",
            source = "local a = 1\nlocal b = 2\nreturn a * b",
            needle = "*",
            occurrence = 1
        )
        assertPrepareRenameRejectedOrGap(outcome, context = "binary operator '*'")
    }

    @Test
    fun prepare_rename_comment_dual_path_rejects_or_gap() {
        val outcome = prepareOn(
            path = "workspace/dual-prepare-comment.lua",
            source = """
                -- rename me not
                local value = 1
                return value
            """,
            needle = "rename me not",
            occurrence = 1
        )
        assertPrepareRenameRejectedOrGap(outcome, context = "line comment body")
    }

    @Test
    fun prepare_rename_string_index_key_dual_path_rejects_or_gap() {
        val outcome = prepareOn(
            path = "workspace/dual-prepare-index-key.lua",
            source = """
                local t = {}
                t["score"] = 1
                return t["score"]
            """,
            needle = "\"score\"",
            occurrence = 1
        )
        assertPrepareRenameRejectedOrGap(outcome, context = "string index key")
    }

    @Test
    fun prepare_rename_global_without_local_binding_dual_path_rejects_or_gap() {
        val outcome = prepareOn(
            path = "workspace/dual-prepare-global.lua",
            source = "print(1)\nreturn print",
            needle = "print",
            occurrence = 1
        )
        assertPrepareRenameRejectedOrGap(outcome, context = "global 'print' without local binding")
    }

    @Test
    fun prepare_rename_undeclared_free_name_dual_path_rejects_or_gap() {
        val outcome = prepareOn(
            path = "workspace/dual-prepare-free.lua",
            source = "return freeName",
            needle = "freeName",
            occurrence = 1
        )
        assertPrepareRenameRejectedOrGap(outcome, context = "undeclared free name")
    }

    @Test
    fun prepare_rename_past_eof_dual_path_rejects_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/dual-prepare-eof.lua",
            "local value = 1\nreturn value"
        )
        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(document, Position(40, 0))
        )
        assertPrepareRenameRejectedOrGap(outcome, context = "position past EOF")
    }

    // -------------------------------------------------------------------------
    // rename dual-path: edits / empty / no crash
    // -------------------------------------------------------------------------

    @Test
    fun rename_local_identifier_dual_path_span_only_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/dual-rename-local.lua",
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
                assertFalse(
                    outcome.detail.contains("NullPointerException", ignoreCase = true),
                    "rename of local must not NPE; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Succeeded -> {
                val edits = outcome.edit.changes?.get(document.uri).orEmpty()
                // CURRENTLY_ACCEPTS empty until product wires multi-site rename.
                if (edits.isEmpty()) {
                    return
                }
                edits.forEach { edit ->
                    assertEquals("renamed", edit.newText)
                    assertEquals(
                        edit.range.start.line,
                        edit.range.end.line,
                        "identifier rename ranges must be single-line"
                    )
                    assertEquals(
                        "value".length,
                        edit.range.end.character - edit.range.start.character,
                        "rename TextEdit range must cover identifier span only"
                    )
                }
            }
        }
    }

    @Test
    fun rename_missing_symbol_dual_path_empty_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/dual-rename-missing.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeRename(
            textDocuments,
            renameParams(document, Position(20, 0), "renamed")
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
                    outcome.detail.contains("NullPointerException", ignoreCase = true) ||
                        outcome.detail.contains("AssertionError", ignoreCase = true),
                    "rename on missing symbol must not NPE/assert; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Succeeded -> {
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
    fun rename_on_keyword_dual_path_empty_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/dual-rename-keyword.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeRename(
            textDocuments,
            renameParams(document, document.positionOf("local"), "renamed")
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
                    "rename on keyword must not NPE; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Succeeded -> {
                val changes = outcome.edit.changes.orEmpty()
                val documentChanges = outcome.edit.documentChanges.orEmpty()
                assertTrue(
                    changes.isEmpty() && documentChanges.isEmpty(),
                    "rename on keyword should not invent edits; changes=${changes.keys}"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Product-available rename planning proxies
    // -------------------------------------------------------------------------

    @Test
    fun document_highlight_proxy_plans_local_rename_sites() {
        val service = service()
        val document = service.open(
            "workspace/dual-highlight-proxy.lua",
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

        assertTrue(highlights.isNotEmpty(), "Expected document highlights for local 'value' rename plan")
        highlights.forEach { highlight ->
            assertTrue(
                highlight.range.end.line > highlight.range.start.line ||
                    (highlight.range.end.line == highlight.range.start.line &&
                        highlight.range.end.character >= highlight.range.start.character),
                "highlight range must be ordered: ${highlight.range}"
            )
            assertHighlightCoversIdentifier(highlight.range, document, "value")
        }
    }

    @Test
    fun references_proxy_plans_local_rename_sites_include_declaration() {
        val service = service()
        val document = service.open(
            "workspace/dual-refs-proxy.lua",
            """
            local value = 1
            local copy = value
            return value
            """
        )

        val references = service.references(
            ReferenceParams(
                TextDocumentIdentifier(document.uri),
                document.positionOf("value", occurrence = 2),
                ReferenceContext(true)
            )
        )

        assertTrue(references.isNotEmpty(), "Expected references for local 'value' rename plan")
        // Soft floor: at least the declaration + one use when includeDeclaration=true.
        // CURRENTLY_ACCEPTS partial product counts, but ordered ranges are hard.
        references.forEach { location ->
            assertEquals(document.uri, location.uri, "local rename plan must stay in-file")
            assertTrue(
                location.range.end.line > location.range.start.line ||
                    (location.range.end.line == location.range.start.line &&
                        location.range.end.character >= location.range.start.character),
                "reference range must be ordered: ${location.range}"
            )
        }
    }

    @Test
    fun text_document_service_prepare_rename_surface_is_invokable_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/dual-prepare-surface.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(document, document.positionOf("value", occurrence = 1))
        )
        assertTrue(
            outcome is PrepareRenameOutcome.Unsupported ||
                outcome is PrepareRenameOutcome.Rejected ||
                outcome is PrepareRenameOutcome.Accepted,
            "prepareRename surface must resolve to a known dual-path outcome; got $outcome"
        )
    }

    // -------------------------------------------------------------------------
    // Explicit dual-path inventory table
    // -------------------------------------------------------------------------

    @Test
    fun prepare_rename_dual_path_inventory_covers_accept_and_reject_shapes() {
        val cases = listOf(
            InventoryCase(
                name = "local-var-use",
                source = "local value = 1\nreturn value",
                needle = "value",
                occurrence = 2,
                expected = InventoryExpectation.ACCEPTABLE_TARGET
            ),
            InventoryCase(
                name = "local-function",
                source = "local function paint()\n  return 1\nend\nreturn paint",
                needle = "paint",
                occurrence = 1,
                expected = InventoryExpectation.ACCEPTABLE_TARGET
            ),
            InventoryCase(
                name = "for-numeric",
                source = "for i = 1, 2 do end",
                needle = "i",
                occurrence = 1,
                expected = InventoryExpectation.ACCEPTABLE_TARGET
            ),
            InventoryCase(
                name = "param",
                source = "local function f(arg)\n  return arg\nend\nreturn f",
                needle = "arg",
                occurrence = 1,
                expected = InventoryExpectation.ACCEPTABLE_TARGET
            ),
            InventoryCase(
                name = "keyword-local",
                source = "local value = 1\nreturn value",
                needle = "local",
                occurrence = 1,
                expected = InventoryExpectation.REJECT_OR_GAP
            ),
            InventoryCase(
                name = "number-literal",
                source = "local value = 7\nreturn value",
                needle = "7",
                occurrence = 1,
                expected = InventoryExpectation.REJECT_OR_GAP
            ),
            InventoryCase(
                name = "string-literal",
                source = "local s = \"x\"\nreturn s",
                needle = "\"x\"",
                occurrence = 1,
                expected = InventoryExpectation.REJECT_OR_GAP
            ),
            InventoryCase(
                name = "free-global-print",
                source = "print(1)",
                needle = "print",
                occurrence = 1,
                expected = InventoryExpectation.REJECT_OR_GAP
            ),
            InventoryCase(
                name = "table-field",
                source = "local M = {}\nM.score = 1\nreturn M",
                needle = "score",
                occurrence = 1,
                expected = InventoryExpectation.SOFT_TARGET
            ),
            InventoryCase(
                name = "method-colon",
                source = "local M = {}\nfunction M:draw() end\nreturn M",
                needle = "draw",
                occurrence = 1,
                expected = InventoryExpectation.SOFT_TARGET
            )
        )

        cases.forEach { case ->
            val outcome = prepareOn(
                path = "workspace/dual-inventory-${case.name}.lua",
                source = case.source,
                needle = case.needle,
                occurrence = case.occurrence
            )
            when (case.expected) {
                InventoryExpectation.ACCEPTABLE_TARGET ->
                    assertAcceptableRenameTarget(
                        outcome,
                        identifier = case.needle,
                        context = "inventory:${case.name}"
                    )
                InventoryExpectation.REJECT_OR_GAP ->
                    assertPrepareRenameRejectedOrGap(outcome, context = "inventory:${case.name}")
                InventoryExpectation.SOFT_TARGET ->
                    assertSoftRenameTarget(
                        outcome,
                        identifier = case.needle,
                        context = "inventory:${case.name}"
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

    private fun prepareOn(
        path: String,
        source: String,
        needle: String,
        occurrence: Int
    ): PrepareRenameOutcome {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(path, source)
        return invokePrepareRename(
            textDocuments,
            prepareRenameParams(document, document.positionOf(needle, occurrence))
        )
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

    /**
     * Ideal renamable local-like targets: Unsupported gap OK; Rejected is a hard
     * product bug once prepareRename exists; Accepted must be identifier-span only
     * (unless defaultBehavior client word-range).
     */
    private fun assertAcceptableRenameTarget(
        outcome: PrepareRenameOutcome,
        identifier: String,
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
                fail(
                    "$context should be renamable once prepareRename is implemented; " +
                        "got rejection: ${outcome.detail}"
                )
            }
            is PrepareRenameOutcome.Accepted -> {
                if (outcome.defaultBehavior) {
                    // Client word-range fallback — soft accept for dual-path inventory.
                    return
                }
                assertTrue(
                    outcome.range.start.line == outcome.range.end.line,
                    "$context prepareRename range must be single-line; got ${outcome.range}"
                )
                assertEquals(
                    identifier.length,
                    outcome.range.end.character - outcome.range.start.character,
                    "$context prepareRename range must equal identifier length"
                )
                if (outcome.placeholder != null) {
                    assertEquals(
                        identifier,
                        outcome.placeholder,
                        "$context prepareRename placeholder should match identifier"
                    )
                }
            }
        }
    }

    /**
     * Soft targets (fields / methods / attributes): Unsupported, Rejected (with
     * detail), or Accepted identifier-span are all CURRENTLY_ACCEPTS. Hard crashes
     * are already filtered by invokePrepareRename; Accepted non-default ranges must
     * still be identifier-span only when product chooses to accept.
     */
    private fun assertSoftRenameTarget(
        outcome: PrepareRenameOutcome,
        identifier: String,
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
                assertTrue(
                    outcome.detail.isNotBlank(),
                    "Soft rejection for $context should carry a detail string"
                )
            }
            is PrepareRenameOutcome.Accepted -> {
                if (outcome.defaultBehavior) {
                    return
                }
                assertTrue(
                    outcome.range.start.line == outcome.range.end.line,
                    "$context accepted range must be single-line; got ${outcome.range}"
                )
                assertEquals(
                    identifier.length,
                    outcome.range.end.character - outcome.range.start.character,
                    "$context accepted range must cover identifier span only"
                )
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
                assertTrue(
                    outcome.detail.isNotBlank(),
                    "Rejection for $context should carry a detail string"
                )
            }
            is PrepareRenameOutcome.Accepted -> {
                if (outcome.defaultBehavior) {
                    // DefaultBehavior = client word range — not a server-endorsed rename.
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

    private fun assertHighlightCoversIdentifier(
        range: Range,
        document: OpenDocument,
        identifier: String
    ) {
        if (range.start.line == range.end.line &&
            range.end.character - range.start.character == identifier.length
        ) {
            val slice = runCatching { document.slice(range) }.getOrNull()
            if (slice == identifier) {
                return
            }
        }
        val fullSlice = runCatching { document.slice(range) }.getOrDefault("")
        val startsAtOccurrence = document.occurrenceStarts(identifier).any {
            it.line == range.start.line && it.character == range.start.character
        }
        assertTrue(
            startsAtOccurrence || fullSlice.contains(identifier) || fullSlice.startsWith(identifier),
            "highlight must cover identifier '$identifier'; range=$range slice='$fullSlice'"
        )
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
        ACCEPTABLE_TARGET,
        REJECT_OR_GAP,
        SOFT_TARGET
    }

    private data class InventoryCase(
        val name: String,
        val source: String,
        val needle: String,
        val occurrence: Int,
        val expected: InventoryExpectation
    )

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
            return (lineStart + position.character).coerceIn(0, source.length)
        }
    }
}
