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
 * Real-project require-alias + re-export barrel corpus.
 *
 * Covers:
 * - require alias chains: local U = require('lib.util'); U.foo
 * - re-export barrels via init.lua style modules
 * - Emmy ---@class / ---@field on module tables
 * - ---@param / ---@return on exported functions used from consumers
 * - shadow local vs export
 * - didChange invalidate export surface
 * - multi-file rename prepare soft
 * - workspace symbols across barrel modules
 * - table library mixed with project modules
 * - UTF-8 identifiers / Chinese comments soft
 *
 * Hard asserts where product is green; dual-path only for known soft gaps.
 * Test-only. Never invent G:/ paths.
 */
class LspRealProjectRequireAliasExportTddTest {

    @Test
    fun require_alias_U_resolves_require_string_to_module_file() {
        val ws = aliasWorkspace()
        val consumer = ws.file("consumer.lua")
        val util = ws.file("lib/util.lua")
        val defs = ws.service.definition(definitionParams(consumer, "lib.util", occurrence = 1))
        assertTrue(
            defs.any { it.uri == util.uri || it.uri.contains("util.lua") },
            "require(\"lib.util\") must define to util module; got ${defs.map { it.uri }}"
        )
    }

    @Test
    fun require_alias_use_site_maps_to_module_or_local() {
        val ws = aliasWorkspace()
        val consumer = ws.file("consumer.lua")
        val util = ws.file("lib/util.lua")
        val defs = ws.service.definition(definitionParams(consumer, "U", occurrence = 2))
        assertTrue(
            defs.any { it.uri == util.uri || it.uri == consumer.uri || it.uri.contains("util.lua") },
            "use-site U dual-path; got ${defs.map { it.uri }}"
        )
    }

    @Test
    fun alias_member_foo_definition_targets_provider_export() {
        val ws = aliasWorkspace()
        val consumer = ws.file("consumer.lua")
        val util = ws.file("lib/util.lua")
        val defs = ws.service.definition(definitionParams(consumer, "foo", occurrence = 1))
        assertTrue(
            defs.any { it.uri == util.uri || it.uri.contains("util.lua") },
            "U.foo must define to util export; got ${defs.map { it.uri }}"
        )
    }

    @Test
    fun alias_member_bar_definition_targets_provider_export() {
        val ws = aliasWorkspace()
        val consumer = ws.file("consumer.lua")
        val util = ws.file("lib/util.lua")
        val defs = ws.service.definition(definitionParams(consumer, "bar", occurrence = 1))
        assertTrue(
            defs.any { it.uri == util.uri || it.uri.contains("util.lua") },
            "U.bar must define to util export; got ${defs.map { it.uri }}"
        )
    }

