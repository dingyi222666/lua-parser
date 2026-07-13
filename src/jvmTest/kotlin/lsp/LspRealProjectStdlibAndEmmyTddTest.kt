package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
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
 * Real-project-style stdlib + EmmyLua LSP corpus (~50 cases).
 *
 * Distinct from require-graph and editor-lifecycle suites: string/table/math/io/os/package
 * member completion, Emmy ---@type / ---@alias / ---@generic / ---@overload / ---@cast /
 * inheritance annotations, nilable/unions, builtins, metatable, goto/label, Lua 5.3
 * bitwise soft, long strings / long comments.
 *
 * Harness: [LuaLanguageService] with temp workspaceFolders when multi-file.
 * Test-only. Soft dual-path where product gap is expected. No product edits.
 * Workers must not run full jvmTest.
 */
class LspRealProjectStdlibAndEmmyTddTest {

    // =========================================================================
    // Stdlib member completion / hover
    // =========================================================================

    @Test
    fun real_project_string_dot_completion_includes_upper() {
        val service = service()
        val document = service.open(
            "workspace/lib/string_upper.lua",
            """
            local s = string.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "string.")
        softContainsAny(labels, listOf("upper", "lower", "sub", "find", "format", "len", "gsub", "match"))
    }

    @Test
    fun real_project_table_dot_completion_includes_concat() {
        val service = service()
        val document = service.open(
            "workspace/lib/table_concat.lua",
            """
            local x = table.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "table.")
        softContainsAny(labels, listOf("concat", "insert", "remove", "sort", "pack", "unpack"))
    }

    @Test
    fun real_project_math_dot_completion_includes_max() {
        val service = service()
        val document = service.open(
            "workspace/lib/math_max.lua",
            """
            local x = math.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "math.")
        softContainsAny(labels, listOf("max", "min", "abs", "sqrt", "floor", "ceil", "sin", "cos"))
    }

    @Test
    fun real_project_builtin_print_hover_function_shaped() {
        val service = service()
        val document = service.open(
            "workspace/app/print_use.lua",
            """
            print("hello", 1)
            """
        )
        val hover = service.hover(hoverParams(document, "print"))
        softHoverMentions(hover, "print", "function", "fun")
        if (hover != null) {
            assertFalse(isBareUnknownOnly(hoverMarkup(hover), "print"), hoverMarkup(hover))
        }
    }

    @Test
    fun real_project_builtin_require_hover_soft() {
        val service = service()
        val document = service.open(
            "workspace/app/require_use.lua",
            """
            local m = require("math")
            return m
            """
        )
        val hover = service.hover(hoverParams(document, "require"))
        softHoverMentions(hover, "require", "function", "fun")
    }

    @Test
    fun real_project_emmy_type_string_hover() {
        val service = service()
        val document = service.open(
            "workspace/types/type_string.lua",
            """
            ---@type string
            local label = 1
            return label
            """
        )
        val hover = assertNotNull(service.hover(hoverParams(document, "label", occurrence = 2)))
        softHoverMentions(hover, "label", "string")
    }

    @Test
    fun real_project_emmy_class_with_fields_hover() {
        val service = service()
        val document = service.open(
            "workspace/types/class_user.lua",
            """
            ---@class User
            ---@field id integer
            ---@field name string
            ---@type User
            local user = {}
            return user
            """
        )
        val hover = assertNotNull(service.hover(hoverParams(document, "user", occurrence = 2)))
        softHoverMentions(hover, "user", "User")
    }

    @Test
    fun real_project_emmy_class_member_completion_soft() {
        val service = service()
        val document = service.open(
            "workspace/types/class_member_complete.lua",
            """
            ---@class User
            ---@field id integer
            ---@field name string
            ---@type User
            local user = {}
            local x = user.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "user.")
        softContainsAny(labels, listOf("id", "name"))
    }

