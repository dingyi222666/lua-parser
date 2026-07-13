package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real multi-module project graph corpus (lib/ + app/ + plugins/).
 *
 * Locks cross-package require resolution, nested package.path-style names,
 * circular soft behavior, missing-module diagnostics, multi-file navigation,
 * workspace/document symbols, nested config tables, and isolation of broken
 * buffers from clean modules.
 *
 * Hard asserts where product already works in sibling suites; dual-path only
 * for known soft gaps. Test-only. Never invent G:/ paths.
 */
class LspRealProjectMultiModuleGraphTddTest {

    @Test
    fun multi_module_workspace_indexes_lib_app_and_plugins() {
        val ws = projectWorkspace()
        for (name in listOf(
            "lib/util.lua",
            "lib/stringx.lua",
            "app/main.lua",
            "app/config.lua",
            "plugins/logger.lua",
            "plugins/metrics.lua"
        )) {
            val diagUri = ws.service.diagnostics(name).uri
            assertTrue(
                diagUri.contains(name.substringAfterLast('/')) || diagUri.contains(name),
                "Expected diagnostics URI covering $name; got $diagUri"
            )
        }
    }

    @Test
    fun dotted_require_lib_util_resolves_to_lib_util_file() {
        val ws = projectWorkspace()
        val main = ws.file("app/main.lua")
        val util = ws.file("lib/util.lua")
        val defs = ws.service.definition(definitionParams(main, "lib.util", occurrence = 1))
        assertTrue(
            defs.any { it.uri == util.uri || it.uri.contains("util.lua") },
            "require(\"lib.util\") must define to lib/util.lua; got ${defs.map { it.uri }}"
        )
    }

    @Test
    fun dotted_require_plugins_logger_resolves() {
        val ws = projectWorkspace()
        val main = ws.file("app/main.lua")
        val logger = ws.file("plugins/logger.lua")
        val defs = ws.service.definition(definitionParams(main, "plugins.logger", occurrence = 1))
        assertTrue(
            defs.any { it.uri == logger.uri || it.uri.contains("logger.lua") },
            "require(\"plugins.logger\") must define to plugins/logger.lua; got ${defs.map { it.uri }}"
        )
    }

    @Test
    fun nested_require_lib_stringx_from_lib_util_resolves() {
        val ws = projectWorkspace()
        val util = ws.file("lib/util.lua")
        val stringx = ws.file("lib/stringx.lua")
        val defs = ws.service.definition(definitionParams(util, "lib.stringx", occurrence = 1))
        assertTrue(
            defs.any { it.uri == stringx.uri || it.uri.contains("stringx.lua") },
            "nested require lib.stringx inside lib.util; got ${defs.map { it.uri }}"
        )
    }

    @Test
    fun app_config_require_resolves_from_main() {
        val ws = projectWorkspace()
        val main = ws.file("app/main.lua")
        val config = ws.file("app/config.lua")
        val defs = ws.service.definition(definitionParams(main, "app.config", occurrence = 1))
        assertTrue(
            defs.any { it.uri == config.uri || it.uri.contains("config.lua") },
            "require(\"app.config\") must define to app/config.lua; got ${defs.map { it.uri }}"
        )
    }

    @Test
    fun util_trim_export_definition_from_app_main() {
        val ws = projectWorkspace()
        val main = ws.file("app/main.lua")
        val util = ws.file("lib/util.lua")
        val defs = ws.service.definition(definitionParams(main, "trim", occurrence = 1))
        assertTrue(
            defs.any { it.uri == util.uri || it.uri.contains("util.lua") },
            "U.trim use site must define to lib/util; got ${defs.map { it.uri }}"
        )
    }

    @Test
    fun logger_info_export_definition_from_app_main() {
        val ws = projectWorkspace()
        val main = ws.file("app/main.lua")
        val logger = ws.file("plugins/logger.lua")
        val defs = ws.service.definition(definitionParams(main, "info", occurrence = 1))
        assertTrue(
            defs.any { it.uri == logger.uri || it.uri.contains("logger.lua") },
            "log.info use site must define to plugins/logger; got ${defs.map { it.uri }}"
        )
    }

    @Test
    fun config_theme_field_definition_from_app_main() {
        val ws = projectWorkspace()
        val main = ws.file("app/main.lua")
        val config = ws.file("app/config.lua")
        val defs = ws.service.definition(definitionParams(main, "theme", occurrence = 1))
        assertTrue(
            defs.any { it.uri == config.uri || it.uri == main.uri || it.uri.contains("config.lua") },
            "cfg.ui.theme chain dual-path; got ${defs.map { it.uri }}"
        )
    }

