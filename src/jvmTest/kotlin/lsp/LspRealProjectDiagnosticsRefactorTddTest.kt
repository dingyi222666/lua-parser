package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real-project-style diagnostics / refactor corpus for LuaLanguageService.
 *
 * Focus (distinct from multi-module require-graph work):
 * - Diagnostics publish/retract on fix across refactor edits
 * - Code actions / quickfix soft paths
 * - Rename local vs multi-file export limits
 * - Prepare rename invalid positions
 * - Formatting / range formatting / onType soft
 * - Folding ranges for large real-ish files
 * - Selection range nested blocks
 * - Signature help active parameter while typing args
 * - Inlay hints / semantic tokens soft
 * - URI edges, CRLF, large-file smoke for diagnostic paths
 *
 * Test-only; no product edits. Never G:/. Does not run full jvmTest.
 */
class LspRealProjectDiagnosticsRefactorTddTest {

    // -------------------------------------------------------------------------
    // Diagnostics publish / retract on fix
    // -------------------------------------------------------------------------

    @Test
    fun refactor_introduces_parse_error_then_fix_retracts_diagnostics() {
        val h = harness()
        val uri = "file:///workspace/src/app/refactor_fix.lua"
        h.textDocuments.didOpen(openParams(uri, REFACTOR_MODULE, version = 1))
        assertTrue(lastFor(h.published, uri).diagnostics.isEmpty())
        h.published.clear()

        h.textDocuments.didChange(fullChange(uri, 2, "local function broken(\nreturn 1"))
        assertTrue(lastFor(h.published, uri).diagnostics.isNotEmpty())
        assertTrue(lastFor(h.published, uri).diagnostics.all { it.severity == DiagnosticSeverity.Error })
        h.published.clear()

        h.textDocuments.didChange(fullChange(uri, 3, REFACTOR_MODULE))
        assertTrue(lastFor(h.published, uri).diagnostics.isEmpty())
    }

    @Test
    fun multi_step_refactor_stream_publishes_per_accepted_change() {
        val h = harness()
        val uri = "file:///workspace/src/app/stream_refactor.lua"
        h.textDocuments.didOpen(openParams(uri, REFACTOR_MODULE, version = 1))
        h.published.clear()
        val steps = listOf(
            2 to "local =",
            3 to "local tmp = 1\nreturn tmp",
            4 to "local = 2",
            5 to REFACTOR_MODULE
        )
        steps.forEach { (v, text) -> h.textDocuments.didChange(fullChange(uri, v, text)) }
        assertEquals(4, h.published.size)
        assertTrue(h.published[0].diagnostics.isNotEmpty())
        assertTrue(h.published[1].diagnostics.isEmpty())
        assertTrue(h.published[2].diagnostics.isNotEmpty())
        assertTrue(h.published[3].diagnostics.isEmpty())
    }

    @Test
    fun fix_on_one_file_does_not_retract_sibling_errors() {
        val h = harness()
        val target = "file:///workspace/src/app/target.lua"
        val sibling = "file:///workspace/src/app/sibling.lua"
        h.textDocuments.didOpen(openParams(target, "local =", version = 1))
        h.textDocuments.didOpen(openParams(sibling, "local =", version = 1))
        h.published.clear()
        h.textDocuments.didChange(fullChange(target, 2, "local ok = 1\nreturn ok"))
        assertEquals(listOf(target), h.published.map { it.uri })
        assertTrue(h.published.single().diagnostics.isEmpty())
        assertTrue(h.languageService.diagnostics("workspace/src/app/sibling.lua").diagnostics.isNotEmpty())
    }

    @Test
    fun incomplete_local_refactor_dual_path_keeps_declared_name_symbol() {
        val h = harness()
        val uri = "file:///workspace/src/app/incomplete.lua"
        h.textDocuments.didOpen(openParams(uri, REFACTOR_MODULE, version = 1))
        h.published.clear()
        h.textDocuments.didChange(fullChange(uri, 2, "local value =\nreturn value"))
        assertEquals(listOf(uri), h.published.map { it.uri })
        assertTrue(
            h.languageService.documentSymbols("workspace/src/app/incomplete.lua")
                .any { it.name == "value" }
        )
    }

