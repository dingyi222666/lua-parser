
package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.WorkspaceFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class MonacoUtilsDotProbeTddTest {
    @Test
    fun bare_statement_utils_dot_mid_function_surfaces_export_members() {
        val root = Files.createTempDirectory("lua-parser-utils-dot-")
        val utils = """
            local M = {}
            function M.log(msg) end
            function M.clamp(x, lo, hi) end
            return M
        """.trimIndent()
        val main = """
            local utils = require("utils")
            local function run(name)
                utils.log(name)
                utils.
                return name
            end
            return run
        """.trimIndent()
        write(root, "utils.lua", utils)
        write(root, "main.lua", main)
        val service = LuaLanguageService().also {
            it.initialize(InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), root.fileName.toString()))
            })
        }
        val mainPath = root.resolve("main.lua")
        val src = main
        val needle = "utils."
        // second occurrence: the incomplete bare statement
        var idx = -1
        var from = 0
        repeat(2) {
            idx = src.indexOf(needle, from)
            require(idx >= 0)
            from = idx + needle.length
        }
        val pos = positionAt(src, idx + needle.length)
        val items = service.completion(mainPath.toString(), pos.line, pos.character).items
        val labels = items.map { it.label }
        println("POS line=${pos.line} char=${pos.character} labels=$labels")
        assertTrue("log" in labels || "clamp" in labels, "expected utils exports; got $labels")
    }

    @Test
    fun assign_form_utils_dot_surfaces_export_members() {
        val root = Files.createTempDirectory("lua-parser-utils-dot-assign-")
        val utils = """
            local M = {}
            function M.log(msg) end
            function M.clamp(x, lo, hi) end
            return M
        """.trimIndent()
        val main = """
            local utils = require("utils")
            local x = utils.
        """.trimIndent()
        write(root, "utils.lua", utils)
        write(root, "main.lua", main)
        val service = LuaLanguageService().also {
            it.initialize(InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), root.fileName.toString()))
            })
        }
        val mainPath = root.resolve("main.lua")
        val src = main
        val idx = src.indexOf("utils.")
        val pos = positionAt(src, idx + "utils.".length)
        val labels = service.completion(mainPath.toString(), pos.line, pos.character).items.map { it.label }
        println("ASSIGN POS line=${pos.line} char=${pos.character} labels=$labels")
        assertTrue("log" in labels || "clamp" in labels, "expected utils exports; got $labels")
    }

    private fun write(root: Path, name: String, source: String) {
        val p = root.resolve(name)
        p.parent?.createDirectories()
        p.writeText(source)
    }

    private fun positionAt(source: String, offset: Int): Position {
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
