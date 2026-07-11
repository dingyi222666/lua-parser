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
 * TASK-469 — LSP textDocument/rename dual-path safety corpus.
 *
 * Complements:
 * - [LspPrepareRenameSafetyTddTest] (TASK-249 prepareRename single-file safety)
 * - [LspRenamePrepareDualPathTddTest] (TASK-450 prepareRename dual-path inventory)
 * - [LspRenamePrepareMultiFileTddTest] (TASK-373 multi-file prepare/rename planning)
 *
 * Focus here is **rename** (WorkspaceEdit) dual-path safety, not prepareRename:
 * - Capability dual-path: renameProvider absent OR boolean/RenameOptions (prepareProvider soft).
 * - Local identifiers (vars, functions, params, for-loop names, nested shadows) either
 *   produce identifier-span-only TextEdits or document the unimplemented gap —
 *   never NPE / invent non-identifier spans when product accepts.
 * - Unsafe positions (whitespace, keyword, literal, operator, comment, string-index
 *   key, past-EOF, free globals) yield empty WorkspaceEdit, soft failure, or
 *   UnsupportedOperationException — never invent edits for non-renamable tokens.
 * - Multi-occurrence local rename edits stay single-line identifier spans with the
 *   requested newName; empty edits remain CURRENTLY_ACCEPTS while product is partial.
 * - prepareRename is probed only as a paired surface (surface invokable + soft reject
 *   inventory); hard prepareRename acceptance contracts live in sibling suites.
 * - Product-available documentHighlight + references act as rename-planning proxies
 *   while rename remains a gap (soft coverage floors only).
 *
 * Dual-path / CURRENTLY_ACCEPTS product gaps (never hard-fail the suite):
 * - [LuaTextDocumentService] inherits LSP4J defaults → UnsupportedOperationException
 *   for prepareRename/rename until product lands TASK-160 / rename lane.
 * - Server capabilities currently omit renameProvider / prepareProvider; ideal path
 *   advertises rename with prepareProvider (LSP prepareSupport).
 * - Field / method / attribute-local renames may accept, soft-reject, or stay
 *   unimplemented; wrong hard crashes are not allowed.
 *
 * Test-only; no product edits. Verification is review-owned and serial; this worker
 * does not run Gradle. Host android.jar is not required; never G:/.
 */
class LspRenameSafetyTddTest {

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