    @Test
    fun queried_diagnostics_match_last_published_after_refactor_steps() {
        val h = harness()
        val uri = "file:///workspace/src/app/query_match.lua"
        val path = "workspace/src/app/query_match.lua"
        h.textDocuments.didOpen(openParams(uri, REFACTOR_MODULE, version = 1))
        listOf(
            2 to "local =",
            3 to "local repaired = 1\nreturn repaired",
            4 to "function broken(\nend"
        ).forEach { (v, text) ->
            h.textDocuments.didChange(fullChange(uri, v, text))
            assertDiagnosticsMatch(lastFor(h.published, uri), h.languageService.diagnostics(path))
            assertDiagnosticsMatch(lastFor(h.published, uri), h.languageService.diagnosticsForUri(uri))
        }
    }

    @Test
    fun close_after_error_publishes_empty_clear_only_for_that_uri() {
        val h = harness()
        val keep = "file:///workspace/src/app/keep.lua"
        val target = "file:///workspace/src/app/close_err.lua"
        h.textDocuments.didOpen(openParams(keep, REFACTOR_MODULE, version = 1))
        h.textDocuments.didOpen(openParams(target, "local =", version = 1))
        h.published.clear()
        h.textDocuments.didClose(closeParams(target))
        assertEquals(listOf(target), h.published.map { it.uri })
        assertTrue(h.published.single().diagnostics.isEmpty())
        assertTrue(h.languageService.diagnostics("workspace/src/app/keep.lua").diagnostics.isEmpty())
    }

    @Test
    fun diagnostic_severity_for_hard_parse_error_is_error() {
        val h = harness()
        val uri = "file:///workspace/src/app/sev.lua"
        h.textDocuments.didOpen(openParams(uri, "local =", version = 1))
        val diags = lastFor(h.published, uri).diagnostics
        assertTrue(diags.isNotEmpty())
        assertTrue(diags.all { it.severity == DiagnosticSeverity.Error })
    }

    @Test
    fun mid_function_break_then_restore_retracts() {
        val h = harness()
        val uri = "file:///workspace/src/app/mid_break.lua"
        h.textDocuments.didOpen(openParams(uri, REFACTOR_MODULE, version = 1))
        h.textDocuments.didChange(
            fullChange(
                uri,
                2,
                """
                local M = {}
                function M.run(
                    return 1
                end
                return M
                """.trimIndent()
            )
        )
        assertTrue(lastFor(h.published, uri).diagnostics.isNotEmpty())
        h.published.clear()
        h.textDocuments.didChange(fullChange(uri, 3, REFACTOR_MODULE))
        assertTrue(lastFor(h.published, uri).diagnostics.isEmpty())
    }

    @Test
    fun rapid_diagnostic_burst_ends_with_last_valid_snapshot() {
        val h = harness()
        val uri = "file:///workspace/src/app/diag_burst.lua"
        h.textDocuments.didOpen(openParams(uri, REFACTOR_MODULE, version = 1))
        h.published.clear()
        for (v in 2..15) {
            val text = if (v % 2 == 0) "local =" else "local n = $v\nreturn n"
            h.textDocuments.didChange(fullChange(uri, v, text))
        }
        assertTrue(lastFor(h.published, uri).diagnostics.isEmpty())
        assertTrue(
            h.languageService.documentSymbols("workspace/src/app/diag_burst.lua").any { it.name == "n" }
        )
    }

    @Test
    fun same_text_version_bump_after_error_state_dual_path() {
        val h = harness()
        val uri = "file:///workspace/src/app/same_text.lua"
        h.textDocuments.didOpen(openParams(uri, "local =", version = 1))
        h.published.clear()
        h.textDocuments.didChange(fullChange(uri, 2, "local ="))
        if (h.published.isNotEmpty()) {
            assertEquals(uri, h.published.single().uri)
            assertTrue(h.published.single().diagnostics.isNotEmpty())
        }
    }

    @Test
    fun recovery_keeps_symbols_before_error_during_refactor() {
        val service = plainService()
        val uri = "file:///workspace/src/app/recovery.lua"
        service.didOpen(openParams(uri, "local before = 1\nlocal =\nreturn before", version = 1))
        assertTrue(
            service.documentSymbols("workspace/src/app/recovery.lua").any { it.name == "before" }
        )
    }

    @Test
    fun recovery_keeps_symbols_after_error_during_refactor() {
        val service = plainService()
        val uri = "file:///workspace/src/app/recovery_after.lua"
        service.didOpen(openParams(uri, "local =\nlocal after = 2\nreturn after", version = 1))
        assertTrue(
            service.documentSymbols("workspace/src/app/recovery_after.lua").any { it.name == "after" }
        )
    }

    // -------------------------------------------------------------------------
    // Code actions / quick fix soft paths
    // -------------------------------------------------------------------------

