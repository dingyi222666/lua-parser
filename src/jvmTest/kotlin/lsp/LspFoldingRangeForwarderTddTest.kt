package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

class LspFoldingRangeForwarderTddTest {
    @Test
    fun repro() {
        // Portable multi-file workspace: the forwarder path needs a didOpen'd document
        // plus workspace state, not the demo corpus specifically.
        val workspace = Files.createTempDirectory("folding-forwarder").toFile()
        File(workspace, "main.lua").writeText("local x = 1\nprint(x)\n")
        File(workspace, "util.lua").writeText("return { trim = function(s) return s end }\n")
        val service = LuaLanguageService().apply {
            initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(
                        WorkspaceFolder("file://${workspace.absolutePath}", "monaco-lsp-demo")
                    )
                }
            )
        }
        val main = File(workspace, "main.lua")
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem("file://${main.absolutePath}", "lua", 1, main.readText())
            )
        )
        val result = service.foldingRanges(
            FoldingRangeRequestParams(TextDocumentIdentifier("file://${main.absolutePath}"))
        )
        println("RESULT=${result.size}")
    }
}
