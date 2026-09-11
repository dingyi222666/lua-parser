package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import java.io.File
import kotlin.test.Test

class FoldingReproTest {
    @Test
    fun repro() {
        val workspace = File("/Users/dingyi/projects/java_projects/lua-parser/tools/monaco-lsp-demo/workspace")
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
