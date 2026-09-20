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
import kotlin.test.assertEquals

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
        // Drive the SERVER wrapper: the wave-S regression was that Kotlin `by`
        // delegation on LuaLanguageServer's TextDocumentService wrapper skipped
        // lsp4j default methods (foldingRange threw UnsupportedOperationException
        // over the wire while the delegate worked fine in isolation).
        val server = io.github.dingyi222666.luaparser.lsp.LuaLanguageServer()
        val clientProxy = java.lang.reflect.Proxy.newProxyInstance(
            java.lang.Thread.currentThread().contextClassLoader,
            arrayOf(org.eclipse.lsp4j.services.LanguageClient::class.java),
            java.lang.reflect.InvocationHandler { _, _, _ -> null }
        ) as org.eclipse.lsp4j.services.LanguageClient
        server.connect(clientProxy)
        server.initialize(InitializeParams().apply {
            workspaceFolders = listOf(
                WorkspaceFolder("file://${workspace.absolutePath}", "monaco-lsp-demo")
            )
        })
        server.flushBackgroundRebuild()
            workspaceFolders = listOf(
                WorkspaceFolder("file://${workspace.absolutePath}", "monaco-lsp-demo")
            )
        })
        val serverService = server.textDocumentService
        val main = File(workspace, "main.lua")
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem("file://${main.absolutePath}", "lua", 1, main.readText())
            )
        )
        // Wrapper path: the foldingRange forwarder must answer (pre-fix: UnsupportedOperationException).
        val throughWrapper = serverService.foldingRange(
            FoldingRangeRequestParams(TextDocumentIdentifier("file://${main.absolutePath}"))
        ).get()
        println("WRAPPER_RESULT=${throughWrapper.size}")
        // Delegate path stays the source of truth for the shape.
        val result = service.foldingRanges(
            FoldingRangeRequestParams(TextDocumentIdentifier("file://${main.absolutePath}"))
        )
        println("RESULT=${result.size}")
        assertEquals(result.size, throughWrapper.size, "wrapper must forward to the delegate")
    }
}