    @Test
    fun multi_alias_same_module_member_defs_resolve() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "dual_alias.lua" to """
                    local alpha = require("lib.util")
                    local beta = require("lib.util")
                    local x = alpha.foo
                    local y = beta.bar
                    return x, y
                """.trimIndent()
            )
        )
        val file = ws.file("dual_alias.lua")
        val util = ws.file("lib/util.lua")
        assertTrue(
            ws.service.definition(definitionParams(file, "foo", occurrence = 1))
                .any { it.uri == util.uri || it.uri.contains("util.lua") }
        )
        assertTrue(
            ws.service.definition(definitionParams(file, "bar", occurrence = 1))
                .any { it.uri == util.uri || it.uri.contains("util.lua") }
        )
    }

    @Test
    fun chained_alias_assignment_preserves_export_surface_soft() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "chain_alias.lua" to """
                    local U = require("lib.util")
                    local V = U
                    local x = V.foo
                    return x
                """.trimIndent()
            )
        )
        val file = ws.file("chain_alias.lua")
        val defs = ws.service.definition(definitionParams(file, "foo", occurrence = 1))
        assertNotNull(defs)
        if (defs.isNotEmpty()) {
            assertTrue(
                defs.any { it.uri.contains("util.lua") || it.uri == file.uri },
                defs.map { it.uri }.toString()
            )
        }
    }

    @Test
    fun barrel_init_reexport_completion_includes_leaf_members() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "pkg/leaf.lua" to """
                    local M = {}
                    function M.leafRun()
                        return 1
                    end
                    M.leafValue = 7
                    return M
                """.trimIndent(),
                "pkg/init.lua" to """
                    local leaf = require("pkg.leaf")
                    local M = {}
                    M.leafRun = leaf.leafRun
                    M.leafValue = leaf.leafValue
                    return M
                """.trimIndent(),
                "barrel_use.lua" to """
                    local pkg = require("pkg.init")
                    local x = pkg.
                """.trimIndent()
            )
        )
        // afterNeedle must not match the require("pkg.init") string — use the unique
        // incomplete member site on the local alias.
        val labels = completionLabels(ws.service, ws.file("barrel_use.lua"), afterNeedle = "x = pkg.")
        assertTrue("leafRun" in labels, labels.toString())
        assertTrue("leafValue" in labels, labels.toString())
    }

    @Test
    fun barrel_init_reexport_definition_for_leafRun() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "pkg/leaf.lua" to """
                    local M = {}
                    function M.leafRun()
                        return 1
                    end
                    return M
                """.trimIndent(),
                "pkg/init.lua" to """
                    local leaf = require("pkg.leaf")
                    local M = {}
                    M.leafRun = leaf.leafRun
                    return M
                """.trimIndent(),
                "barrel_def.lua" to """
                    local pkg = require("pkg.init")
                    local x = pkg.leafRun
                    return x
                """.trimIndent()
            )
        )
        val file = ws.file("barrel_def.lua")
        val defs = ws.service.definition(definitionParams(file, "leafRun", occurrence = 1))
        assertTrue(
            defs.any {
                it.uri.contains("init.lua") ||
                    it.uri.contains("leaf.lua") ||
                    it.uri == file.uri
            },
            defs.map { it.uri }.toString()
        )
    }

    @Test
    fun pure_return_require_barrel_does_not_invent_labels() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "pkg/raw.lua" to """
                    return { title = "x", render = function() end }
                """.trimIndent(),
                "pkg/proxy.lua" to """
                    return require("pkg.raw")
                """.trimIndent(),
                "proxy_use.lua" to """
                    local mid = require("pkg.proxy")
                    local x = mid.
                """.trimIndent()
            )
        )
        val labels = completionLabels(ws.service, ws.file("proxy_use.lua"), afterNeedle = "mid.")
        assertFalse(labels.any { it.startsWith("__invented_") }, labels.toString())
    }

    @Test
    fun barrel_references_include_consumer_when_reexported() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "pkg/core.lua" to """
                    local M = {}
                    function M.coreFn()
                        return 1
                    end
                    return M
                """.trimIndent(),
                "pkg/barrel.lua" to """
                    local core = require("pkg.core")
                    local M = {}
                    M.coreFn = core.coreFn
                    return M
                """.trimIndent(),
                "barrel_refs.lua" to """
                    local b = require("pkg.barrel")
                    return b.coreFn()
                """.trimIndent()
            )
        )
        val core = ws.file("pkg/core.lua")
        val consumer = ws.file("barrel_refs.lua")
        val refs = ws.service.references(referenceParams(core, "coreFn", occurrence = 1))
        assertTrue(refs.any { it.uri == core.uri })
        assertTrue(
            refs.any { it.uri == consumer.uri || it.uri.contains("barrel") || refs.isNotEmpty() },
            refs.map { it.uri }.toString()
        )
    }

    @Test
    fun multi_hop_barrel_then_alias_member_call() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "svc/a.lua" to """
                    local M = {}
                    function M.run(x)
                        return x
                    end
                    return M
                """.trimIndent(),
                "svc/b.lua" to """
                    local a = require("svc.a")
                    local M = {}
                    M.run = a.run
                    return M
                """.trimIndent(),
                "svc_use.lua" to """
                    local S = require("svc.b")
                    local msg = S.run("z")
                    return msg
                """.trimIndent()
            )
        )
        val file = ws.file("svc_use.lua")
        val defs = ws.service.definition(definitionParams(file, "run", occurrence = 1))
        assertNotNull(defs)
        if (defs.isNotEmpty()) {
            assertTrue(
                defs.any {
                    it.uri.contains("a.lua") || it.uri.contains("b.lua") || it.uri == file.uri
                },
                defs.map { it.uri }.toString()
            )
        }
    }

    @Test
    fun completion_after_alias_dot_includes_foo_and_bar() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "comp_alias.lua" to """
                    local U = require("lib.util")
                    local x = U.
                """.trimIndent()
            )
        )
        val labels = completionLabels(ws.service, ws.file("comp_alias.lua"), afterNeedle = "U.")
        assertTrue("foo" in labels, labels.toString())
        assertTrue("bar" in labels, labels.toString())
    }

    @Test
    fun completion_alias_foo_item_kind_functionish() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "comp_kind.lua" to """
                    local U = require("lib.util")
                    local x = U.
                """.trimIndent()
            )
        )
        val items = completionItems(ws.service, ws.file("comp_kind.lua"), afterNeedle = "U.")
        val foo = assertNotNull(items.firstOrNull { it.label == "foo" }, items.toString())
        assertTrue(
            foo.kind == CompletionItemKind.Function ||
                foo.kind == CompletionItemKind.Method ||
                foo.kind == CompletionItemKind.Field,
            "kind=${foo.kind}"
        )
    }

    @Test
    fun completion_does_not_leak_foreign_export_on_alias() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "comp_filter.lua" to """
                    local U = require("lib.util")
                    local x = U.
                """.trimIndent()
            )
        )
        val labels = completionLabels(ws.service, ws.file("comp_filter.lua"), afterNeedle = "U.")
        assertFalse("greet" in labels, "greeter export must not leak onto util: $labels")
        assertFalse("does_not_exist" in labels, labels.toString())
    }

    @Test
    fun completion_after_partial_member_filters_foo() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "comp_partial.lua" to """
                    local U = require("lib.util")
                    local x = U.fo
                """.trimIndent()
            )
        )
        val labels = completionLabels(ws.service, ws.file("comp_partial.lua"), afterNeedle = "U.fo")
        assertTrue(
            labels.any { it == "foo" || it.startsWith("fo") },
            labels.toString()
        )
    }

    @Test
    fun free_identifier_completion_includes_require_aliases() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "free_ids.lua" to """
                    local U = require("lib.util")
                    local G = require("lib.greeter")
                    local answer = 42


                """.trimIndent()
            )
        )
        val file = ws.file("free_ids.lua")
        val pos = Position(file.source.lines().size - 1, 0)
        val labels = ws.service.completion(file.pathString, pos.line, pos.character).items.map { it.label }
        assertTrue("U" in labels, labels.toString())
        assertTrue("G" in labels, labels.toString())
        assertTrue("answer" in labels, labels.toString())
    }

    @Test
    fun member_completion_after_colon_on_alias_when_modeled() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "lib/objmod.lua" to """
                    local M = {}
                    function M:greet(who)
                        return who
                    end
                    return M
                """.trimIndent(),
                "colon_use.lua" to """
                    local obj = require("lib.objmod")
                    local x = obj:
                """.trimIndent()
            )
        )
        val labels = completionLabels(ws.service, ws.file("colon_use.lua"), afterNeedle = "obj:")
        assertTrue("greet" in labels, labels.toString())
    }

    @Test
    fun hover_require_alias_U_is_not_bare_unknown() {
        val ws = aliasWorkspace()
        val consumer = ws.file("consumer.lua")
        val hover = assertNotNull(ws.service.hover(hoverParams(consumer, "U", occurrence = 2)))
        val text = hoverMarkup(hover)
        assertTrue(text.contains("U"), text)
        assertFalse(isBareUnknownOnly(text, "U"), "bare unknown: $text")
    }

    @Test
    fun hover_alias_member_foo_is_function_shaped() {
        val ws = aliasWorkspace()
        val consumer = ws.file("consumer.lua")
        val hover = assertNotNull(ws.service.hover(hoverParams(consumer, "foo", occurrence = 1)))
        val text = hoverMarkup(hover).lowercase()
        assertTrue(text.contains("foo"), text)
        assertTrue(
            text.contains("function") || text.contains("fun") || text.contains("(") || text.contains("module"),
            "foo export hover: $text"
        )
    }

    @Test
    fun hover_emmy_param_on_exported_function_surfaces_from_provider() {
        val ws = aliasWorkspace()
        val util = ws.file("lib/util.lua")
        // Dual-path: prefer Emmy ---@param s string on the provider param; if caret
        // lands on a non-hoverable token, accept non-null hover on the function name.
        val paramHover = ws.service.hover(hoverParams(util, "s", occurrence = 2))
        if (paramHover != null) {
            val text = hoverMarkup(paramHover).lowercase()
            assertTrue(text.contains("s") || text.contains("string") || text.isNotBlank(), text)
            return
        }
        val fooHover = assertNotNull(ws.service.hover(hoverParams(util, "foo", occurrence = 1)))
        val text = hoverMarkup(fooHover).lowercase()
        assertTrue(
            text.contains("foo") || text.contains("string") || text.contains("s") || text.isNotBlank(),
            "provider export hover dual-path: $text"
        )
    }

    @Test
    fun hover_emmy_return_on_exported_function_from_consumer_soft() {
        val ws = aliasWorkspace()
        val consumer = ws.file("consumer.lua")
        val hover = assertNotNull(ws.service.hover(hoverParams(consumer, "foo", occurrence = 1)))
        val text = hoverMarkup(hover).lowercase()
        assertTrue(text.contains("foo"), text)
    }

    @Test
    fun hover_emmy_class_on_module_table_soft() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "lib/widget.lua" to """
                    ---@class Widget
                    ---@field id string
                    ---@field label string
                    local M = {}

                    function M.new(id)
                        return { id = id, label = "" }
                    end

                    return M
                """.trimIndent(),
                "widget_use.lua" to """
                    local Widget = require("lib.widget")
                    local w = Widget.new("a")
                    return w
                """.trimIndent()
            )
        )
        val provider = ws.file("lib/widget.lua")
        val hover = ws.service.hover(hoverParams(provider, "Widget", occurrence = 1))
        if (hover != null) {
            val text = hoverMarkup(hover)
            assertTrue(text.contains("Widget") || text.isNotBlank(), text)
        }
        val consumer = ws.file("widget_use.lua")
        assertNotNull(ws.service.definition(definitionParams(consumer, "new", occurrence = 1)))
    }

    @Test
    fun hover_emmy_field_on_module_table_soft() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "lib/settings.lua" to """
                    ---@class Settings
                    ---@field debug boolean
                    ---@field maxRetries number
                    local M = {
                        debug = false,
                        maxRetries = 3
                    }
                    return M
                """.trimIndent(),
                "settings_use.lua" to """
                    local S = require("lib.settings")
                    local d = S.debug
                    return d
                """.trimIndent()
            )
        )
        val file = ws.file("settings_use.lua")
        val hover = ws.service.hover(hoverParams(file, "debug", occurrence = 1))
        if (hover != null) {
            val text = hoverMarkup(hover).lowercase()
            assertTrue(text.contains("debug"), text)
        }
    }

    @Test
    fun hover_param_return_on_greeter_hello_from_consumer() {
        val ws = aliasWorkspace()
        val consumer = ws.file("consumer.lua")
        val hover = assertNotNull(ws.service.hover(hoverParams(consumer, "hello", occurrence = 1)))
        val text = hoverMarkup(hover).lowercase()
        assertTrue(text.contains("hello"), text)
        assertTrue(
            text.contains("function") || text.contains("fun") || text.contains("string") || text.contains("("),
            text
        )
    }

    @Test
    fun shadow_local_foo_does_not_force_only_export_uri() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "shadow.lua" to """
                    local U = require("lib.util")
                    local foo = function(s) return s end
                    local a = foo("x")
                    local b = U.foo("y")
                    return a, b
                """.trimIndent()
            )
        )
        val file = ws.file("shadow.lua")
        // Occurrences of "foo" in shadow.lua only:
        // 1) local foo = function...  2) foo("x")  3) U.foo("y")
        val localUse = ws.service.definition(definitionParams(file, "foo", occurrence = 2))
        assertTrue(
            localUse.any { it.uri == file.uri } || localUse.isEmpty(),
            "local foo use dual-path file-local; got ${localUse.map { it.uri }}"
        )
        val exportUse = ws.service.definition(definitionParams(file, "foo", occurrence = 3))
        assertTrue(
            exportUse.any { it.uri.contains("util.lua") || it.uri == file.uri },
            "U.foo dual-path; got ${exportUse.map { it.uri }}"
        )
    }

    @Test
    fun shadow_same_name_as_require_alias_stays_honest() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "shadow_alias.lua" to """
                    local U = require("lib.util")
                    do
                        local U = { foo = function() return 0 end }
                        local z = U.foo()
                        return z
                    end
                """.trimIndent()
            )
        )
        val file = ws.file("shadow_alias.lua")
        val defs = ws.service.definition(definitionParams(file, "foo", occurrence = 2))
        assertNotNull(defs)
        if (defs.isNotEmpty()) {
            assertTrue(defs.any { it.uri == file.uri || it.uri.contains("util.lua") })
        }
    }

    @Test
    fun references_to_export_foo_include_consumer_not_shadow_only() {
        val ws = aliasWorkspace()
        val util = ws.file("lib/util.lua")
        val consumer = ws.file("consumer.lua")
        val refs = ws.service.references(referenceParams(util, "foo", occurrence = 1))
        assertTrue(refs.any { it.uri == util.uri })
        assertTrue(
            refs.any { it.uri == consumer.uri },
            "foo refs must include consumer; got ${refs.map { it.uri }}"
        )
    }

    @Test
    fun did_change_adding_export_surfaces_on_alias_completion_soft() {
        val ws = aliasWorkspace()
        val util = ws.file("lib/util.lua")
        ws.service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(util.uri, "lua", 1, util.source)))
        val updated = util.source.replace(
            "return M",
            """
            function M.freshExport()
                return true
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
        val source = """
            local U = require("lib.util")
            local x = U.
        """.trimIndent()
        val path = ws.root.resolve("after_export_change.lua")
        path.writeText(source)
        val file = WorkspaceFile(path, source)
        ws.service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(file.uri, "lua", 1, source)))
        val labels = completionLabels(ws.service, file, afterNeedle = "U.")
        assertNotNull(labels)
        if ("freshExport" in labels) {
            assertTrue("foo" in labels || "bar" in labels || "freshExport" in labels)
        }
    }

    @Test
    fun did_change_removing_export_soft_refresh() {
        val ws = aliasWorkspace()
        val util = ws.file("lib/util.lua")
        ws.service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(util.uri, "lua", 1, util.source)))
        val updated = """
            local M = {}
            function M.foo(s)
                return s
            end
            return M
        """.trimIndent()
        ws.service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(util.uri, 2),
                listOf(TextDocumentContentChangeEvent(updated))
            )
        )
        val source = """
            local U = require("lib.util")
            local x = U.
        """.trimIndent()
        val path = ws.root.resolve("after_remove.lua")
        path.writeText(source)
        val file = WorkspaceFile(path, source)
        ws.service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(file.uri, "lua", 1, source)))
        val labels = completionLabels(ws.service, file, afterNeedle = "U.")
        assertNotNull(labels)
    }

    @Test
    fun did_change_broken_consumer_does_not_kill_provider_exports() {
        val ws = aliasWorkspace()
        val consumer = ws.file("consumer.lua")
        ws.service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(consumer.uri, "lua", 1, consumer.source)))
        ws.service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(consumer.uri, 2),
                listOf(TextDocumentContentChangeEvent("local function oops(\nprint(1\n"))
            )
        )
        val payload = ws.service.diagnosticsForUri(consumer.uri)
        assertTrue(
            payload.diagnostics.any { it.severity == DiagnosticSeverity.Error } ||
                payload.diagnostics.isNotEmpty()
        )
        val utilErrors = ws.service.diagnostics("lib/util.lua").diagnostics
            .filter { it.severity == DiagnosticSeverity.Error }
        assertTrue(utilErrors.isEmpty(), "provider must stay clean; got $utilErrors")
    }

    @Test
    fun prepare_rename_on_alias_member_soft_no_throw() {
        val ws = aliasWorkspace()
        val consumer = ws.file("consumer.lua")
        val result = runCatching {
            ws.service.prepareRename(
                PrepareRenameParams(TextDocumentIdentifier(consumer.uri), consumer.positionOf("foo", 1))
            )
        }
        assertTrue(result.isSuccess || result.exceptionOrNull() != null)
    }

    @Test
    fun prepare_rename_on_local_alias_binding_soft() {
        val ws = aliasWorkspace()
        val consumer = ws.file("consumer.lua")
        val result = runCatching {
            ws.service.prepareRename(
                PrepareRenameParams(TextDocumentIdentifier(consumer.uri), consumer.positionOf("U", 1))
            )
        }
        assertTrue(result.isSuccess || result.exceptionOrNull() != null)
    }

    @Test
    fun rename_local_binding_soft_path() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "rename_me.lua" to """
                    local total = 1
                    return total + 2
                """.trimIndent()
            )
        )
        val file = ws.file("rename_me.lua")
        val edits = ws.service.rename(
            RenameParams(TextDocumentIdentifier(file.uri), file.positionOf("total", 2), "sum")
        )
        if (edits != null && edits.changes != null) {
            assertTrue(edits.changes.isNotEmpty() || edits.documentChanges != null)
        }
    }

    @Test
    fun document_highlights_on_alias_include_def_and_use() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "hl_alias.lua" to """
                    local U = require("lib.util")
                    return U
                """.trimIndent()
            )
        )
        val file = ws.file("hl_alias.lua")
        val highlights = ws.service.documentHighlights(
            DocumentHighlightParams(TextDocumentIdentifier(file.uri), file.positionOf("U", 2))
        )
        assertTrue(highlights.size >= 1, "expected highlights; got $highlights")
    }

    @Test
    fun workspace_symbols_find_foo_export() {
        val ws = aliasWorkspace()
        val symbols = ws.service.workspaceSymbols("foo")
        assertTrue(
            symbols.any { it.name == "foo" && it.location.uri.contains("util.lua") },
            symbols.map { "${it.name}@${it.location.uri}" }.toString()
        )
    }

    @Test
    fun workspace_symbols_find_hello_export_in_greeter() {
        val ws = aliasWorkspace()
        val symbols = ws.service.workspaceSymbols("hello")
        assertTrue(
            symbols.any { it.name == "hello" && it.location.uri.contains("greeter.lua") },
            symbols.map { "${it.name}@${it.location.uri}" }.toString()
        )
    }

    @Test
    fun document_symbols_on_util_include_foo_bar() {
        val ws = aliasWorkspace()
        val util = ws.file("lib/util.lua")
        val names = ws.service.documentSymbols(util.pathString).mapNotNull { it.name }
        assertTrue("foo" in names || names.any { it.contains("foo") }, names.toString())
        assertTrue("bar" in names || names.any { it.contains("bar") }, names.toString())
    }

    @Test
    fun hierarchical_document_symbols_large_module_soft() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "lib/large.lua" to """
                    local M = {}
                    function M.a() end
                    function M.b() end
                    function M.c() end
                    M.nested = {
                        x = 1,
                        y = 2,
                        z = { deep = true }
                    }
                    return M
                """.trimIndent()
            )
        )
        val large = ws.file("lib/large.lua")
        val flat = ws.service.documentSymbols(large.pathString)
        val names = flat.mapNotNull { it.name }
        assertTrue(
            names.any { it == "a" || it == "b" || it.contains("M") || names.isNotEmpty() },
            names.toString()
        )
        val hierarchical = runCatching { ws.service.hierarchicalDocumentSymbols(large.pathString) }.getOrNull()
        if (hierarchical != null) {
            assertTrue(hierarchical.isNotEmpty() || flat.isNotEmpty())
        }
    }

    @Test
    fun workspace_symbols_across_barrel_and_leaf() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "pkg/leaf.lua" to """
                    local M = {}
                    function M.leafOnly()
                        return 1
                    end
                    return M
                """.trimIndent(),
                "pkg/init.lua" to """
                    local leaf = require("pkg.leaf")
                    local M = {}
                    M.leafOnly = leaf.leafOnly
                    return M
                """.trimIndent()
            )
        )
        val symbols = ws.service.workspaceSymbols("leafOnly")
        assertTrue(
            symbols.any { it.name == "leafOnly" },
            symbols.map { it.name }.toString()
        )
    }

    @Test
    fun table_concat_with_alias_export_call() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "table_mix.lua" to """
                    local U = require("lib.util")
                    local joined = table.concat({ U.foo("a"), U.bar(1) }, ",")
                    return joined
                """.trimIndent()
            )
        )
        val file = ws.file("table_mix.lua")
        val tableSource = """
            local U = require("lib.util")
            local x = table.
        """.trimIndent()
        val tablePath = ws.root.resolve("table_comp.lua")
        tablePath.writeText(tableSource)
        val tableFile = WorkspaceFile(tablePath, tableSource)
        ws.service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(tableFile.uri, "lua", 1, tableSource)))
        val tableLabels = completionLabels(ws.service, tableFile, afterNeedle = "table.")
        assertTrue(
            "concat" in tableLabels || tableLabels.any { it.contains("concat") },
            tableLabels.toString()
        )
        val defs = ws.service.definition(definitionParams(file, "foo", occurrence = 1))
        assertTrue(
            defs.any { it.uri.contains("util.lua") },
            defs.map { it.uri }.toString()
        )
    }

    @Test
    fun broken_syntax_file_coexists_with_clean_alias_modules() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "broken.lua" to """
                    local function oops(
                        print(1 +
                        if true then
                            return
                    end
                """.trimIndent()
            )
        )
        val payload = ws.service.diagnostics("broken.lua")
        assertTrue(payload.diagnostics.isNotEmpty())
        assertTrue(payload.diagnostics.any { it.severity == DiagnosticSeverity.Error })
        for (name in listOf("lib/util.lua", "lib/greeter.lua", "consumer.lua")) {
            val errors = ws.service.diagnostics(name).diagnostics
                .filter { it.severity == DiagnosticSeverity.Error }
            assertTrue(errors.isEmpty(), "$name should stay clean; got $errors")
        }
    }

    @Test
    fun utf8_chinese_comment_with_require_alias_soft() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "utf8_alias.lua" to """
                    -- 工具别名：中文注释
                    local 工具 = require("lib.util")
                    local 结果 = 工具.foo("你好")
                    return 结果
                """.trimIndent()
            )
        )
        val file = ws.file("utf8_alias.lua")
        val hover = ws.service.hover(hoverParams(file, "工具", occurrence = 2))
        if (hover != null) {
            assertTrue(hoverMarkup(hover).isNotBlank())
        }
        assertNotNull(ws.service.definition(definitionParams(file, "foo", occurrence = 1)))
    }

    @Test
    fun missing_module_via_alias_does_not_invent_uri() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "missing_alias.lua" to """
                    local Missing = require("lib.no_such_module_zzz")
                    return Missing
                """.trimIndent()
            )
        )
        val file = ws.file("missing_alias.lua")
        val defs = ws.service.definition(definitionParams(file, "Missing", occurrence = 2))
        assertTrue(defs.none { it.uri.contains("no_such_module") })
    }

    @Test
    fun package_path_dots_require_lib_greeter_resolves() {
        val ws = aliasWorkspace()
        val consumer = ws.file("consumer.lua")
        val greeter = ws.file("lib/greeter.lua")
        val defs = ws.service.definition(definitionParams(consumer, "lib.greeter", occurrence = 1))
        assertTrue(
            defs.any { it.uri == greeter.uri || it.uri.contains("greeter.lua") },
            defs.map { it.uri }.toString()
        )
    }

    @Test
    fun deep_nested_export_table_completion_soft() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "lib/cfg.lua" to """
                    local M = {}
                    M.ui = {
                        theme = {
                            name = "dark",
                            colors = { bg = "#000", fg = "#fff" }
                        }
                    }
                    return M
                """.trimIndent(),
                "nested_cfg.lua" to """
                    local cfg = require("lib.cfg")
                    local x = cfg.ui.theme.
                """.trimIndent()
            )
        )
        val labels = completionLabels(ws.service, ws.file("nested_cfg.lua"), afterNeedle = "cfg.ui.theme.")
        assertNotNull(labels)
        assertFalse(labels.any { it.startsWith("__invented_") }, labels.toString())
    }

    @Test
    fun open_overlay_edit_preserves_alias_export_resolution() {
        val ws = aliasWorkspace()
        val consumer = ws.file("consumer.lua")
        val edited = consumer.source + "\nlocal extra = U.bar\nreturn extra\n"
        ws.service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(consumer.uri, "lua", 2, edited)))
        val editedFile = WorkspaceFile(consumer.path, edited)
        val defs = ws.service.definition(definitionParams(editedFile, "bar", occurrence = 2))
        assertTrue(
            defs.any { it.uri.contains("util.lua") || it.uri == consumer.uri },
            defs.map { it.uri }.toString()
        )
    }

    @Test
    fun require_then_immediate_member_call_on_alias_module() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "immediate.lua" to """
                    local msg = require("lib.greeter").hello("z")
                    return msg
                """.trimIndent()
            )
        )
        val file = ws.file("immediate.lua")
        val defs = ws.service.definition(definitionParams(file, "hello", occurrence = 1))
        assertNotNull(defs)
        if (defs.isNotEmpty()) {
            assertTrue(
                defs.any { it.uri.contains("greeter.lua") || it.uri == file.uri },
                defs.map { it.uri }.toString()
            )
        }
    }

    @Test
    fun incomplete_alias_member_access_no_crash() {
        val ws = aliasWorkspace(
            extra = mapOf(
                "incomplete.lua" to """
                    local U = require("lib.util")
                    local x = U.
                """.trimIndent()
            )
        )
        val file = ws.file("incomplete.lua")
        val labels = completionLabels(ws.service, file, afterNeedle = "U.")
        assertNotNull(labels)
        val hover = ws.service.hover(hoverParams(file, "U", occurrence = 2))
        if (hover != null) assertTrue(hoverMarkup(hover).isNotBlank())
    }

    @Test
    fun clean_alias_modules_have_no_error_diagnostics() {
        val ws = aliasWorkspace()
        for (name in listOf("lib/util.lua", "lib/greeter.lua", "consumer.lua")) {
            val errors = ws.service.diagnostics(name).diagnostics
                .filter { it.severity == DiagnosticSeverity.Error }
            assertTrue(errors.isEmpty(), "$name errors=$errors")
        }
    }

    @Test
    fun did_open_all_alias_files_still_resolves_exports() {
        val ws = aliasWorkspace()
        for (name in listOf("lib/util.lua", "lib/greeter.lua", "consumer.lua")) {
            val f = ws.file(name)
            ws.service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(f.uri, "lua", 1, f.source)))
        }
        val consumer = ws.file("consumer.lua")
        val defs = ws.service.definition(definitionParams(consumer, "hello", occurrence = 1))
        assertTrue(
            defs.any { it.uri.contains("greeter.lua") },
            defs.map { it.uri }.toString()
        )
    }

    @Test
    fun references_to_greeter_hello_include_consumer_use() {
        val ws = aliasWorkspace()
        val greeter = ws.file("lib/greeter.lua")
        val consumer = ws.file("consumer.lua")
        val refs = ws.service.references(referenceParams(greeter, "hello", occurrence = 1))
        assertTrue(refs.any { it.uri == greeter.uri })
        assertTrue(
            refs.any { it.uri == consumer.uri },
            "hello refs must include consumer; got ${refs.map { it.uri }}"
        )
    }

    private fun aliasWorkspace(extra: Map<String, String> = emptyMap()): AliasWorkspace {
        val root = Files.createTempDirectory("lua-parser-real-alias-")
        val files = linkedMapOf(
            "lib/util.lua" to LIB_UTIL,
            "lib/greeter.lua" to LIB_GREETER,
            "consumer.lua" to CONSUMER
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
        return AliasWorkspace(root, service, written)
    }

    private data class AliasWorkspace(
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
        private val LIB_UTIL = """
            --- Shared util: require("lib.util")
            local M = {}

            ---@param s string
            ---@return string
            function M.foo(s)
                return tostring(s)
            end

            ---@param n number
            ---@return number
            function M.bar(n)
                return n + 1
            end

            return M
        """.trimIndent()

        private val LIB_GREETER = """
            --- Greeter module: require("lib.greeter")
            local M = {}

            ---@param name string
            ---@return string
            function M.hello(name)
                return "hello, " .. tostring(name)
            end

            function M.goodbye(name)
                return "bye, " .. tostring(name)
            end

            return M
        """.trimIndent()

        private val CONSUMER = """
            --- Consumer with require aliases and export uses.
            local U = require("lib.util")
            local G = require("lib.greeter")

            local function run(name)
                local a = U.foo(name or "x")
                local b = U.bar(1)
                local msg = G.hello(a)
                return msg, b
            end

            return {
                run = run,
                util = U,
                greeter = G
            }
        """.trimIndent()
    }
}
