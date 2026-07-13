package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reproduce Monaco demo: type bare `utils.` mid-function after require("utils"),
 * through full didOpen/didChange + workspace folder indexing of utils.lua.
 */
class MonacoDemoLiveUtilsDotTddTest {
    @Test
    fun live_edit_bare_utils_dot_after_did_change_surfaces_exports() {
        val root = Files.createTempDirectory("monaco-demo-live-utils-")
        val utils = Files.readString(
            Path.of("tools/monaco-lsp-demo/workspace/utils.lua")
        )
        val mainDisk = Files.readString(
            Path.of("tools/monaco-lsp-demo/workspace/main.lua")
        )
        val greeter = Files.readString(
            Path.of("tools/monaco-lsp-demo/workspace/greeter.lua")
        )
        write(root, "utils.lua", utils)
        write(root, "greeter.lua", greeter)
        write(root, "main.lua", mainDisk)

        val service = LuaLanguageService().also {
            it.initialize(InitializeParams().apply {
                workspaceFolders = listOf(
                    WorkspaceFolder(root.toUri().toString(), "monaco-lsp-demo")
                )
            })
        }

        val mainPath = root.resolve("main.lua")
        val mainUri = mainPath.toUri().toString()

        // Open disk main
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(mainUri, "lua", 1, mainDisk)
            )
        )

        // Live edit: insert bare utils. before return, matching screenshot mid-run()
        val edited = """
            --- Entry module: exercises require, locals, and cross-file defs.
            local utils = require("utils")
            local greeter = require("greeter")

            local function run(name)
                local message = greeter.hello(name or "world")
                utils.log(message)
                utils.
                return message
            end

            -- Hover / completion targets
            local n = 42
            local s = "lua-parser"
            print(run(s), n)

            return {
                run = run,
            }
        """.trimIndent() + "\n"

        service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(mainUri, 2),
                listOf(TextDocumentContentChangeEvent(edited))
            )
        )

        val needle = "utils."
        // second occurrence = incomplete bare statement
        var idx = -1
        var from = 0
        repeat(2) {
            idx = edited.indexOf(needle, from)
            require(idx >= 0) { "needle not found" }
            from = idx + needle.length
        }
        val pos = positionAt(edited, idx + needle.length)
        val items = service.completion(mainUri, pos.line, pos.character).items
        val labels = items.map { it.label }
        println("LIVE labels=$labels pos=$pos uri=$mainUri")
        assertTrue(
            "log" in labels || "clamp" in labels || "join" in labels,
            "expected utils exports after live didChange; got $labels"
        )
    }

    @Test
    fun live_edit_assign_utils_dot_surfaces_exports() {
        val root = Files.createTempDirectory("monaco-demo-live-utils-assign-")
        val utils = Files.readString(Path.of("tools/monaco-lsp-demo/workspace/utils.lua"))
        write(root, "utils.lua", utils)
        val mainDisk = """
            local utils = require("utils")
            local x = 1
        """.trimIndent() + "\n"
        write(root, "main.lua", mainDisk)
        val service = LuaLanguageService().also {
            it.initialize(InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "ws"))
            })
        }
        val mainPath = root.resolve("main.lua")
        val mainUri = mainPath.toUri().toString()
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(mainUri, "lua", 1, mainDisk)))
        val edited = """
            local utils = require("utils")
            local x = utils.
        """.trimIndent() + "\n"
        service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(mainUri, 2),
                listOf(TextDocumentContentChangeEvent(edited))
            )
        )
        val idx = edited.indexOf("utils.")
        val pos = positionAt(edited, idx + "utils.".length)
        val labels = service.completion(mainUri, pos.line, pos.character).items.map { it.label }
        println("ASSIGN LIVE labels=$labels")
        assertTrue("log" in labels || "clamp" in labels, "got $labels")
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