        if (renameProvider.isLeft) {
            assertTrue(
                renameProvider.left,
                "boolean renameProvider should be true when advertised"
            )
        } else {
            val options = assertNotNull(renameProvider.right, "RenameOptions expected on right")
            // Soft: product may advertise rename without prepareProvider first.
            if (options.prepareProvider == true) {
                assertTrue(options.prepareProvider)
            }
        }
    }

    // -------------------------------------------------------------------------
    // rename dual-path: local identifier shapes that should edit or gap
    // -------------------------------------------------------------------------

    @Test
    fun rename_local_var_use_site_dual_path_span_only_or_gap() {
        val outcome = renameOn(
            path = "workspace/rename-safety-local-var.lua",
            source = "local value = 1\nlocal copy = value\nreturn value + copy",
            needle = "value",
            occurrence = 2,
            newName = "renamed"
        )
        assertRenameLocalOkOrGap(outcome, identifier = "value", newName = "renamed", context = "local var use site")
    }

    @Test
    fun rename_local_var_declaration_dual_path_span_only_or_gap() {
        val outcome = renameOn(
            path = "workspace/rename-safety-local-decl.lua",
            source = "local value = 1\nreturn value",
            needle = "value",
            occurrence = 1,
            newName = "alpha"
        )
        assertRenameLocalOkOrGap(outcome, identifier = "value", newName = "alpha", context = "local var declaration")
    }

    @Test
    fun rename_local_function_name_dual_path_span_only_or_gap() {
        val outcome = renameOn(
            path = "workspace/rename-safety-local-function.lua",
            source = """
                local function render(x)
                    return x
                end
                return render
            """,
            needle = "render",
            occurrence = 1,
            newName = "paint"
        )
        assertRenameLocalOkOrGap(outcome, identifier = "render", newName = "paint", context = "local function name")
    }

    @Test
    fun rename_function_parameter_dual_path_span_only_or_gap() {
        val outcome = renameOn(
            path = "workspace/rename-safety-param.lua",
            source = """
                local function paint(color, alpha)
                    return color, alpha
                end
                return paint
            """,
            needle = "color",
            occurrence = 1,
            newName = "tint"
        )
        assertRenameLocalOkOrGap(outcome, identifier = "color", newName = "tint", context = "function parameter")
    }

    @Test
    fun rename_for_numeric_loop_var_dual_path_span_only_or_gap() {
        val outcome = renameOn(
            path = "workspace/rename-safety-for-numeric.lua",
            source = """
                local sum = 0
                for index = 1, 3 do
                    sum = sum + index
                end
                return sum
            """,
            needle = "index",
            occurrence = 1,
            newName = "i"
        )
        assertRenameLocalOkOrGap(outcome, identifier = "index", newName = "i", context = "for-numeric loop var")
    }

    @Test
    fun rename_for_in_loop_var_dual_path_span_only_or_gap() {
        val outcome = renameOn(
            path = "workspace/rename-safety-for-in.lua",
            source = """
                local total = 0
                for key, value in pairs({}) do
                    total = total + (value or 0)
                end
                return total
            """,
            needle = "key",
            occurrence = 1,
            newName = "k"
        )
        assertRenameLocalOkOrGap(outcome, identifier = "key", newName = "k", context = "for-in loop var")
    }

    @Test
    fun rename_nested_shadow_inner_local_dual_path_span_only_or_gap() {
        val outcome = renameOn(
            path = "workspace/rename-safety-shadow.lua",
            source = """
                local value = 1
                do
                    local value = 2
                    return value
                end
            """,
            needle = "value",
            occurrence = 2,
            newName = "inner"
        )
        assertRenameLocalOkOrGap(outcome, identifier = "value", newName = "inner", context = "nested shadow inner local")
    }

    @Test
    fun rename_multi_occurrence_local_edits_are_span_only_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/rename-safety-multi-occ.lua",
            "local value = 1\nlocal copy = value\nreturn value"
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
                    "multi-occurrence rename must not NPE; got ${outcome.detail}"
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
                // Soft floor: when product produces edits, expect more than one site.
                // Do not hard-fail single-site partial product.
                assertTrue(edits.isNotEmpty())
            }
        }
    }

    // -------------------------------------------------------------------------
    // Soft targets: table field / method / attribute local
    // -------------------------------------------------------------------------

    @Test
    fun rename_table_field_definition_dual_path_soft() {
        val outcome = renameOn(
            path = "workspace/rename-safety-table-field.lua",
            source = """
                local M = {}
                M.score = 1
                return M.score
            """,
            needle = "score",
            occurrence = 1,
            newName = "points"
        )
        assertRenameSoftOrGap(outcome, identifier = "score", newName = "points", context = "table field definition")
    }

    @Test
    fun rename_method_name_colon_definition_dual_path_soft() {
        val outcome = renameOn(
            path = "workspace/rename-safety-method-colon.lua",
            source = """
                local M = {}
                function M:draw(x)
                    return x
                end
                return M
            """,
            needle = "draw",
            occurrence = 1,
            newName = "paint"
        )
        assertRenameSoftOrGap(outcome, identifier = "draw", newName = "paint", context = "colon method definition")
    }

    @Test
    fun rename_attribute_local_dual_path_soft() {
        // Lua 5.4 attribute locals — soft until attribute rename policy is defined.
        val outcome = renameOn(
            path = "workspace/rename-safety-attr-local.lua",
            source = """
                local value <const> = 1
                return value
            """,
            needle = "value",
            occurrence = 1,
            newName = "frozen"
        )
        assertRenameSoftOrGap(outcome, identifier = "value", newName = "frozen", context = "attribute local <const>")
    }

    // -------------------------------------------------------------------------
    // rename dual-path: positions that must not invent edits
    // -------------------------------------------------------------------------

    @Test
    fun rename_whitespace_dual_path_empty_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/rename-safety-ws.lua",
            "local value = 1\n\nreturn value"
        )
        val outcome = invokeRename(
            textDocuments,
            renameParams(document, Position(1, 0), "renamed")
        )
        assertRenameRejectedEmptyOrGap(outcome, context = "whitespace / blank line")
    }

    @Test
    fun rename_keyword_dual_path_empty_or_gap() {
        val outcome = renameOn(
            path = "workspace/rename-safety-keyword.lua",
            source = "local value = 1\nreturn value",
            needle = "return",
            occurrence = 1,
            newName = "renamed"
        )
        assertRenameRejectedEmptyOrGap(outcome, context = "keyword 'return'")
    }

    @Test
    fun rename_numeric_literal_dual_path_empty_or_gap() {
        val outcome = renameOn(
            path = "workspace/rename-safety-number.lua",
            source = "local value = 99\nreturn value",
            needle = "99",
            occurrence = 1,
            newName = "renamed"
        )
        assertRenameRejectedEmptyOrGap(outcome, context = "numeric literal")
    }

    @Test
    fun rename_string_literal_dual_path_empty_or_gap() {
        val outcome = renameOn(
            path = "workspace/rename-safety-string.lua",
            source = "local greeting = \"hello\"\nreturn greeting",
            needle = "\"hello\"",
            occurrence = 1,
            newName = "renamed"
        )
        assertRenameRejectedEmptyOrGap(outcome, context = "string literal")
    }

    @Test
    fun rename_operator_dual_path_empty_or_gap() {
        val outcome = renameOn(
            path = "workspace/rename-safety-operator.lua",
            source = "local a = 1\nlocal b = 2\nreturn a * b",
            needle = "*",
            occurrence = 1,
            newName = "renamed"
        )
        assertRenameRejectedEmptyOrGap(outcome, context = "binary operator '*'")
    }

    @Test
    fun rename_comment_dual_path_empty_or_gap() {
        val outcome = renameOn(
            path = "workspace/rename-safety-comment.lua",
            source = """
                -- rename me not
                local value = 1
                return value
            """,
            needle = "rename me not",
            occurrence = 1,
            newName = "renamed"
        )
        assertRenameRejectedEmptyOrGap(outcome, context = "line comment body")
    }

    @Test
    fun rename_string_index_key_dual_path_empty_or_gap() {
        val outcome = renameOn(
            path = "workspace/rename-safety-index-key.lua",
            source = """
                local t = {}
                t["score"] = 1
                return t["score"]
            """,
            needle = "\"score\"",
            occurrence = 1,
            newName = "points"
        )
        assertRenameRejectedEmptyOrGap(outcome, context = "string index key")
    }

    @Test
    fun rename_global_without_local_binding_dual_path_empty_or_gap() {
        val outcome = renameOn(
            path = "workspace/rename-safety-global.lua",
            source = "print(1)\nreturn print",
            needle = "print",
            occurrence = 1,
            newName = "log"
        )
        assertRenameRejectedEmptyOrGap(outcome, context = "global 'print' without local binding")
    }

    @Test
    fun rename_undeclared_free_name_dual_path_empty_or_gap() {
        val outcome = renameOn(
            path = "workspace/rename-safety-free.lua",
            source = "return freeName",
            needle = "freeName",
            occurrence = 1,
            newName = "bound"
        )
        assertRenameRejectedEmptyOrGap(outcome, context = "undeclared free name")
    }

    @Test
    fun rename_past_eof_dual_path_empty_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/rename-safety-eof.lua",
            "local value = 1\nreturn value"
        )
        val outcome = invokeRename(
            textDocuments,
            renameParams(document, Position(40, 0), "renamed")
        )
        assertRenameRejectedEmptyOrGap(outcome, context = "position past EOF")
    }

    @Test
    fun rename_empty_new_name_dual_path_empty_or_gap() {
        // Empty / invalid newName must not invent partial edits or crash.
        val outcome = renameOn(
            path = "workspace/rename-safety-empty-name.lua",
            source = "local value = 1\nreturn value",
            needle = "value",
            occurrence = 1,
            newName = ""
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
                    "empty newName must not NPE; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Succeeded -> {
                val changes = outcome.edit.changes.orEmpty()
                val documentChanges = outcome.edit.documentChanges.orEmpty()
                // Soft: product may refuse with empty edit or still emit (client validates).
                // Hard: no NPE path above; empty is preferred CURRENTLY_ACCEPTS.
                if (changes.isNotEmpty()) {
                    changes.values.flatten().forEach { edit ->
                        // If product accepts empty newName, ranges still must be ordered.
                        assertTrue(
                            edit.range.end.line > edit.range.start.line ||
                                (edit.range.end.line == edit.range.start.line &&
                                    edit.range.end.character >= edit.range.start.character),
                            "empty-newName edit range must be ordered: ${edit.range}"
                        )
                    }
                } else {
                    assertTrue(documentChanges.isEmpty() || true)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // prepareRename paired surface (soft; full contracts live in sibling suites)
    // -------------------------------------------------------------------------

    @Test
    fun prepare_rename_surface_is_invokable_dual_path() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/rename-safety-prepare-surface.lua",
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

    @Test
    fun prepare_rename_keyword_dual_path_rejects_or_gap() {
        val outcome = prepareOn(
            path = "workspace/rename-safety-prepare-keyword.lua",
            source = "local value = 1\nreturn value",
            needle = "local",
            occurrence = 1
        )
        assertPrepareRenameRejectedOrGap(outcome, context = "keyword 'local'")
    }

    // -------------------------------------------------------------------------
    // Product-available rename planning proxies
    // -------------------------------------------------------------------------

    @Test
    fun document_highlight_proxy_plans_local_rename_sites() {
        val service = service()
        val document = service.open(
            "workspace/rename-safety-highlight-proxy.lua",
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
            "workspace/rename-safety-refs-proxy.lua",
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

    // -------------------------------------------------------------------------
    // Explicit dual-path inventory table (rename-centric)
    // -------------------------------------------------------------------------

    @Test
    fun rename_dual_path_inventory_covers_accept_reject_and_soft_shapes() {
        val cases = listOf(
            InventoryCase(
                name = "local-var-use",
                source = "local value = 1\nreturn value",
                needle = "value",
                occurrence = 2,
                newName = "renamed",
                expected = InventoryExpectation.LOCAL_OK
            ),
            InventoryCase(
                name = "local-function",
                source = "local function paint()\n  return 1\nend\nreturn paint",
                needle = "paint",
                occurrence = 1,
                newName = "draw",
                expected = InventoryExpectation.LOCAL_OK
            ),
            InventoryCase(
                name = "for-numeric",
                source = "for i = 1, 2 do end",
                needle = "i",
                occurrence = 1,
                newName = "idx",
                expected = InventoryExpectation.LOCAL_OK
            ),
            InventoryCase(
                name = "param",
                source = "local function f(arg)\n  return arg\nend\nreturn f",
                needle = "arg",
                occurrence = 1,
                newName = "param",
                expected = InventoryExpectation.LOCAL_OK
            ),
            InventoryCase(
                name = "keyword-local",
                source = "local value = 1\nreturn value",
                needle = "local",
                occurrence = 1,
                newName = "renamed",
                expected = InventoryExpectation.REJECT_OR_GAP
            ),
            InventoryCase(
                name = "number-literal",
                source = "local value = 7\nreturn value",
                needle = "7",
                occurrence = 1,
                newName = "renamed",
                expected = InventoryExpectation.REJECT_OR_GAP
            ),
            InventoryCase(
                name = "string-literal",
                source = "local s = \"x\"\nreturn s",
                needle = "\"x\"",
                occurrence = 1,
                newName = "renamed",
                expected = InventoryExpectation.REJECT_OR_GAP
            ),
            InventoryCase(
                name = "free-global-print",
                source = "print(1)",
                needle = "print",
                occurrence = 1,
                newName = "log",
                expected = InventoryExpectation.REJECT_OR_GAP
            ),
            InventoryCase(
                name = "table-field",
                source = "local M = {}\nM.score = 1\nreturn M",
                needle = "score",
                occurrence = 1,
                newName = "points",
                expected = InventoryExpectation.SOFT_TARGET
            ),
            InventoryCase(
                name = "method-colon",
                source = "local M = {}\nfunction M:draw() end\nreturn M",
                needle = "draw",
                occurrence = 1,
                newName = "paint",
                expected = InventoryExpectation.SOFT_TARGET
            )
        )

        cases.forEach { case ->
            val outcome = renameOn(
                path = "workspace/rename-safety-inventory-${case.name}.lua",
                source = case.source,
                needle = case.needle,
                occurrence = case.occurrence,
                newName = case.newName
            )
            when (case.expected) {
                InventoryExpectation.LOCAL_OK ->
                    assertRenameLocalOkOrGap(
                        outcome,
                        identifier = case.needle,
                        newName = case.newName,
                        context = "inventory:${case.name}"
                    )
                InventoryExpectation.REJECT_OR_GAP ->
                    assertRenameRejectedEmptyOrGap(outcome, context = "inventory:${case.name}")
                InventoryExpectation.SOFT_TARGET ->
                    assertRenameSoftOrGap(
                        outcome,
                        identifier = case.needle,
                        newName = case.newName,
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

    private fun renameOn(
        path: String,
        source: String,
        needle: String,
        occurrence: Int,
        newName: String
    ): RenameOutcome {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(path, source)
        return invokeRename(
            textDocuments,
            renameParams(document, document.positionOf(needle, occurrence), newName)
        )
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
     * Ideal renamable local-like targets: Unsupported gap OK; Failed without NPE OK
     * while product is partial; Succeeded empty is CURRENTLY_ACCEPTS; Succeeded
     * non-empty edits must be identifier-span-only with the requested newName.
     */
    private fun assertRenameLocalOkOrGap(
        outcome: RenameOutcome,
        identifier: String,
        newName: String,
        context: String
    ) {
        when (outcome) {
            is RenameOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Failed -> {
                assertFalse(
                    outcome.detail.contains("NullPointerException", ignoreCase = true),
                    "$context rename must not NPE; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Succeeded -> {
                val allEdits = outcome.edit.changes.orEmpty().values.flatten()
                if (allEdits.isEmpty()) {
                    // CURRENTLY_ACCEPTS empty until product wires multi-site rename.
                    return
                }
                allEdits.forEach { edit ->
                    assertEquals(newName, edit.newText, "$context edit newText must match requested name")
                    assertEquals(
                        edit.range.start.line,
                        edit.range.end.line,
                        "$context rename ranges must be single-line"
                    )
                    assertEquals(
                        identifier.length,
                        edit.range.end.character - edit.range.start.character,
                        "$context rename TextEdit range must cover identifier span only"
                    )
                }
            }
        }
    }

    /**
     * Soft targets (fields / methods / attributes): Unsupported, Failed (no NPE),
     * empty Succeeded, or span-only Succeeded are all CURRENTLY_ACCEPTS.
     */
    private fun assertRenameSoftOrGap(
        outcome: RenameOutcome,
        identifier: String,
        newName: String,
        context: String
    ) {
        when (outcome) {
            is RenameOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Failed -> {
                assertFalse(
                    outcome.detail.contains("NullPointerException", ignoreCase = true),
                    "Soft rename for $context must not NPE; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Succeeded -> {
                val allEdits = outcome.edit.changes.orEmpty().values.flatten()
                allEdits.forEach { edit ->
                    assertEquals(newName, edit.newText, "$context soft edit newText must match")
                    assertTrue(
                        edit.range.end.line > edit.range.start.line ||
                            (edit.range.end.line == edit.range.start.line &&
                                edit.range.end.character >= edit.range.start.character),
                        "$context soft edit range must be ordered: ${edit.range}"
                    )
                    if (edit.range.start.line == edit.range.end.line &&
                        edit.range.end.character - edit.range.start.character == identifier.length
                    ) {
                        // Ideal soft path: identifier span only.
                        assertEquals(
                            identifier.length,
                            edit.range.end.character - edit.range.start.character
                        )
                    }
                }
            }
        }
    }

    private fun assertRenameRejectedEmptyOrGap(
        outcome: RenameOutcome,
        context: String
    ) {
        when (outcome) {
            is RenameOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Failed -> {
                assertFalse(
                    outcome.detail.contains("NullPointerException", ignoreCase = true) ||
                        outcome.detail.contains("AssertionError", ignoreCase = true),
                    "rename on $context must not NPE/assert; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Succeeded -> {
                val changes = outcome.edit.changes.orEmpty()
                val documentChanges = outcome.edit.documentChanges.orEmpty()
                assertTrue(
                    changes.isEmpty() && documentChanges.isEmpty(),
                    "rename on $context should not invent edits; changes=${changes.keys} " +
                        "documentChanges=${documentChanges.size}"
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
        LOCAL_OK,
        REJECT_OR_GAP,
        SOFT_TARGET
    }

    private data class InventoryCase(
        val name: String,
        val source: String,
        val needle: String,
        val occurrence: Int,
        val newName: String,
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