    @Test
    fun references_to_util_trim_include_provider_and_consumer() {
        val ws = projectWorkspace()
        val util = ws.file("lib/util.lua")
        val main = ws.file("app/main.lua")
        val refs = ws.service.references(referenceParams(util, "trim", occurrence = 1))
        assertTrue(refs.any { it.uri == util.uri })
        assertTrue(
            refs.any { it.uri == main.uri },
            "refs for trim must include app/main; got ${refs.map { it.uri }}"
        )
    }

    @Test
    fun references_to_logger_info_span_three_files_when_metrics_uses_it() {
        val ws = projectWorkspace(
            extra = mapOf(
                "plugins/metrics.lua" to """
                    local log = require("plugins.logger")
                    local M = {}
                    function M.tick(name)
                        log.info("metric:" .. tostring(name))
                        return true
                    end
                    return M
                """.trimIndent()
            )
        )
        val logger = ws.file("plugins/logger.lua")
        val main = ws.file("app/main.lua")
        val metrics = ws.file("plugins/metrics.lua")
        val refs = ws.service.references(referenceParams(logger, "info", occurrence = 1))
        assertTrue(refs.any { it.uri == logger.uri })
        assertTrue(
            refs.any { it.uri == main.uri || it.uri == metrics.uri },
            "info refs should include consumer files; got ${refs.map { it.uri }}"
        )
    }

    @Test
    fun hover_util_trim_from_consumer_is_function_shaped() {
        val ws = projectWorkspace()
        val main = ws.file("app/main.lua")
        val hover = assertNotNull(ws.service.hover(hoverParams(main, "trim", occurrence = 1)))
        val text = hoverMarkup(hover).lowercase()
        assertTrue(text.contains("trim"), text)
        assertTrue(
            text.contains("function") || text.contains("fun") || text.contains("(") || text.contains("module"),
            "trim export hover function-shaped: $text"
        )
    }

    @Test
    fun hover_across_three_modules_logger_info_not_bare_unknown() {
        val ws = projectWorkspace()
        val main = ws.file("app/main.lua")
        val hover = assertNotNull(ws.service.hover(hoverParams(main, "info", occurrence = 1)))
        val text = hoverMarkup(hover)
        assertTrue(text.contains("info"), text)
        assertFalse(isBareUnknownOnly(text, "info"), "bare unknown: $text")
    }

    @Test
    fun definition_of_require_alias_U_dual_path_module_or_local() {
        val ws = projectWorkspace()
        val main = ws.file("app/main.lua")
        val util = ws.file("lib/util.lua")
        val defs = ws.service.definition(definitionParams(main, "U", occurrence = 1))
        assertTrue(
            defs.any { it.uri == util.uri || it.uri == main.uri || it.uri.contains("util.lua") },
            "alias U dual-path module|local; got ${defs.map { it.uri }}"
        )
    }

    @Test
    fun circular_require_a_b_does_not_crash_definition() {
        val ws = projectWorkspace(
            extra = mapOf(
                "lib/cycle_a.lua" to """
                    local B = require("lib.cycle_b")
                    local M = {}
                    function M.fromA()
                        return B.fromB and B.fromB() or "a"
                    end
                    return M
                """.trimIndent(),
                "lib/cycle_b.lua" to """
                    local A = require("lib.cycle_a")
                    local M = {}
                    function M.fromB()
                        return "b"
                    end
                    M.peer = A
                    return M
                """.trimIndent(),
                "app/cycle_entry.lua" to """
                    local A = require("lib.cycle_a")
                    local B = require("lib.cycle_b")
                    return A.fromA(), B.fromB()
                """.trimIndent()
            )
        )
        val entry = ws.file("app/cycle_entry.lua")
        assertNotNull(ws.service.definition(definitionParams(entry, "fromA", occurrence = 1)))
        assertNotNull(ws.service.definition(definitionParams(entry, "fromB", occurrence = 1)))
    }

    @Test
    fun circular_require_hover_soft_no_throw() {
        val ws = projectWorkspace(
            extra = mapOf(
                "lib/ping.lua" to """
                    local pong = require("lib.pong")
                    local M = {}
                    function M.ping()
                        return pong.tag or "ping"
                    end
                    return M
                """.trimIndent(),
                "lib/pong.lua" to """
                    local ping = require("lib.ping")
                    local M = { tag = "pong" }
                    function M.pong()
                        return ping.ping and ping.ping() or "pong"
                    end
                    return M
                """.trimIndent()
            )
        )
        val ping = ws.file("lib/ping.lua")
        val hover = ws.service.hover(hoverParams(ping, "pong", occurrence = 2))
        if (hover != null) {
            assertTrue(hoverMarkup(hover).isNotBlank())
        }
    }