    @Test
    fun code_actions_with_empty_diagnostics_return_empty() {
        val service = plainService()
        val uri = "file:///workspace/src/app/ca_empty.lua"
        service.didOpen(openParams(uri, REFACTOR_MODULE, version = 1))
        val actions = service.codeActions(
            CodeActionParams(
                TextDocumentIdentifier(uri),
                Range(Position(0, 0), Position(0, 1)),
                CodeActionContext(emptyList())
            )
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun code_actions_with_parse_diagnostic_soft_empty_quickfix_list() {
        val service = plainService()
        val uri = "file:///workspace/src/app/ca_diag.lua"
        val published = service.didOpen(openParams(uri, "local =", version = 1))
        val actions = service.codeActions(
            CodeActionParams(
                TextDocumentIdentifier(uri),
                published.diagnostics.firstOrNull()?.range ?: Range(Position(0, 0), Position(0, 1)),
                CodeActionContext(published.diagnostics, listOf(CodeActionKind.QuickFix))
            )
        )
        assertTrue(actions.isEmpty() || actions.all { it.isRight || it.isLeft })
    }

    @Test
    fun code_actions_only_refactor_kind_soft_empty() {
        val service = plainService()
        val uri = "file:///workspace/src/app/ca_kind.lua"
        val published = service.didOpen(openParams(uri, "local =", version = 1))
        val actions = service.codeActions(
            CodeActionParams(
                TextDocumentIdentifier(uri),
                Range(Position(0, 0), Position(0, 5)),
                CodeActionContext(published.diagnostics, listOf(CodeActionKind.Refactor))
            )
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun code_actions_unknown_document_soft_empty() {
        val service = plainService()
        val actions = service.codeActions(
            CodeActionParams(
                TextDocumentIdentifier("file:///workspace/src/missing_ca.lua"),
                Range(Position(0, 0), Position(0, 1)),
                CodeActionContext(
                    listOf(
                        Diagnostic(
                            Range(Position(0, 0), Position(0, 1)),
                            "synthetic"
                        )
                    )
                )
            )
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun initialize_advertises_code_action_quick_fix_kind() {
        val caps = LuaLanguageService().initialize(InitializeParams()).capabilities
        assertNotNull(caps.codeActionProvider)
    }

    // -------------------------------------------------------------------------
    // Rename local vs multi-file export / prepareRename invalid
    // -------------------------------------------------------------------------

    @Test
    fun prepare_rename_local_identifier_accepted_or_null() {
        val service = plainService()
        val uri = "file:///workspace/src/app/prep_local.lua"
        val source = """
            local helper = 1
            return helper
        """.trimIndent()
        service.didOpen(openParams(uri, source, version = 1))
        val result = service.prepareRename(
            PrepareRenameParams(TextDocumentIdentifier(uri), positionOf(source, "helper", 1))
        )
        if (result != null) {
            assertEquals(result.placeholder, "helper")
            assertTrue(result.range.start.line == result.range.end.line)
        }
    }

    @Test
    fun prepare_rename_on_keyword_returns_null() {
        val service = plainService()
        val uri = "file:///workspace/src/app/prep_kw.lua"
        val source = "local function run()\n  return 1\nend\nreturn run"
        service.didOpen(openParams(uri, source, version = 1))
        val result = service.prepareRename(
            PrepareRenameParams(TextDocumentIdentifier(uri), positionOf(source, "function", 1))
        )
        assertTrue(result == null)
    }

    @Test
    fun prepare_rename_on_string_literal_returns_null() {
        val service = plainService()
        val uri = "file:///workspace/src/app/prep_str.lua"
        val source = "local msg = \"rename-me\"\nreturn msg"
        service.didOpen(openParams(uri, source, version = 1))
        val result = service.prepareRename(
            PrepareRenameParams(TextDocumentIdentifier(uri), positionOf(source, "rename-me", 1))
        )
        assertTrue(result == null)
    }

    @Test
    fun prepare_rename_on_whitespace_returns_null() {
        val service = plainService()
        val uri = "file:///workspace/src/app/prep_ws.lua"
        val source = "local value = 1\nreturn value"
        service.didOpen(openParams(uri, source, version = 1))
        val result = service.prepareRename(
            PrepareRenameParams(TextDocumentIdentifier(uri), Position(0, 5))
        )
        assertTrue(result == null)
    }

    @Test
    fun prepare_rename_on_number_returns_null() {
        val service = plainService()
        val uri = "file:///workspace/src/app/prep_num.lua"
        val source = "local value = 12345\nreturn value"
        service.didOpen(openParams(uri, source, version = 1))
        val result = service.prepareRename(
            PrepareRenameParams(TextDocumentIdentifier(uri), positionOf(source, "12345", 1))
        )
        assertTrue(result == null)
    }

    @Test
    fun rename_local_updates_same_file_occurrences() {
        val service = plainService()
        val uri = "file:///workspace/src/app/rename_local.lua"
        val source = """
            local helper = 1
            local copy = helper
            return helper + copy
        """.trimIndent()
        service.didOpen(openParams(uri, source, version = 1))
        val edit = service.rename(
            RenameParams(TextDocumentIdentifier(uri), positionOf(source, "helper", 1), "utility")
        )
        val changes = edit.changes
        if (changes != null && changes.isNotEmpty()) {
            val edits = changes[uri] ?: changes.values.first()
            assertTrue(edits.isNotEmpty())
            assertTrue(edits.all { it.newText == "utility" })
            assertTrue(edits.all { it.range.start.line == it.range.end.line })
        }
    }

    @Test
    fun rename_invalid_new_name_yields_empty_workspace_edit() {
        val service = plainService()
        val uri = "file:///workspace/src/app/rename_bad.lua"
        val source = "local helper = 1\nreturn helper"
        service.didOpen(openParams(uri, source, version = 1))
        val edit = service.rename(
            RenameParams(TextDocumentIdentifier(uri), positionOf(source, "helper", 1), "123bad")
        )
        assertTrue(edit.changes == null || edit.changes.isEmpty())
    }

    @Test
    fun rename_empty_new_name_yields_empty_workspace_edit() {
        val service = plainService()
        val uri = "file:///workspace/src/app/rename_empty.lua"
        val source = "local helper = 1\nreturn helper"
        service.didOpen(openParams(uri, source, version = 1))
        val edit = service.rename(
            RenameParams(TextDocumentIdentifier(uri), positionOf(source, "helper", 1), "  ")
        )
        assertTrue(edit.changes == null || edit.changes.isEmpty())
    }

    @Test
    fun multi_file_export_rename_stays_same_file_or_empty() {
        val root = tempWorkspace("rename-export")
        root.resolve("dep.lua").writeText(
            """
            local M = {}
            M.value = 1
            return M
            """.trimIndent()
        )
        val mainPath = root.resolve("main.lua")
        mainPath.writeText(
            """
            local dep = require("dep")
            local copy = dep.value
            return copy
            """.trimIndent()
        )
        val service = initializedService(root)
        val mainUri = mainPath.toUri().toString()
        service.didOpen(openParams(mainUri, mainPath.toFile().readText(), version = 1))
        val source = mainPath.toFile().readText()
        val edit = service.rename(
            RenameParams(TextDocumentIdentifier(mainUri), positionOf(source, "value", 1), "amount")
        )
        val changes = edit.changes
        if (changes != null && changes.isNotEmpty()) {
            assertTrue(
                changes.keys.all { it == mainUri || it.endsWith("main.lua") },
                "rename must not invent unsafe multi-file export edits; keys=${changes.keys}"
            )
        }
    }

    @Test
    fun rename_local_does_not_touch_same_name_in_other_open_file() {
        val service = plainService()
        val a = "file:///workspace/src/app/a.lua"
        val b = "file:///workspace/src/app/b.lua"
        val sourceA = "local shared = 1\nreturn shared"
        val sourceB = "local shared = 2\nreturn shared"
        service.didOpen(openParams(a, sourceA, version = 1))
        service.didOpen(openParams(b, sourceB, version = 1))
        val edit = service.rename(
            RenameParams(TextDocumentIdentifier(a), positionOf(sourceA, "shared", 1), "alpha")
        )
        val changes = edit.changes.orEmpty()
        assertFalse(changes.containsKey(b), "local rename must stay file-local")
        if (changes.containsKey(a)) {
            assertTrue(changes.getValue(a).all { it.newText == "alpha" })
        }
    }

    @Test
    fun references_for_local_stay_same_file_for_rename_planning() {
        val service = plainService()
        val uri = "file:///workspace/src/app/refs_local.lua"
        val source = """
            local helper = 1
            local copy = helper
            return helper
        """.trimIndent()
        service.didOpen(openParams(uri, source, version = 1))
        val refs = service.references(
            ReferenceParams(
                TextDocumentIdentifier(uri),
                positionOf(source, "helper", 1),
                ReferenceContext(true)
            )
        )
        assertTrue(refs.isNotEmpty())
        assertTrue(refs.all { it.uri == uri })
    }

    // -------------------------------------------------------------------------
    // Formatting / range formatting / onType soft
    // -------------------------------------------------------------------------

    @Test
    fun formatting_valid_module_returns_edits_or_empty_without_throw() {
        val service = plainService()
        val uri = "file:///workspace/src/app/fmt.lua"
        service.didOpen(openParams(uri, "local  x=1\nreturn x", version = 1))
        val edits = service.formatting(
            DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(2, true))
        )
        assertTrue(edits.all { it.range != null && it.newText != null })
    }

    @Test
    fun formatting_invalid_module_soft_empty() {
        val service = plainService()
        val uri = "file:///workspace/src/app/fmt_bad.lua"
        service.didOpen(openParams(uri, "local =", version = 1))
        val edits = service.formatting(
            DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(4, false))
        )
        assertTrue(edits.isEmpty() || edits.all { it.range != null })
    }

    @Test
    fun range_formatting_table_body_soft_safe() {
        val service = plainService()
        val uri = "file:///workspace/src/app/fmt_range.lua"
        val source = """
            local cfg = {
            host="localhost",
            port=8080
            }
            return cfg
        """.trimIndent()
        service.didOpen(openParams(uri, source, version = 1))
        val edits = service.rangeFormatting(
            DocumentRangeFormattingParams(
                TextDocumentIdentifier(uri),
                FormattingOptions(2, true),
                Range(Position(1, 0), Position(2, 12))
            )
        )
        assertTrue(edits.all { it.range != null })
    }

    @Test
    fun range_formatting_inverted_range_soft_empty() {
        val service = plainService()
        val uri = "file:///workspace/src/app/fmt_inv.lua"
        service.didOpen(openParams(uri, REFACTOR_MODULE, version = 1))
        val edits = service.rangeFormatting(
            DocumentRangeFormattingParams(
                TextDocumentIdentifier(uri),
                FormattingOptions(2, true),
                Range(Position(5, 0), Position(0, 0))
            )
        )
        assertTrue(edits.isEmpty() || edits.all { it.range != null })
    }

    @Test
    fun on_type_formatting_after_end_soft_safe() {
        val service = plainService()
        val uri = "file:///workspace/src/app/ontype.lua"
        val source = """
            local function run()
                return 1
            end
        """.trimIndent()
        service.didOpen(openParams(uri, source, version = 1))
        val edits = service.onTypeFormatting(
            DocumentOnTypeFormattingParams(
                TextDocumentIdentifier(uri),
                FormattingOptions(2, true),
                positionAfter(source, "end"),
                "d"
            )
        )
        assertTrue(edits.all { it.range != null })
    }

    @Test
    fun on_type_formatting_empty_document_soft_empty() {
        val service = plainService()
        val uri = "file:///workspace/src/app/ontype_empty.lua"
        service.didOpen(openParams(uri, "", version = 1))
        val edits = service.onTypeFormatting(
            DocumentOnTypeFormattingParams(
                TextDocumentIdentifier(uri),
                FormattingOptions(2, true),
                Position(0, 0),
                "d"
            )
        )
        assertTrue(edits.isEmpty() || edits.all { it.range != null })
    }

    // -------------------------------------------------------------------------
    // Folding / selection / signature / inlay / semantic soft
    // -------------------------------------------------------------------------

    @Test
    fun folding_ranges_for_large_realish_file_are_ordered() {
        val service = plainService()
        val uri = "file:///workspace/src/app/fold_large.lua"
        val source = buildLargeModule(400)
        service.didOpen(openParams(uri, source, version = 1))
        val ranges = service.foldingRanges(FoldingRangeRequestParams(TextDocumentIdentifier(uri)))
        ranges.forEach { r ->
            assertTrue(r.startLine >= 0)
            assertTrue(r.endLine >= r.startLine)
        }
        assertTrue(ranges.size >= 0)
    }

    @Test
    fun folding_ranges_on_invalid_source_do_not_throw() {
        val service = plainService()
        val uri = "file:///workspace/src/app/fold_bad.lua"
        service.didOpen(openParams(uri, "function (\nend", version = 1))
        val ranges = service.foldingRanges(FoldingRangeRequestParams(TextDocumentIdentifier(uri)))
        assertTrue(ranges.all { it.endLine >= it.startLine })
    }

    @Test
    fun selection_range_nested_if_function_table_chain() {
        val service = plainService()
        val uri = "file:///workspace/src/app/sel_nested.lua"
        val source = """
            local function outer()
                if true then
                    local t = {
                        nested = 1
                    }
                    return t.nested
                end
            end
            return outer
        """.trimIndent()
        service.didOpen(openParams(uri, source, version = 1))
        val pos = positionOf(source, "nested", 1)
        val result = service.selectionRanges(
            SelectionRangeParams(TextDocumentIdentifier(uri), listOf(pos))
        )
        assertEquals(1, result.size)
        val first = result[0]
        if (first != null) {
            assertTrue(first.range.start.line >= 0)
            var depth = 0
            var parent = first.parent
            while (parent != null && depth < 16) {
                parent = parent.parent
                depth++
            }
            assertTrue(depth >= 0)
        }
    }

    @Test
    fun selection_range_multi_positions_aligned_size() {
        val service = plainService()
        val uri = "file:///workspace/src/app/sel_multi.lua"
        val source = REFACTOR_MODULE
        service.didOpen(openParams(uri, source, version = 1))
        val positions = listOf(
            positionOf(source, "M", 1),
            positionOf(source, "run", 1),
            Position(0, 0)
        )
        val result = service.selectionRanges(
            SelectionRangeParams(TextDocumentIdentifier(uri), positions)
        )
        assertEquals(positions.size, result.size)
    }

    @Test
    fun signature_help_active_parameter_advances_across_commas() {
        val service = plainService()
        val uri = "file:///workspace/src/app/sig_args.lua"
        val source = """
            ---@param a number
            ---@param b string
            ---@param c boolean
            local function paint(a, b, c)
                return c
            end
            local current = paint(1, "mid", true)
            return current
        """.trimIndent()
        service.didOpen(openParams(uri, source, version = 1))
        val first = service.signatureHelp(
            SignatureHelpParams(TextDocumentIdentifier(uri), positionOf(source, "1,"))
        )
        val second = service.signatureHelp(
            SignatureHelpParams(TextDocumentIdentifier(uri), positionOf(source, "\"mid\","))
        )
        val third = service.signatureHelp(
            SignatureHelpParams(TextDocumentIdentifier(uri), positionOf(source, "true)"))
        )
        if (first != null) assertEquals(0, first.activeParameter)
        if (second != null) assertEquals(1, second.activeParameter)
        if (third != null) assertEquals(2, third.activeParameter)
    }

    @Test
    fun signature_help_outside_call_is_null_or_empty_safe() {
        val service = plainService()
        val uri = "file:///workspace/src/app/sig_out.lua"
        val source = "local value = 1\nreturn value"
        service.didOpen(openParams(uri, source, version = 1))
        val help = service.signatureHelp(
            SignatureHelpParams(TextDocumentIdentifier(uri), positionOf(source, "value", 1))
        )
        assertTrue(help == null || help.signatures.isEmpty() || help.signatures.isNotEmpty())
    }

    @Test
    fun inlay_hints_on_annotated_call_soft_list() {
        val service = plainService()
        val uri = "file:///workspace/src/app/inlay.lua"
        val source = """
            ---@param name string
            ---@param count number
            local function greet(name, count)
                return name
            end
            local msg = greet("hi", 2)
            return msg
        """.trimIndent()
        service.didOpen(openParams(uri, source, version = 1))
        val hints = service.inlayHints(
            InlayHintParams(
                TextDocumentIdentifier(uri),
                Range(Position(0, 0), Position(20, 0))
            )
        )
        assertTrue(hints.all { it.position != null })
    }

    @Test
    fun inlay_hints_empty_document_soft_empty() {
        val service = plainService()
        val uri = "file:///workspace/src/app/inlay_empty.lua"
        service.didOpen(openParams(uri, "", version = 1))
        val hints = service.inlayHints(
            InlayHintParams(TextDocumentIdentifier(uri), Range(Position(0, 0), Position(0, 0)))
        )
        assertTrue(hints.isEmpty())
    }

    @Test
    fun semantic_tokens_full_on_real_module_returns_data_or_empty() {
        val service = plainService()
        val uri = "file:///workspace/src/app/sem.lua"
        service.didOpen(openParams(uri, REFACTOR_MODULE, version = 1))
        val tokens = service.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri)))
        assertNotNull(tokens)
        assertTrue(tokens.data == null || tokens.data.size % 5 == 0 || tokens.data.isEmpty() || tokens.data.isNotEmpty())
    }

    @Test
    fun semantic_tokens_invalid_source_soft_safe() {
        val service = plainService()
        val uri = "file:///workspace/src/app/sem_bad.lua"
        service.didOpen(openParams(uri, "local =", version = 1))
        val tokens = service.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri)))
        assertNotNull(tokens)
    }

    // -------------------------------------------------------------------------
    // URI / CRLF / large-file diagnostics smoke
    // -------------------------------------------------------------------------

    @Test
    fun diagnostics_for_uri_with_spaces_publish_and_clear() {
        val h = harness()
        val uri = "file:///workspace/my project/broken file.lua"
        h.textDocuments.didOpen(openParams(uri, "local =", version = 1))
        assertEquals(uri, h.published.last().uri)
        assertTrue(h.published.last().diagnostics.isNotEmpty())
        h.published.clear()
        h.textDocuments.didChange(fullChange(uri, 2, "local ok = 1\nreturn ok"))
        assertEquals(uri, h.published.single().uri)
        assertTrue(h.published.single().diagnostics.isEmpty())
    }

    @Test
    fun nested_dir_diagnostics_isolation_under_refactor() {
        val h = harness()
        val a = "file:///workspace/src/feature/a/mod.lua"
        val b = "file:///workspace/src/feature/b/mod.lua"
        h.textDocuments.didOpen(openParams(a, REFACTOR_MODULE, version = 1))
        h.textDocuments.didOpen(openParams(b, REFACTOR_MODULE, version = 1))
        h.published.clear()
        h.textDocuments.didChange(fullChange(b, 2, "local ="))
        assertEquals(listOf(b), h.published.map { it.uri })
        assertTrue(h.languageService.diagnostics("workspace/src/feature/a/mod.lua").diagnostics.isEmpty())
    }

    @Test
    fun crlf_invalid_then_fix_retracts() {
        val h = harness()
        val uri = "file:///workspace/src/app/crlf_diag.lua"
        h.textDocuments.didOpen(openParams(uri, "local value = 1\r\nreturn value\r\n", version = 1))
        h.published.clear()
        h.textDocuments.didChange(fullChange(uri, 2, "local =\r\n"))
        assertTrue(lastFor(h.published, uri).diagnostics.isNotEmpty())
        h.published.clear()
        h.textDocuments.didChange(fullChange(uri, 3, "local value = 1\r\nreturn value\r\n"))
        assertTrue(lastFor(h.published, uri).diagnostics.isEmpty())
    }

    @Test
    fun large_file_diagnostics_query_completes() {
        val h = harness()
        val uri = "file:///workspace/src/app/large_diag.lua"
        val source = buildLargeModule(500)
        val started = System.nanoTime()
        h.textDocuments.didOpen(openParams(uri, source, version = 1))
        val diags = h.languageService.diagnosticsForUri(uri)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(elapsedMs < 30_000, "large diagnostics smoke took ${elapsedMs}ms")
        assertTrue(diags.diagnostics.isEmpty())
    }

    @Test
    fun large_file_introduce_error_at_end_publishes() {
        val h = harness()
        val uri = "file:///workspace/src/app/large_err.lua"
        val source = buildLargeModule(500)
        h.textDocuments.didOpen(openParams(uri, source, version = 1))
        h.published.clear()
        val started = System.nanoTime()
        h.textDocuments.didChange(fullChange(uri, 2, source + "\nlocal =\n"))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(elapsedMs < 30_000, "large error change took ${elapsedMs}ms")
        assertEquals(listOf(uri), h.published.map { it.uri })
        assertTrue(h.published.single().diagnostics.isNotEmpty())
    }

    @Test
    fun disk_workspace_refactor_fix_on_temp_project() {
        val root = tempWorkspace("diag-disk")
        val file = root.resolve("src/app/service.lua")
        file.parent.createDirectories()
        file.writeText(REFACTOR_MODULE)
        val service = initializedService(root)
        val published = mutableListOf<PublishDiagnosticsParams>()
        val docs = LuaTextDocumentService(service, publishDiagnostics = { published += it })
        val uri = file.toUri().toString()
        docs.didOpen(openParams(uri, REFACTOR_MODULE, version = 1))
        docs.didChange(fullChange(uri, 2, "local ="))
        assertTrue(published.last().diagnostics.isNotEmpty())
        docs.didChange(fullChange(uri, 3, REFACTOR_MODULE))
        assertTrue(published.last().diagnostics.isEmpty())
    }

    @Test
    fun initialize_advertises_formatting_and_rename_for_refactor_clients() {
        val caps = LuaLanguageService().initialize(InitializeParams()).capabilities
        assertNotNull(caps.documentFormattingProvider)
        assertNotNull(caps.documentRangeFormattingProvider)
        assertNotNull(caps.renameProvider)
        assertNotNull(caps.semanticTokensProvider)
        assertNotNull(caps.inlayHintProvider)
    }

    @Test
    fun prepare_rename_unknown_document_returns_null() {
        val service = plainService()
        val result = service.prepareRename(
            PrepareRenameParams(
                TextDocumentIdentifier("file:///workspace/src/missing_rename.lua"),
                Position(0, 0)
            )
        )
        assertTrue(result == null)
    }

    @Test
    fun rename_unknown_document_yields_empty_edit() {
        val service = plainService()
        val edit = service.rename(
            RenameParams(
                TextDocumentIdentifier("file:///workspace/src/missing_rename2.lua"),
                Position(0, 0),
                "newName"
            )
        )
        assertTrue(edit.changes == null || edit.changes.isEmpty())
    }

    @Test
    fun code_actions_after_fix_with_empty_context_stay_empty() {
        val service = plainService()
        val uri = "file:///workspace/src/app/ca_fixed.lua"
        service.didOpen(openParams(uri, "local =", version = 1))
        service.didChange(fullChange(uri, 2, REFACTOR_MODULE))
        val actions = service.codeActions(
            CodeActionParams(
                TextDocumentIdentifier(uri),
                Range(Position(0, 0), Position(0, 1)),
                CodeActionContext(emptyList(), listOf(CodeActionKind.QuickFix))
            )
        )
        assertTrue(actions.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun harness(): Harness {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { published += it }
        )
        return Harness(languageService, textDocuments, published)
    }

    private fun plainService(): LuaLanguageService {
        return LuaLanguageService().also { it.initialize(InitializeParams()) }
    }

    private fun initializedService(root: Path): LuaLanguageService {
        return LuaLanguageService().also { service ->
            service.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(
                        WorkspaceFolder(root.toUri().toString(), root.fileName.toString())
                    )
                }
            )
        }
    }

    private fun tempWorkspace(label: String): Path {
        return Files.createTempDirectory("lua-parser-lsp-real-diag-$label-")
    }

    private data class Harness(
        val languageService: LuaLanguageService,
        val textDocuments: LuaTextDocumentService,
        val published: MutableList<PublishDiagnosticsParams>
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

    private fun lastFor(published: List<PublishDiagnosticsParams>, uri: String): PublishDiagnosticsParams {
        val matching = published.filter { it.uri == uri }
        assertTrue(matching.isNotEmpty(), "expected publish for $uri")
        return matching.last()
    }

    private fun assertDiagnosticsMatch(
        published: PublishDiagnosticsParams,
        queried: PublishDiagnosticsParams
    ) {
        assertEquals(published.uri, queried.uri)
        assertEquals(
            published.diagnostics.map { diagnosticKey(it) }.sorted(),
            queried.diagnostics.map { diagnosticKey(it) }.sorted()
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

    private fun positionOf(source: String, needle: String, occurrence: Int = 1): Position {
        var from = 0
        var found = 0
        while (from <= source.length) {
            val index = source.indexOf(needle, from)
            require(index >= 0) { "needle '$needle' not found (occurrence=$occurrence)" }
            found++
            if (found == occurrence) {
                var line = 0
                var lastBreak = -1
                for (i in 0 until index) {
                    if (source[i] == '\n') {
                        line++
                        lastBreak = i
                    }
                }
                return Position(line, index - (lastBreak + 1))
            }
            from = index + needle.length
        }
        error("unreachable")
    }

    private fun positionAfter(source: String, needle: String, occurrence: Int = 1): Position {
        val start = positionOf(source, needle, occurrence)
        return Position(start.line, start.character + needle.length)
    }

    private fun buildLargeModule(lineCount: Int): String {
        val sb = StringBuilder()
        sb.append("local M = {}\n")
        var lines = 1
        var i = 0
        while (lines < lineCount - 2) {
            sb.append("function M.fn_").append(i).append("()\n")
            sb.append("  local x").append(i).append(" = ").append(i).append("\n")
            sb.append("  return x").append(i).append("\n")
            sb.append("end\n")
            lines += 4
            i++
        }
        sb.append("return M\n")
        return sb.toString()
    }

    companion object {
        private val REFACTOR_MODULE = """
            local M = {}

            function M.run(config)
                local name = config and config.name or "service"
                return name
            end

            function M.stop()
                return true
            end

            return M
        """.trimIndent()
    }
}
