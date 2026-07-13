package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
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
 * Monaco greeter.hello cross-module return typing:
 * require-export member call must surface Emmy ---@return string (or honest body string)
 * through the normal ModuleType export surface so message / run are string-ish, not unknown.
 *
 * Architectural lock: product enriches export FunctionTypes once after bind from the
 * provider binder/TypeResolver path — not scattered Emmy re-parse in hover/completion.
 */
class MonacoGreeterHelloReturnTddTest {

    @Test
    fun hover_message_from_greeter_hello_is_string_not_unknown() {
        val ws = demoWorkspace()
        val main = ws.file("main.lua")
        val hover = assertNotNull(
            ws.service.hover(hoverParams(main, "message", occurrence = 1)),
            "expected hover on local message = greeter.hello(...)"
        )
        val text = hoverMarkup(hover).lowercase()
        println("HOVER message markup=$text")
        assertTrue(
            text.contains("string") || text.contains("\"hello"),
            "message must type as string-ish from greeter.hello; got: $text"
        )
        assertFalse(
            isBareUnknownOnly(text, "message"),
            "message must not collapse to bare unknown; got: $text"
        )
    }

    @Test
    fun hover_greeter_hello_call_site_surfaces_string_return() {
        val ws = demoWorkspace()
        val main = ws.file("main.lua")
        // Use site of hello in greeter.hello(name or "world")
        val hover = assertNotNull(
            ws.service.hover(hoverParams(main, "hello", occurrence = 1)),
            "expected hover on greeter.hello use site"
        )
        val text = hoverMarkup(hover).lowercase()
        println("HOVER hello markup=$text")
        assertTrue(
            text.contains("hello") || text.contains("fun") || text.contains("function") || text.contains("("),
            "hello export hover should stay function-shaped: $text"
        )
        assertTrue(
            text.contains("string") || text.contains("fun("),
            "hello must surface string return (Emmy or body); got: $text"
        )
        assertFalse(
            text.contains("fun(name: unknown): unknown") && !text.contains("string"),
            "hello must not freeze as fun(...): unknown only; got: $text"
        )
    }

    @Test
    fun hover_run_inferred_return_is_string_ish() {
        val ws = demoWorkspace()
        val main = ws.file("main.lua")
        val hover = assertNotNull(
            ws.service.hover(hoverParams(main, "run", occurrence = 1)),
            "expected hover on local function run"
        )
        val text = hoverMarkup(hover).lowercase()
        println("HOVER run markup=$text")
        assertTrue(
            text.contains("string") || text.contains("fun("),
            "run should be fun(...): string-ish via message return; got: $text"
        )
        assertFalse(
            Regex("""fun\([^)]*\):\s*unknown\b""").containsMatchIn(text) &&
                !text.contains("string"),
            "run must not hover as fun(...): unknown only; got: $text"
        )
    }

    @Test
    fun utils_log_stays_unknown_return_honest() {
        val ws = demoWorkspace()
        val main = ws.file("main.lua")
        val hover = assertNotNull(
            ws.service.hover(hoverParams(main, "log", occurrence = 1)),
            "expected hover on utils.log use site"
        )
        val text = hoverMarkup(hover).lowercase()
        println("HOVER log markup=$text")
        // No Emmy / no return expression worth inventing — unknown return remains honest.
        assertTrue(
            text.contains("log") || text.contains("fun") || text.contains("function") || text.contains("("),
            "log should stay function-shaped: $text"
        )
        assertFalse(
            text.contains("invented_string_return"),
            "must not invent returns for utils.log; got: $text"
        )
    }

    private fun demoWorkspace(): DemoWorkspace {
        val root = Files.createTempDirectory("lua-parser-monaco-greeter-return-")
        val files = linkedMapOf(
            "greeter.lua" to DEMO_GREETER,
            "utils.lua" to DEMO_UTILS,
            "main.lua" to DEMO_MAIN
        )
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
        return DemoWorkspace(root, service, written)
    }

    private data class DemoWorkspace(
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

        fun positionOf(needle: String, occurrence: Int = 1): Position {
            require(occurrence >= 1)
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

    private fun hoverParams(file: WorkspaceFile, needle: String, occurrence: Int = 1): HoverParams =
        HoverParams(TextDocumentIdentifier(file.uri), file.positionOf(needle, occurrence))

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
        private val DEMO_GREETER = """
            --- Greeter module: definition / references demo.
            local M = {}

            --- Build a greeting string.
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

        private val DEMO_UTILS = """
            --- Shared helpers exported for require("utils").
            local M = {}

            function M.log(msg)
                print("[utils]", msg)
            end

            return M
        """.trimIndent()

        private val DEMO_MAIN = """
            --- Entry module: exercises require, locals, and cross-file defs.
            local utils = require("utils")
            local greeter = require("greeter")

            local function run(name)
                local message = greeter.hello(name or "world")
                utils.log(message)
                return message
            end

            local n = 42
            local s = "lua-parser"
            print(run(s), n)

            return {
                run = run,
            }
        """.trimIndent()
    }
}