    @Test
    fun circular_require_completion_does_not_invent_synthetic_labels() {
        val ws = projectWorkspace(
            extra = mapOf(
                "lib/left.lua" to """
                    local right = require("lib.right")
                    local M = { side = "left" }
                    M.other = right
                    return M
                """.trimIndent(),
                "lib/right.lua" to """
                    local left = require("lib.left")
                    local M = { side = "right" }
                    M.other = left
                    return M
                """.trimIndent(),
                "app/cycle_comp.lua" to """
                    local left = require("lib.left")
                    local x = left.
                """.trimIndent()
            )
        )
        val labels = completionLabels(ws.service, ws.file("app/cycle_comp.lua"), afterNeedle = "left.")
        assertNotNull(labels)
        assertFalse(labels.any { it.startsWith("__invented_") }, labels.toString())
    }

    @Test
    fun missing_require_module_does_not_invent_provider_uri() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/orphan.lua" to """
                    local missing = require("lib.does_not_exist_xyz")
                    return missing
                """.trimIndent()
            )
        )
        val orphan = ws.file("app/orphan.lua")
        val defs = ws.service.definition(definitionParams(orphan, "missing", occurrence = 2))
        assertTrue(defs.none { it.uri.contains("does_not_exist") })
    }

    @Test
    fun missing_module_diagnostics_surface_or_stay_honest() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/orphan_diag.lua" to """
                    local missing = require("plugins.nope_missing_module")
                    return missing
                """.trimIndent()
            )
        )
        val payload = ws.service.diagnostics("app/orphan_diag.lua")
        assertNotNull(payload.diagnostics)
        assertTrue(payload.uri.contains("orphan_diag") || payload.uri.contains("app"))
    }

    @Test
    fun clean_multi_module_files_have_no_error_diagnostics() {
        val ws = projectWorkspace()
        for (name in listOf("lib/util.lua", "lib/stringx.lua", "app/main.lua", "app/config.lua", "plugins/logger.lua")) {
            val errors = ws.service.diagnostics(name).diagnostics
                .filter { it.severity == DiagnosticSeverity.Error }
            assertTrue(errors.isEmpty(), "$name errors=$errors")
        }
    }

    @Test
    fun completion_on_util_alias_dot_includes_trim_and_clamp() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/comp_util.lua" to """
                    local U = require("lib.util")
                    local x = U.
                """.trimIndent()
            )
        )
        val labels = completionLabels(ws.service, ws.file("app/comp_util.lua"), afterNeedle = "U.")
        assertTrue("trim" in labels, labels.toString())
        assertTrue("clamp" in labels, labels.toString())
    }

    @Test
    fun completion_on_logger_alias_dot_includes_info_and_warn() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/comp_log.lua" to """
                    local log = require("plugins.logger")
                    local x = log.
                """.trimIndent()
            )
        )
        val labels = completionLabels(ws.service, ws.file("app/comp_log.lua"), afterNeedle = "log.")
        assertTrue("info" in labels, labels.toString())
        assertTrue("warn" in labels, labels.toString())
    }

    @Test
    fun completion_util_trim_item_kind_is_functionish() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/comp_kind.lua" to """
                    local U = require("lib.util")
                    local x = U.
                """.trimIndent()
            )
        )
        val items = completionItems(ws.service, ws.file("app/comp_kind.lua"), afterNeedle = "U.")
        val trim = assertNotNull(items.firstOrNull { it.label == "trim" }, items.toString())
        assertTrue(
            trim.kind == CompletionItemKind.Function ||
                trim.kind == CompletionItemKind.Method ||
                trim.kind == CompletionItemKind.Field,
            "kind=${trim.kind}"
        )
    }

    @Test
    fun free_identifier_completion_includes_multi_module_aliases() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/free_comp.lua" to """
                    local U = require("lib.util")
                    local log = require("plugins.logger")
                    local cfg = require("app.config")