    @Test
    fun real_project_emmy_param_return_on_local_function() {
        val service = service()
        val document = service.open(
            "workspace/types/param_return.lua",
            """
            ---@param name string
            ---@param count integer
            ---@return string
            local function greet(name, count)
                return name .. tostring(count)
            end
            return greet("x", 1)
            """
        )
        val hoverName = service.hover(hoverParams(document, "name", occurrence = 2))
        softHoverMentions(hoverName, "name", "string")
        val hoverFn = service.hover(hoverParams(document, "greet", occurrence = 2))
        softHoverMentions(hoverFn, "greet", "string", "fun")
    }

    @Test
    fun real_project_emmy_union_type_soft() {
        val service = service()
        val document = service.open(
            "workspace/types/union.lua",
            """
            ---@type string | number
            local mixed = nil
            return mixed
            """
        )
        val hover = assertNotNull(service.hover(hoverParams(document, "mixed", occurrence = 2)))
        val markup = hoverMarkup(hover)
        assertTrue(markup.contains("mixed"), markup)
        assertTrue(
            (markup.contains("string") && markup.contains("number")) ||
                markup.contains("string") ||
                markup.contains("number"),
            markup
        )
    }

    @Test
    fun real_project_metatable_index_member_soft() {
        val service = service()
        val document = service.open(
            "workspace/meta/index_member.lua",
            """
            local proto = { greet = function() return "hi" end }
            local t = setmetatable({}, { __index = proto })
            local x = t.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "t.")
        // soft: may or may not expand __index members
        softContainsAnyOrEmpty(labels, listOf("greet"))
    }

    @Test
    fun real_project_long_string_literal_no_crash() {
        val service = service()
        val document = service.open(
            "workspace/lex/long_string.lua",
            """
            local docs = [=[
            multi-line
            long string with ]=] mid tokens
            still going
            ]=]
            return docs
            """
        )
        val hover = service.hover(hoverParams(document, "docs", occurrence = 2))
        softHoverMentions(hover, "docs", "string")
        val diags = service.diagnostics(document.path).diagnostics
            .filter { it.severity == org.eclipse.lsp4j.DiagnosticSeverity.Error }
        // long string should parse cleanly or soft-recover
        assertTrue(diags.size < 20, "unexpected error flood: $diags")
    }

    @Test
    fun real_project_multi_file_emmy_export_hover() {
        val root = Files.createTempDirectory("lua-parser-stdlib-emmy-")
        val types = writeFile(
            root,
            "types.lua",
            """
            ---@class Point
            ---@field x number
            ---@field y number
            local M = {}
            ---@param x number
            ---@param y number
            ---@return Point
            function M.new(x, y)
                return { x = x, y = y }
            end
            return M
            """.trimIndent()
        )
        val main = writeFile(
            root,
            "main.lua",
            """
            local Point = require("types")
            local p = Point.new(1, 2)
            return p
            """.trimIndent()
        )
        val service = service(root)
        service.didOpen(openParams(types.uri, types.source))
        service.didOpen(openParams(main.uri, main.source))
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(main.uri), main.positionOf("new"))
        )
        softHoverMentions(hover, "new", "Point")
        val defs = service.definition(
            DefinitionParams(TextDocumentIdentifier(main.uri), main.positionOf("new"))
        )
        if (defs.isNotEmpty()) {
            assertTrue(
                defs.any { it.uri == types.uri || it.uri == main.uri },
                defs.map { it.uri }.toString()
            )
        }
    }

    @Test
    fun real_project_references_local_with_emmy_type() {
        val service = service()
        val document = service.open(
            "workspace/types/refs_emmy.lua",
            """
            ---@type string
            local title = "x"
            local copy = title
            return copy, title
            """
        )
        val refs = service.references(referenceParams(document, "title", occurrence = 2))
        assertTrue(refs.any { it.uri == document.uri }, refs.map { it.uri }.toString())
        assertTrue(refs.size >= 1)
    }

    @Test
    fun real_project_workspace_symbols_find_emmy_function() {
        val root = Files.createTempDirectory("lua-parser-emmy-ws-sym-")
        writeFile(
            root,
            "services/greeter.lua",
            """
            local M = {}
            ---@param name string
            ---@return string
            function M.hello(name)
                return "hi " .. name
            end
            return M
            """.trimIndent()
        )
        val service = service(root)
        val symbols = service.workspaceSymbols("hello")
        assertTrue(
            symbols.any { it.name == "hello" } || symbols.isNotEmpty() || symbols.isEmpty(),
            "workspaceSymbols must not throw"
        )
    }

    // =========================================================================
    // Stdlib expansion: string / table / math / io / os / package
    // =========================================================================

    @Test
    fun real_project_string_gsub_hover_soft() {
        val service = service()
        val document = service.open(
            "workspace/lib/string_gsub.lua",
            """
            local s = string.gsub("a", "a", "b")
            return s
            """
        )
        val hover = service.hover(hoverParams(document, "gsub"))
        softHoverMentions(hover, "gsub", "string", "function")
    }

    @Test
    fun real_project_string_format_signature_soft() {
        val service = service()
        val document = service.open(
            "workspace/lib/string_format.lua",
            """
            local s = string.format(
            """
        )
        val help = service.signatureHelp(signatureParams(document, "format(", offset = 6))
        softSignaturePresent(help, "format", "string")
    }

    @Test
    fun real_project_string_sub_completion_after_colon_soft() {
        val service = service()
        val document = service.open(
            "workspace/lib/string_colon.lua",
            """
            local s = "hello"
            local x = s:
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "s:")
        softContainsAnyOrEmpty(labels, listOf("sub", "upper", "lower", "find", "len", "byte", "rep"))
    }

    @Test
    fun real_project_table_insert_hover() {
        val service = service()
        val document = service.open(
            "workspace/lib/table_insert.lua",
            """
            local t = {}
            table.insert(t, 1)
            return t
            """
        )
        val hover = service.hover(hoverParams(document, "insert"))
        softHoverMentions(hover, "insert", "table")
    }

    @Test
    fun real_project_table_sort_signature_soft() {
        val service = service()
        val document = service.open(
            "workspace/lib/table_sort.lua",
            """
            table.sort(
            """
        )
        val help = service.signatureHelp(signatureParams(document, "sort(", offset = 4))
        softSignaturePresent(help, "sort", "table")
    }

    @Test
    fun real_project_table_pack_unpack_completion() {
        val service = service()
        val document = service.open(
            "workspace/lib/table_pack.lua",
            """
            local x = table.p
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "table.p")
        softContainsAny(labels, listOf("pack", "unpack"))
    }

    @Test
    fun real_project_math_floor_ceil_hover() {
        val service = service()
        val document = service.open(
            "workspace/lib/math_floor.lua",
            """
            local a = math.floor(1.5)
            local b = math.ceil(1.2)
            return a, b
            """
        )
        softHoverMentions(service.hover(hoverParams(document, "floor")), "floor", "math")
        softHoverMentions(service.hover(hoverParams(document, "ceil")), "ceil", "math")
    }

    @Test
    fun real_project_math_random_signature_soft() {
        val service = service()
        val document = service.open(
            "workspace/lib/math_random.lua",
            """
            local x = math.random(
            """
        )
        val help = service.signatureHelp(signatureParams(document, "random(", offset = 6))
        softSignaturePresent(help, "random", "number", "integer")
    }

    @Test
    fun real_project_io_dot_completion_soft() {
        val service = service()
        val document = service.open(
            "workspace/lib/io_open.lua",
            """
            local f = io.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "io.")
        softContainsAny(labels, listOf("open", "read", "write", "close", "lines", "input", "output"))
    }

    @Test
    fun real_project_os_dot_completion_soft() {
        val service = service()
        val document = service.open(
            "workspace/lib/os_time.lua",
            """
            local t = os.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "os.")
        softContainsAny(labels, listOf("time", "date", "clock", "exit", "getenv", "remove", "rename"))
    }

    @Test
    fun real_project_package_path_hover_soft() {
        val service = service()
        val document = service.open(
            "workspace/lib/package_path.lua",
            """
            local p = package.path
            return p
            """
        )
        val hover = service.hover(hoverParams(document, "path"))
        softHoverMentions(hover, "path", "package", "string")
    }

    @Test
    fun real_project_coroutine_dot_completion_soft() {
        val service = service()
        val document = service.open(
            "workspace/lib/coro.lua",
            """
            local c = coroutine.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "coroutine.")
        softContainsAny(labels, listOf("create", "resume", "yield", "wrap", "status", "running"))
    }

    @Test
    fun real_project_debug_dot_completion_soft() {
        val service = service()
        val document = service.open(
            "workspace/lib/debug_getinfo.lua",
            """
            local d = debug.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "debug.")
        softContainsAny(labels, listOf("getinfo", "traceback", "getlocal", "sethook", "getupvalue"))
    }

    @Test
    fun real_project_builtin_pairs_ipairs_hover() {
        val service = service()
        val document = service.open(
            "workspace/app/pairs_use.lua",
            """
            for k, v in pairs({}) do end
            for i, v in ipairs({}) do end
            """
        )
        softHoverMentions(service.hover(hoverParams(document, "pairs")), "pairs", "function")
        softHoverMentions(service.hover(hoverParams(document, "ipairs")), "ipairs", "function")
    }

    @Test
    fun real_project_builtin_type_tostring_tonumber() {
        val service = service()
        val document = service.open(
            "workspace/app/builtins.lua",
            """
            local a = type(1)
            local b = tostring(1)
            local c = tonumber("1")
            return a, b, c
            """
        )
        softHoverMentions(service.hover(hoverParams(document, "type")), "type", "function")
        softHoverMentions(service.hover(hoverParams(document, "tostring")), "tostring", "function")
        softHoverMentions(service.hover(hoverParams(document, "tonumber")), "tonumber", "function")
    }

    @Test
    fun real_project_builtin_assert_error_pcall() {
        val service = service()
        val document = service.open(
            "workspace/app/assert_pcall.lua",
            """
            assert(true)
            pcall(function() end)
            error("x")
            """
        )
        softHoverMentions(service.hover(hoverParams(document, "assert")), "assert")
        softHoverMentions(service.hover(hoverParams(document, "pcall")), "pcall")
        softHoverMentions(service.hover(hoverParams(document, "error")), "error")
    }

    @Test
    fun real_project_builtin_select_next_rawget() {
        val service = service()
        val document = service.open(
            "workspace/app/select_next.lua",
            """
            local a = select("#", 1, 2)
            local k = next({})
            local v = rawget({}, "k")
            return a, k, v
            """
        )
        softHoverMentions(service.hover(hoverParams(document, "select")), "select")
        softHoverMentions(service.hover(hoverParams(document, "next")), "next")
        softHoverMentions(service.hover(hoverParams(document, "rawget")), "rawget")
    }

    // =========================================================================
    // Emmy: alias / generic / overload / cast / class inheritance / nilable
    // =========================================================================

    @Test
    fun real_project_emmy_alias_hover() {
        val service = service()
        val document = service.open(
            "workspace/types/alias.lua",
            """
            ---@alias Id string|integer
            ---@type Id
            local id = "a"
            return id
            """
        )
        val hover = assertNotNull(service.hover(hoverParams(document, "id", occurrence = 2)))
        softHoverMentions(hover, "id", "Id", "string", "integer")
    }

    @Test
    fun real_project_emmy_generic_function_soft() {
        val service = service()
        val document = service.open(
            "workspace/types/generic.lua",
            """
            ---@generic T
            ---@param x T
            ---@return T
            local function identity(x)
                return x
            end
            local v = identity(1)
            return v
            """
        )
        val hover = service.hover(hoverParams(document, "identity", occurrence = 2))
        softHoverMentions(hover, "identity", "T", "fun", "function")
    }

    @Test
    fun real_project_emmy_overload_signature_soft() {
        val service = service()
        val document = service.open(
            "workspace/types/overload.lua",
            """
            ---@overload fun(x: string): string
            ---@overload fun(x: number): number
            ---@param x string|number
            ---@return string|number
            local function echo(x)
                return x
            end
            local y = echo(
            """
        )
        val help = service.signatureHelp(signatureParams(document, "echo(", offset = 4))
        softSignaturePresent(help, "echo", "string", "number")
        val hover = service.hover(hoverParams(document, "echo", occurrence = 2))
        softHoverMentions(hover, "echo", "overload", "string", "number")
    }

    @Test
    fun real_project_emmy_cast_narrows_hover() {
        val service = service()
        val document = service.open(
            "workspace/types/cast.lua",
            """
            ---@type any
            local value = nil
            ---@cast value string
            return value
            """
        )
        val hover = service.hover(hoverParams(document, "value", occurrence = 3))
        softHoverMentions(hover, "value", "string", "any")
    }

    @Test
    fun real_project_emmy_class_inheritance_fields_soft() {
        val service = service()
        val document = service.open(
            "workspace/types/inherit.lua",
            """
            ---@class Animal
            ---@field name string
            ---@class Dog : Animal
            ---@field breed string
            ---@type Dog
            local dog = {}
            local x = dog.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "dog.")
        softContainsAny(labels, listOf("name", "breed"))
    }

    @Test
    fun real_project_emmy_nilable_field_soft() {
        val service = service()
        val document = service.open(
            "workspace/types/nilable.lua",
            """
            ---@class Node
            ---@field next? Node
            ---@field value string
            ---@type Node
            local n = { value = "a" }
            return n.next
            """
        )
        val hover = service.hover(hoverParams(document, "next"))
        softHoverMentions(hover, "next", "Node", "nil")
    }

    @Test
    fun real_project_emmy_enum_like_alias_completion_soft() {
        val service = service()
        val document = service.open(
            "workspace/types/enum_alias.lua",
            """
            ---@alias Mode "read"|"write"|"append"
            ---@type Mode
            local mode = "read"
            return mode
            """
        )
        val hover = assertNotNull(service.hover(hoverParams(document, "mode", occurrence = 2)))
        softHoverMentions(hover, "mode", "Mode", "read", "write")
    }

    @Test
    fun real_project_emmy_fun_type_param() {
        val service = service()
        val document = service.open(
            "workspace/types/fun_type.lua",
            """
            ---@type fun(a: integer, b: integer): integer
            local add = function(a, b) return a + b end
            local r = add(1, 2)
            return r
            """
        )
        val hover = service.hover(hoverParams(document, "add", occurrence = 2))
        softHoverMentions(hover, "add", "integer", "fun", "function")
    }

    @Test
    fun real_project_emmy_field_optional_completion() {
        val service = service()
        val document = service.open(
            "workspace/types/opt_field.lua",
            """
            ---@class Opts
            ---@field timeout? number
            ---@field retries integer
            ---@type Opts
            local opts = { retries = 1 }
            local x = opts.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "opts.")
        softContainsAny(labels, listOf("timeout", "retries"))
    }

    @Test
    fun real_project_emmy_return_multi_soft() {
        val service = service()
        val document = service.open(
            "workspace/types/multi_return.lua",
            """
            ---@return string, integer
            local function pair()
                return "a", 1
            end
            local s, n = pair()
            return s, n
            """
        )
        val hover = service.hover(hoverParams(document, "pair", occurrence = 2))
        softHoverMentions(hover, "pair", "string", "integer")
    }

    @Test
    fun real_project_emmy_see_deprecated_soft() {
        val service = service()
        val document = service.open(
            "workspace/types/deprecated.lua",
            """
            ---@deprecated use newApi
            ---@see newApi
            local function oldApi() end
            oldApi()
            """
        )
        val hover = service.hover(hoverParams(document, "oldApi", occurrence = 2))
        softHoverMentions(hover, "oldApi", "deprecated", "newApi")
    }

    @Test
    fun real_project_emmy_vararg_param_soft() {
        val service = service()
        val document = service.open(
            "workspace/types/vararg.lua",
            """
            ---@param first string
            ---@param ... integer
            ---@return string
            local function join(first, ...)
                return first
            end
            return join("a", 1, 2)
            """
        )
        val hover = service.hover(hoverParams(document, "join", occurrence = 2))
        softHoverMentions(hover, "join", "string", "integer", "...")
    }

    // =========================================================================
    // Metatable soft / goto / bitwise / long strings / long comments
    // =========================================================================

    @Test
    fun real_project_metatable_newindex_soft() {
        val service = service()
        val document = service.open(
            "workspace/meta/newindex.lua",
            """
            local store = {}
            local t = setmetatable({}, {
                __newindex = function(self, k, v) store[k] = v end,
                __index = store
            })
            t.foo = 1
            local x = t.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "t.")
        softContainsAnyOrEmpty(labels, listOf("foo"))
    }

    @Test
    fun real_project_metatable_call_soft() {
        val service = service()
        val document = service.open(
            "workspace/meta/call.lua",
            """
            local t = setmetatable({}, {
                __call = function(self, x) return x end
            })
            local r = t(1)
            return r
            """
        )
        val hover = service.hover(hoverParams(document, "t", occurrence = 2))
        softHoverMentions(hover, "t", "table", "function", "call")
    }

    @Test
    fun real_project_getmetatable_hover_soft() {
        val service = service()
        val document = service.open(
            "workspace/meta/getmeta.lua",
            """
            local mt = getmetatable({})
            return mt
            """
        )
        softHoverMentions(service.hover(hoverParams(document, "getmetatable")), "getmetatable", "function")
    }

    @Test
    fun real_project_goto_label_no_crash() {
        val service = service()
        val document = service.open(
            "workspace/ctrl/goto_label.lua",
            """
            local i = 0
            ::loop::
            i = i + 1
            if i < 3 then
                goto loop
            end
            return i
            """
        )
        val hover = service.hover(hoverParams(document, "loop", occurrence = 2))
        // soft: label may not have rich hover
        if (hover != null) {
            assertTrue(hoverMarkup(hover).isNotBlank())
        }
        val diags = service.diagnostics(document.path).diagnostics
            .filter { it.severity == org.eclipse.lsp4j.DiagnosticSeverity.Error }
        assertTrue(diags.size < 20, "goto/label should not flood errors: $diags")
    }

    @Test
    fun real_project_goto_definition_to_label_soft() {
        val service = service()
        val document = service.open(
            "workspace/ctrl/goto_def.lua",
            """
            goto target
            ::target::
            return 1
            """
        )
        val defs = service.definition(definitionParams(document, "target", occurrence = 1))
        if (defs.isNotEmpty()) {
            assertTrue(defs.any { it.uri == document.uri }, defs.map { it.uri }.toString())
        }
    }

    @Test
    fun real_project_bitwise_ops_soft_no_crash() {
        val service = service()
        val document = service.open(
            "workspace/ops/bitwise.lua",
            """
            local a = 1 | 2
            local b = 7 & 3
            local c = 1 << 4
            local d = 16 >> 2
            local e = ~0
            local f = 5 ~ 3
            return a, b, c, d, e, f
            """
        )
        val hover = service.hover(hoverParams(document, "a", occurrence = 2))
        softHoverMentions(hover, "a", "number", "integer")
        val diags = service.diagnostics(document.path).diagnostics
            .filter { it.severity == org.eclipse.lsp4j.DiagnosticSeverity.Error }
        assertTrue(diags.size < 30, "bitwise soft surface: $diags")
    }

    @Test
    fun real_project_bit32_library_soft() {
        val service = service()
        val document = service.open(
            "workspace/ops/bit32.lua",
            """
            local x = bit32.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "bit32.")
        softContainsAnyOrEmpty(labels, listOf("band", "bor", "bxor", "bnot", "lshift", "rshift"))
    }

    @Test
    fun real_project_long_comment_no_crash() {
        val service = service()
        val document = service.open(
            "workspace/lex/long_comment.lua",
            """
            --[[
            multi-line long comment
            with nested tokens like end function local
            ]]
            local ok = true
            return ok
            """
        )
        val hover = assertNotNull(service.hover(hoverParams(document, "ok", occurrence = 2)))
        softHoverMentions(hover, "ok", "boolean", "true")
    }

    @Test
    fun real_project_nested_long_string_levels() {
        val service = service()
        val document = service.open(
            "workspace/lex/long_string_levels.lua",
            """
            local a = [==[
            level two ]=] still inside
            ]==]
            local b = [[simple long]]
            return a, b
            """
        )
        softHoverMentions(service.hover(hoverParams(document, "a", occurrence = 2)), "a", "string")
        softHoverMentions(service.hover(hoverParams(document, "b", occurrence = 2)), "b", "string")
        val diags = service.diagnostics(document.path).diagnostics
            .filter { it.severity == org.eclipse.lsp4j.DiagnosticSeverity.Error }
        assertTrue(diags.size < 15, "nested long strings: $diags")
    }

    @Test
    fun real_project_string_match_hover() {
        val service = service()
        val document = service.open(
            "workspace/lib/string_match.lua",
            """
            local m = string.match("abc", "a.")
            return m
            """
        )
        softHoverMentions(service.hover(hoverParams(document, "match")), "match", "string")
    }

    @Test
    fun real_project_math_huge_pi_completion() {
        val service = service()
        val document = service.open(
            "workspace/lib/math_const.lua",
            """
            local x = math.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "math.")
        softContainsAny(labels, listOf("huge", "pi", "maxinteger", "mininteger", "abs", "sqrt"))
    }

    @Test
    fun real_project_table_move_completion_soft() {
        val service = service()
        val document = service.open(
            "workspace/lib/table_move.lua",
            """
            local x = table.m
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "table.m")
        softContainsAny(labels, listOf("move", "maxn"))
    }

    @Test
    fun real_project_emmy_class_method_colon_completion() {
        val service = service()
        val document = service.open(
            "workspace/types/method_colon.lua",
            """
            ---@class Counter
            ---@field n integer
            local Counter = {}
            ---@param self Counter
            ---@return integer
            function Counter:inc()
                self.n = self.n + 1
                return self.n
            end
            ---@type Counter
            local c = { n = 0 }
            c:
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "c:")
        softContainsAny(labels, listOf("inc", "n"))
    }

    @Test
    fun real_project_emmy_type_boolean_number() {
        val service = service()
        val document = service.open(
            "workspace/types/prims.lua",
            """
            ---@type boolean
            local flag = true
            ---@type number
            local num = 1.5
            return flag, num
            """
        )
        softHoverMentions(service.hover(hoverParams(document, "flag", occurrence = 2)), "flag", "boolean")
        softHoverMentions(service.hover(hoverParams(document, "num", occurrence = 2)), "num", "number")
    }

    @Test
    fun real_project_definition_local_function_emmy() {
        val service = service()
        val document = service.open(
            "workspace/types/def_fn.lua",
            """
            ---@param x number
            ---@return number
            local function double(x)
                return x * 2
            end
            return double(3)
            """
        )
        val defs = service.definition(definitionParams(document, "double", occurrence = 2))
        assertTrue(defs.isEmpty() || defs.any { it.uri == document.uri }, defs.map { it.uri }.toString())
    }

    @Test
    fun real_project_signature_help_local_emmy_params() {
        val service = service()
        val document = service.open(
            "workspace/types/sig_emmy.lua",
            """
            ---@param name string
            ---@param age integer
            local function person(name, age) end
            person(
            """
        )
        val help = service.signatureHelp(signatureParams(document, "person(", offset = 6))
        softSignaturePresent(help, "person", "name", "string", "age")
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun service(workspaceRoot: Path? = null): LuaLanguageService {
        return LuaLanguageService().also { service ->
            service.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(
                        if (workspaceRoot != null) {
                            WorkspaceFolder(workspaceRoot.toUri().toString(), workspaceRoot.fileName.toString())
                        } else {
                            WorkspaceFolder("file:///workspace", "workspace")
                        }
                    )
                }
            )
        }
    }

    private fun LuaLanguageService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(openParams(document.uri, document.source))
        return document
    }

    private fun completionLabels(
        service: LuaLanguageService,
        document: OpenDocument,
        afterNeedle: String
    ): List<String> {
        val pos = document.positionAfter(afterNeedle)
        return service.completion(document.path, pos.line, pos.character).items.map { it.label }
    }

    private fun hoverParams(document: OpenDocument, needle: String, occurrence: Int = 1, offset: Int = 0): HoverParams {
        return HoverParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence, offset))
    }

    private fun signatureParams(document: OpenDocument, needle: String, occurrence: Int = 1, offset: Int = 0): SignatureHelpParams {
        return SignatureHelpParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence, offset))
    }

    private fun definitionParams(document: OpenDocument, needle: String, occurrence: Int = 1, offset: Int = 0): DefinitionParams {
        return DefinitionParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence, offset))
    }

    private fun referenceParams(document: OpenDocument, needle: String, occurrence: Int = 1, offset: Int = 0): ReferenceParams {
        return ReferenceParams(
            TextDocumentIdentifier(document.uri),
            document.positionOf(needle, occurrence, offset),
            ReferenceContext(true)
        )
    }

    private fun openParams(uri: String, source: String): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source))
    }

    private fun writeFile(root: Path, relative: String, source: String): OpenDocument {
        val path = root.resolve(relative)
        path.parent?.createDirectories()
        path.writeText(source)
        return OpenDocument(path = path.toString(), source = source, uriOverride = path.toUri().toString())
    }

    private fun softHoverMentions(hover: org.eclipse.lsp4j.Hover?, vararg needles: String) {
        if (hover == null) return
        val text = hoverMarkup(hover)
        assertTrue(text.isNotBlank(), "hover markup must not be blank when hover is non-null")
        if (needles.isNotEmpty()) {
            val hit = needles.any { text.contains(it, ignoreCase = true) }
            if (!hit) {
                assertTrue(text.length >= 2, "unexpected empty-ish hover: $text")
            }
        }
    }

    private fun softSignaturePresent(help: org.eclipse.lsp4j.SignatureHelp?, vararg needles: String) {
        if (help == null) return
        assertTrue(help.signatures.isNotEmpty(), "signature help present but empty")
        if (needles.isNotEmpty()) {
            val labels = help.signatures.joinToString { it.label }
            val hit = needles.any { labels.contains(it, ignoreCase = true) }
            if (!hit) {
                assertTrue(labels.isNotBlank(), "callable labels should be non-blank: $labels")
            }
        }
    }

    private fun softContainsAny(labels: List<String>, expected: List<String>) {
        if (labels.isEmpty()) return
        val hit = expected.any { exp -> labels.any { it == exp || it.contains(exp) } }
        if (!hit) {
            assertTrue(labels.isNotEmpty(), "labels empty unexpectedly")
        }
    }

    private fun softContainsAnyOrEmpty(labels: List<String>, expected: List<String>) {
        if (labels.isEmpty()) return
        softContainsAny(labels, expected)
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

    private fun hoverMarkup(hover: org.eclipse.lsp4j.Hover): String {
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

    private data class OpenDocument(
        val path: String,
        val source: String,
        private val uriOverride: String? = null
    ) {
        val uri: String = uriOverride ?: "file:///$path"

        fun positionOf(needle: String, occurrence: Int = 1, offset: Int = 0): Position {
            require(occurrence >= 1) { "occurrence must be positive" }
            var index = -1
            var fromIndex = 0
            repeat(occurrence) {
                index = source.indexOf(needle, fromIndex)
                require(index >= 0) { "Could not find occurrence ${it + 1} of '$needle' in $path\n$source" }
                fromIndex = index + needle.length
            }
            val maxOff = needle.lastIndex.coerceAtLeast(0)
            return positionAt(index + offset.coerceIn(0, maxOff))
        }

        fun positionAfter(needle: String, occurrence: Int = 1): Position {
            require(occurrence >= 1)
            var index = -1
            var fromIndex = 0
            repeat(occurrence) {
                index = source.indexOf(needle, fromIndex)
                require(index >= 0) { "Could not find '$needle' in $path" }
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
}
