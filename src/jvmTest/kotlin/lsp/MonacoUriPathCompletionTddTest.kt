package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import io.github.dingyi222666.luaparser.lsp.LspTextDocumentRequestPolicy
import org.eclipse.lsp4j.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class MonacoUriPathCompletionTddTest {
    @Test
    fun text_document_service_completion_with_file_uri_surfaces_utils_exports() {
        val root = Files.createTempDirectory("monaco-uri-path-")
        write(root, "utils.lua", """
            local M = {}
            function M.log(msg) end
            function M.clamp(x, lo, hi) end
            return M
        """.trimIndent())
        val main = """
            local utils = require("utils")
            local x = utils.
        """.trimIndent()
        write(root, "main.lua", main)

        val service = LuaLanguageService()
        service.initialize(InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "ws"))
        })

        val mainUri = root.resolve("main.lua").toUri().toString()
        val utilsUri = root.resolve("utils.lua").toUri().toString()

        val tds = LuaTextDocumentService(
            languageService = service,
            requestPolicy = { LspTextDocumentRequestPolicy.Accept }
        )
        tds.didOpen(DidOpenTextDocumentParams(TextDocumentItem(utilsUri, "lua", 1, Files.readString(root.resolve("utils.lua")))))
        tds.didOpen(DidOpenTextDocumentParams(TextDocumentItem(mainUri, "lua", 1, main)))

        val idx = main.indexOf("utils.") + "utils.".length
        var line = 0; var ls = 0
        for (i in 0 until idx) {
            if (main[i] == '\n') { line++; ls = i + 1 }
        }
        val pos = Position(line, idx - ls)
        println("mainUri=$mainUri pos=$pos")
        val either = tds.completion(CompletionParams(TextDocumentIdentifier(mainUri), pos)).get()
        val items = if (either.isRight) either.right.items else either.left
        val labels = items.map { it.label }
        println("TDS labels=$labels")
        assertTrue("log" in labels || "clamp" in labels, "got $labels")
    }

    @Test
    fun language_service_completion_with_file_uri_path_string() {
        val root = Files.createTempDirectory("monaco-uri-ls-")
        write(root, "utils.lua", """
            local M = {}
            function M.log(msg) end
            return M
        """.trimIndent())
        val main = """
            local utils = require("utils")
            local x = utils.
        """.trimIndent()
        write(root, "main.lua", main)
        val service = LuaLanguageService()
        service.initialize(InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "ws"))
        })
        val mainUri = root.resolve("main.lua").toUri().toString()
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(mainUri, "lua", 1, main)))
        val idx = main.indexOf("utils.") + "utils.".length
        var line = 0; var ls = 0
        for (i in 0 until idx) {
            if (main[i] == '\n') { line++; ls = i + 1 }
        }
        // Call like TextDocumentService: pathFromUri style absolute virtual path
        val labels1 = service.completion(mainUri, line, idx - ls).items.map { it.label }
        println("LS uri labels=$labels1")
        // Call with absolute filesystem path
        val labels2 = service.completion(root.resolve("main.lua").toString(), line, idx - ls).items.map { it.label }
        println("LS fs path labels=$labels2")
        // Call with relative path
        val labels3 = service.completion("main.lua", line, idx - ls).items.map { it.label }
        println("LS relative labels=$labels3")
        assertTrue(
            "log" in labels1 || "log" in labels2 || "log" in labels3,
            "none worked: uri=$labels1 fs=$labels2 rel=$labels3"
        )
    }

    private fun write(root: Path, name: String, source: String) {
        root.resolve(name).parent?.createDirectories()
        root.resolve(name).writeText(source)
    }
}