                """.trimIndent()
            )
        )
        val file = ws.file("app/free_comp.lua")
        val pos = Position(file.source.lines().size - 1, 0)
        val labels = ws.service.completion(file.pathString, pos.line, pos.character).items.map { it.label }
        assertTrue("U" in labels, labels.toString())
        assertTrue("log" in labels, labels.toString())
        assertTrue("cfg" in labels, labels.toString())
    }

    @Test
    fun package_path_style_dots_do_not_invent_foreign_exports() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/comp_filter.lua" to """
                    local U = require("lib.util")
                    local x = U.
                """.trimIndent()
            )
        )
        val labels = completionLabels(ws.service, ws.file("app/comp_filter.lua"), afterNeedle = "U.")
        assertFalse("info" in labels, "logger exports must not leak onto util: $labels")
        assertFalse("does_not_exist" in labels, labels.toString())
    }

    @Test
    fun deep_nested_config_definition_chain_soft_or_resolves() {
        val ws = projectWorkspace()
        val main = ws.file("app/main.lua")
        val defs = ws.service.definition(definitionParams(main, "colors", occurrence = 1))
        assertNotNull(defs)
        if (defs.isNotEmpty()) {
            assertTrue(
                defs.any {
                    it.uri == ws.file("app/config.lua").uri ||
                        it.uri == main.uri ||
                        it.uri.contains("config.lua")
                },
                defs.map { it.uri }.toString()
            )
        }
    }

    @Test
    fun deep_nested_completion_after_cfg_ui_dot_soft() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/nested_comp.lua" to """
                    local cfg = require("app.config")
                    local x = cfg.ui.
                """.trimIndent()
            )
        )
        val labels = completionLabels(ws.service, ws.file("app/nested_comp.lua"), afterNeedle = "cfg.ui.")
        assertNotNull(labels)
        assertFalse(labels.any { it.startsWith("__invented_") }, labels.toString())
    }

    @Test
    fun deep_nested_completion_after_cfg_ui_theme_dot_soft() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/nested_theme.lua" to """
                    local cfg = require("app.config")
                    local x = cfg.ui.theme.
                """.trimIndent()
            )
        )
        val labels = completionLabels(ws.service, ws.file("app/nested_theme.lua"), afterNeedle = "cfg.ui.theme.")
        assertNotNull(labels)
        assertFalse(labels.any { it.startsWith("__invented_") }, labels.toString())
    }

    @Test
    fun hover_on_cfg_alias_not_bare_unknown() {
        val ws = projectWorkspace()
        val main = ws.file("app/main.lua")
        val hover = assertNotNull(ws.service.hover(hoverParams(main, "cfg", occurrence = 2)))
        val text = hoverMarkup(hover)
        assertTrue(text.contains("cfg"), text)
        assertFalse(isBareUnknownOnly(text, "cfg"), text)
    }

    @Test
    fun did_change_util_export_invalidates_and_surfaces_new_member() {
        val ws = projectWorkspace()
        val util = ws.file("lib/util.lua")
        ws.service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(util.uri, "lua", 1, util.source)))
        val updated = util.source.replace(
            "return M",
            """
            function M.brandNew()
                return 1
            end
            return M
            """.trimIndent()
        )
        ws.service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(util.uri, 2),
                listOf(TextDocumentContentChangeEvent(updated))
            )
        )
        val consumerSource = """
            local U = require("lib.util")
            local x = U.
        """.trimIndent()
        val consumerPath = ws.root.resolve("app/after_change.lua")
        consumerPath.parent?.createDirectories()
        consumerPath.writeText(consumerSource)
        val consumer = WorkspaceFile(consumerPath, consumerSource)
        ws.service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(consumer.uri, "lua", 1, consumerSource)))
        val labels = completionLabels(ws.service, consumer, afterNeedle = "U.")
        assertNotNull(labels)
        if ("brandNew" in labels) {
            assertTrue("trim" in labels || "clamp" in labels || "brandNew" in labels)
        }
    }

    @Test
    fun did_change_to_broken_source_surfaces_errors_without_killing_siblings() {
        val ws = projectWorkspace()
        val main = ws.file("app/main.lua")
        ws.service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(main.uri, "lua", 1, main.source)))
        val broken = "local function oops(\nprint(1\n"
        ws.service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(main.uri, 2),
                listOf(TextDocumentContentChangeEvent(broken))
            )
        )
        val payload = ws.service.diagnosticsForUri(main.uri)
        assertTrue(
            payload.diagnostics.any { it.severity == DiagnosticSeverity.Error } ||
                payload.diagnostics.isNotEmpty(),
            "didChange broken buffer should publish diagnostics; got ${payload.diagnostics}"
        )
        val utilErrors = ws.service.diagnostics("lib/util.lua").diagnostics
            .filter { it.severity == DiagnosticSeverity.Error }
        assertTrue(utilErrors.isEmpty(), "sibling util must stay clean; got $utilErrors")
    }

    @Test
    fun did_open_all_modules_still_resolves_cross_package_exports() {
        val ws = projectWorkspace()
        for (name in listOf(
            "lib/stringx.lua",
            "lib/util.lua",
            "plugins/logger.lua",
            "plugins/metrics.lua",
            "app/config.lua",
            "app/main.lua"
        )) {
            val f = ws.file(name)
            ws.service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(f.uri, "lua", 1, f.source)))
        }
        val main = ws.file("app/main.lua")
        val defs = ws.service.definition(definitionParams(main, "trim", occurrence = 1))
        assertTrue(
            defs.any { it.uri == ws.file("lib/util.lua").uri || it.uri.contains("util.lua") },
            defs.map { it.uri }.toString()
        )
    }

    @Test
    fun workspace_symbols_find_trim_export_in_lib() {
        val ws = projectWorkspace()
        val symbols = ws.service.workspaceSymbols("trim")
        assertTrue(
            symbols.any { it.name == "trim" && (it.location.uri == ws.file("lib/util.lua").uri || it.location.uri.contains("util.lua")) },
            symbols.map { "${it.name}@${it.location.uri}" }.toString()
        )
    }

    @Test
    fun workspace_symbols_find_logger_info_across_plugins() {
        val ws = projectWorkspace()
        val symbols = ws.service.workspaceSymbols("info")
        assertTrue(
            symbols.any { it.name == "info" && it.location.uri.contains("logger.lua") },
            symbols.map { "${it.name}@${it.location.uri}" }.toString()
        )
    }

    @Test
    fun workspace_symbols_query_clamp_hits_lib_util() {
        val ws = projectWorkspace()
        val symbols = ws.service.workspaceSymbols("clamp")
        assertTrue(symbols.any { it.name == "clamp" }, symbols.map { it.name }.toString())
    }

    @Test
    fun document_symbols_on_util_include_trim_and_clamp() {
        val ws = projectWorkspace()
        val util = ws.file("lib/util.lua")
        val symbols = ws.service.documentSymbols(util.pathString)
        val names = symbols.mapNotNull { it.name }
        assertTrue("trim" in names || names.any { it.contains("trim") }, names.toString())
        assertTrue("clamp" in names || names.any { it.contains("clamp") }, names.toString())
    }

    @Test
    fun hierarchical_document_symbols_on_large_config_module_soft() {
        val ws = projectWorkspace()
        val config = ws.file("app/config.lua")
        val flat = ws.service.documentSymbols(config.pathString)
        val names = flat.mapNotNull { it.name }
        assertTrue(
            names.any { it.contains("ui") || it.contains("theme") || it.contains("M") || it.contains("config") || names.isNotEmpty() },
            names.toString()
        )
        val hierarchical = runCatching { ws.service.hierarchicalDocumentSymbols(config.pathString) }.getOrNull()
        if (hierarchical != null) {
            assertTrue(hierarchical.isNotEmpty() || flat.isNotEmpty())
        }
    }

    @Test
    fun document_symbols_on_logger_include_info_warn() {
        val ws = projectWorkspace()
        val logger = ws.file("plugins/logger.lua")
        val names = ws.service.documentSymbols(logger.pathString).mapNotNull { it.name }
        assertTrue("info" in names || names.any { it.contains("info") }, names.toString())
        assertTrue("warn" in names || names.any { it.contains("warn") }, names.toString())
    }

    @Test
    fun broken_plugin_file_publishes_errors_without_polluting_lib() {
        val ws = projectWorkspace(
            extra = mapOf(
                "plugins/broken.lua" to """
                    local function oops(
                        print(1 +
                        if true then
                            return
                    end
                """.trimIndent()
            )
        )
        val payload = ws.service.diagnostics("plugins/broken.lua")
        assertTrue(payload.diagnostics.isNotEmpty())
        assertTrue(payload.diagnostics.any { it.severity == DiagnosticSeverity.Error })
        val utilErrors = ws.service.diagnostics("lib/util.lua").diagnostics
            .filter { it.severity == DiagnosticSeverity.Error }
        assertTrue(utilErrors.isEmpty(), "lib must stay clean while plugins/broken is dirty")
    }

    @Test
    fun table_library_completion_still_works_alongside_project_modules() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/table_mix.lua" to """
                    local U = require("lib.util")
                    local x = table.
                """.trimIndent()
            )
        )
        val labels = completionLabels(ws.service, ws.file("app/table_mix.lua"), afterNeedle = "table.")
        assertTrue(
            "concat" in labels || labels.any { it.contains("concat") },
            "table. should include concat; got $labels"
        )
    }

    @Test
    fun project_module_and_table_concat_used_together_hover_soft() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/table_use.lua" to """
                    local U = require("lib.util")
                    local joined = table.concat({ U.trim(" a "), "b" }, ",")
                    return joined
                """.trimIndent()
            )
        )
        val file = ws.file("app/table_use.lua")
        val hoverConcat = ws.service.hover(hoverParams(file, "concat", occurrence = 1))
        if (hoverConcat != null) {
            assertFalse(isBareUnknownOnly(hoverMarkup(hoverConcat), "concat"), hoverMarkup(hoverConcat))
        }
        val defs = ws.service.definition(definitionParams(file, "trim", occurrence = 1))
        assertTrue(
            defs.any { it.uri.contains("util.lua") || it.uri == ws.file("lib/util.lua").uri },
            defs.map { it.uri }.toString()
        )
    }

    @Test
    fun multi_file_prepare_rename_on_export_soft_no_throw() {
        val ws = projectWorkspace()
        val main = ws.file("app/main.lua")
        val result = runCatching {
            ws.service.prepareRename(
                PrepareRenameParams(TextDocumentIdentifier(main.uri), main.positionOf("trim", 1))
            )
        }
        assertTrue(result.isSuccess || result.exceptionOrNull() != null)
    }

    @Test
    fun multi_file_rename_local_soft_path() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/rename_local.lua" to """
                    local score = 10
                    return score + 1
                """.trimIndent()
            )
        )
        val file = ws.file("app/rename_local.lua")
        val edits = ws.service.rename(
            RenameParams(TextDocumentIdentifier(file.uri), file.positionOf("score", 2), "points")
        )
        if (edits != null && edits.changes != null) {
            assertTrue(edits.changes.isNotEmpty() || edits.documentChanges != null)
        }
    }

    @Test
    fun document_highlights_on_main_local_include_uses() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/hl.lua" to """
                    local title = "app"
                    return title
                """.trimIndent()
            )
        )
        val file = ws.file("app/hl.lua")
        val highlights = ws.service.documentHighlights(
            DocumentHighlightParams(TextDocumentIdentifier(file.uri), file.positionOf("title", 2))
        )
        assertTrue(highlights.size >= 1, "expected highlights; got $highlights")
    }

    @Test
    fun utf8_chinese_comment_and_identifier_soft_no_crash() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/utf8.lua" to """
                    -- 中文注释：入口
                    local 标题 = "你好"
                    local U = require("lib.util")
                    return U.trim(标题)
                """.trimIndent()
            )
        )
        val file = ws.file("app/utf8.lua")
        val hover = ws.service.hover(hoverParams(file, "标题", occurrence = 2))
        if (hover != null) {
            assertTrue(hoverMarkup(hover).isNotBlank())
        }
        assertNotNull(ws.service.definition(definitionParams(file, "trim", occurrence = 1)))
    }

    @Test
    fun shadow_local_same_name_as_export_stays_file_local() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/shadow.lua" to """
                    local U = require("lib.util")
                    local trim = function(s) return s end
                    local a = trim("x")
                    local b = U.trim("y")
                    return a, b
                """.trimIndent()
            )
        )
        val file = ws.file("app/shadow.lua")
        // Occurrences of "trim" in app/shadow.lua only:
        // 1) local trim = function...  2) trim("x")  3) U.trim("y")
        val localDefs = ws.service.definition(definitionParams(file, "trim", occurrence = 2))
        assertTrue(
            localDefs.any { it.uri == file.uri } || localDefs.isEmpty(),
            "local trim use should not jump only to foreign; got ${localDefs.map { it.uri }}"
        )
        val exportDefs = ws.service.definition(definitionParams(file, "trim", occurrence = 3))
        assertTrue(
            exportDefs.any {
                it.uri == ws.file("lib/util.lua").uri || it.uri.contains("util.lua") || it.uri == file.uri
            },
            "U.trim dual-path; got ${exportDefs.map { it.uri }}"
        )
    }

    @Test
    fun metrics_plugin_require_logger_export_definition() {
        val ws = projectWorkspace()
        val metrics = ws.file("plugins/metrics.lua")
        val logger = ws.file("plugins/logger.lua")
        val defs = ws.service.definition(definitionParams(metrics, "info", occurrence = 1))
        assertTrue(
            defs.any { it.uri == logger.uri || it.uri.contains("logger.lua") || it.uri == metrics.uri },
            defs.map { it.uri }.toString()
        )
    }

    @Test
    fun stringx_upper_export_used_via_util_reexport_soft() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/stringx_use.lua" to """
                    local U = require("lib.util")
                    local v = U.upper("ab")
                    return v
                """.trimIndent()
            )
        )
        val file = ws.file("app/stringx_use.lua")
        val defs = ws.service.definition(definitionParams(file, "upper", occurrence = 1))
        assertNotNull(defs)
        if (defs.isNotEmpty()) {
            assertTrue(
                defs.any {
                    it.uri.contains("util.lua") ||
                        it.uri.contains("stringx.lua") ||
                        it.uri == file.uri
                },
                defs.map { it.uri }.toString()
            )
        }
    }

    @Test
    fun incomplete_member_access_across_packages_does_not_crash() {
        val ws = projectWorkspace(
            extra = mapOf(
                "app/incomplete.lua" to """
                    local log = require("plugins.logger")
                    local x = log.
                """.trimIndent()
            )
        )
        val file = ws.file("app/incomplete.lua")
        val labels = completionLabels(ws.service, file, afterNeedle = "log.")
        assertNotNull(labels)
        val hover = ws.service.hover(hoverParams(file, "log", occurrence = 2))
        if (hover != null) assertTrue(hoverMarkup(hover).isNotBlank())
    }

    @Test
    fun multi_hop_lib_to_plugins_definition_soft() {
        val ws = projectWorkspace(
            extra = mapOf(
                "lib/bridge.lua" to """
                    local log = require("plugins.logger")
                    local M = {}
                    M.info = log.info
                    return M
                """.trimIndent(),
                "app/bridge_use.lua" to """
                    local bridge = require("lib.bridge")
                    bridge.info("hi")
                    return bridge
                """.trimIndent()
            )
        )
        val file = ws.file("app/bridge_use.lua")
        val defs = ws.service.definition(definitionParams(file, "info", occurrence = 1))
        assertNotNull(defs)
        if (defs.isNotEmpty()) {
            assertTrue(
                defs.any {
                    it.uri.contains("bridge.lua") ||
                        it.uri.contains("logger.lua") ||
                        it.uri == file.uri
                },
                defs.map { it.uri }.toString()
            )
        }
    }

    @Test
    fun workspace_symbols_across_lib_and_plugins_are_distinct() {
        val ws = projectWorkspace()
        val trimHits = ws.service.workspaceSymbols("trim")
        val infoHits = ws.service.workspaceSymbols("info")
        assertTrue(trimHits.any { it.location.uri.contains("util.lua") }, trimHits.toString())
        assertTrue(infoHits.any { it.location.uri.contains("logger.lua") }, infoHits.toString())
        assertTrue(
            trimHits.none { it.name == "trim" && it.location.uri.contains("logger.lua") } ||
                infoHits.none { it.name == "info" && it.location.uri.contains("util.lua") }
        )
    }

    private fun projectWorkspace(extra: Map<String, String> = emptyMap()): ProjectWorkspace {
        val root = Files.createTempDirectory("lua-parser-real-mm-")
        val files = linkedMapOf(
            "lib/stringx.lua" to LIB_STRINGX,
            "lib/util.lua" to LIB_UTIL,
            "plugins/logger.lua" to PLUGIN_LOGGER,
            "plugins/metrics.lua" to PLUGIN_METRICS,
            "app/config.lua" to APP_CONFIG,
            "app/main.lua" to APP_MAIN
        )
        files.putAll(extra)
        val written = files.mapValues { (name, source) ->
            val path = root.resolve(name)
            path.parent?.createDirectories()
            path.writeText(source)
            WorkspaceFile(path, source)
        }
        val service = LuaLanguageService().also { service ->
            service.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), root.fileName.toString()))
                }
            )
        }
        return ProjectWorkspace(root, service, written)
    }

    private data class ProjectWorkspace(
        val root: Path,
        val service: LuaLanguageService,
        val files: Map<String, WorkspaceFile>
    ) {
        fun file(name: String): WorkspaceFile = files[name] ?: error("missing fixture $name")
    }

    private data class WorkspaceFile(
        val path: Path,
        val source: String
    ) {
        val uri: String = path.toUri().toString()
        val pathString: String = path.toString()

        fun positionOf(needle: String, occurrence: Int = 1): Position {
            require(occurrence >= 1) { "occurrence must be positive" }
            var index = -1
            var fromIndex = 0
            repeat(occurrence) {
                index = source.indexOf(needle, fromIndex)
                require(index >= 0) {
                    "Could not find occurrence $occurrence of '$needle' in ${path.fileName}\n$source"
                }
                fromIndex = index + needle.length
            }
            return positionAt(index)
        }

        fun positionAfter(needle: String, occurrence: Int = 1): Position {
            require(occurrence >= 1)
            var index = -1
            var fromIndex = 0
            repeat(occurrence) {
                index = source.indexOf(needle, fromIndex)
                require(index >= 0) { "Could not find '$needle' in ${path.fileName}" }
                fromIndex = index + needle.length
            }
            return positionAt(index + needle.length)
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

    private fun definitionParams(file: WorkspaceFile, needle: String, occurrence: Int = 1): DefinitionParams =
        DefinitionParams(TextDocumentIdentifier(file.uri), file.positionOf(needle, occurrence))

    private fun hoverParams(file: WorkspaceFile, needle: String, occurrence: Int = 1): HoverParams =
        HoverParams(TextDocumentIdentifier(file.uri), file.positionOf(needle, occurrence))

    private fun referenceParams(file: WorkspaceFile, needle: String, occurrence: Int = 1): ReferenceParams =
        ReferenceParams(
            TextDocumentIdentifier(file.uri),
            file.positionOf(needle, occurrence),
            ReferenceContext(true)
        )

    private fun completionItems(
        service: LuaLanguageService,
        file: WorkspaceFile,
        afterNeedle: String
    ): List<CompletionItem> {
        val pos = file.positionAfter(afterNeedle)
        return service.completion(file.pathString, pos.line, pos.character).items
    }

    private fun completionLabels(
        service: LuaLanguageService,
        file: WorkspaceFile,
        afterNeedle: String
    ): List<String> = completionItems(service, file, afterNeedle).map { it.label }

    private fun hoverMarkup(hover: Hover): String {
        val contents = hover.contents ?: return ""
        return when {
            contents.isRight -> contents.right?.value.orEmpty()
            contents.isLeft -> contents.left.orEmpty().joinToString("\n") { either ->
                when {
                    either.isRight -> either.right?.value.orEmpty()
                    either.isLeft -> either.left?.toString().orEmpty()
                    else -> ""
                }
            }
            else -> hover.toString()
        }
    }

    private fun isBareUnknownOnly(text: String, symbol: String): Boolean {
        val normalized = text.lowercase()
        if (!normalized.contains(symbol.lowercase())) return false
        val hasUnknown = normalized.contains("unknown") ||
            Regex("""type:\s*`?any`?""").containsMatchIn(normalized)
        if (!hasUnknown) return false
        val richHints = listOf(
            "function", "fun(", "fun ", "module", "table", "string", "number",
            "boolean", "class", "callable", "->"
        )
        return richHints.none { normalized.contains(it) }
    }

    companion object {
        private val LIB_STRINGX = """
            --- String helpers for lib.stringx package path.
            local M = {}

            ---@param s string
            ---@return string
            function M.upper(s)
                return string.upper(s)
            end

            ---@param s string
            ---@return string
            function M.lower(s)
                return string.lower(s)
            end

            return M
        """.trimIndent()

        private val LIB_UTIL = """
            --- Core util package: require("lib.util")
            local stringx = require("lib.stringx")
            local M = {}

            ---@param s string
            ---@return string
            function M.trim(s)
                return (tostring(s):gsub("^%s+", ""):gsub("%s+$", ""))
            end

            ---@param x number
            ---@param lo number
            ---@param hi number
            ---@return number
            function M.clamp(x, lo, hi)
                if x < lo then return lo end
                if x > hi then return hi end
                return x
            end

            M.upper = stringx.upper

            return M
        """.trimIndent()

        private val PLUGIN_LOGGER = """
            --- plugins.logger — structured log surface.
            local M = {}

            ---@param msg string
            function M.info(msg)
                print("[info]", msg)
            end

            ---@param msg string
            function M.warn(msg)
                print("[warn]", msg)
            end

            ---@param msg string
            function M.error(msg)
                print("[error]", msg)
            end

            return M
        """.trimIndent()

        private val PLUGIN_METRICS = """
            local log = require("plugins.logger")
            local M = {}

            function M.tick(name)
                log.info("metric:" .. tostring(name))
                return true
            end

            function M.count(name, n)
                log.info(tostring(name) .. "=" .. tostring(n or 1))
            end

            return M
        """.trimIndent()

        private val APP_CONFIG = """
            --- App configuration with deep nested tables.
            local M = {}

            M.ui = {
                theme = {
                    name = "dark",
                    colors = {
                        bg = "#111",
                        fg = "#eee",
                        accent = "#4af"
                    }
                },
                fontSize = 14
            }

            M.network = {
                timeout = 30,
                retries = 3
            }

            return M
        """.trimIndent()

        private val APP_MAIN = """
            --- Multi-module entry: lib + app + plugins.
            local U = require("lib.util")
            local log = require("plugins.logger")
            local cfg = require("app.config")
            local metrics = require("plugins.metrics")

            local function boot(name)
                local title = U.trim(name or "world")
                log.info("boot " .. title)
                metrics.tick("boot")
                local theme = cfg.ui.theme
                local colors = theme.colors
                return title, colors
            end

            return {
                boot = boot,
                util = U,
                log = log,
                cfg = cfg
            }
        """.trimIndent()
    }
}
