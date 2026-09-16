package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import java.io.File
import kotlin.test.Test

/** Keystroke-cycle perf probe over the real demo workspace. Prints timings only. */
class PerfProbe {
    @Test
    fun probe() {
        val service = LuaLanguageService().apply {
            initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
        }
        val platforms = File(System.getProperty("user.home"), "Library/Android/sdk/platforms")
        val androidJar = platforms.listFiles()
            ?.filter { it.name.startsWith("android-") }
            ?.maxByOrNull { it.name }?.resolve("android.jar")
        androidJar?.let { jar ->
            LuaWorkspaceService(service).didChangeConfiguration(
                DidChangeConfigurationParams(mapOf("jvm.androidJar" to jar.path))
            )
        }
        val ws = File("tools/monaco-lsp-demo/workspace")
        val files = ws.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".lua") || it.name.endsWith(".aly")) }
            .filterNot { it.path.contains("/libs/") || it.path.contains("/image/") }
            .toList()

        var totalOpen = 0L
        files.forEach { f ->
            val rel = f.relativeTo(ws).path.replace('\\', '/')
            val t0 = System.nanoTime()
            service.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem("file:///workspace/$rel", "lua", 1, f.readText()))
            )
            val ms = (System.nanoTime() - t0) / 1_000_000
            totalOpen += ms
            if (ms > 50) println("OPEN $rel ${ms}ms")
        }
        println("OPEN-TOTAL ${totalOpen}ms")

        val main = File(ws, "main.lua").readText().lines()
        val actLine = main.indexOfFirst { it.startsWith("activity.") }
        val uri = "file:///workspace/main.lua"
        val t0 = System.nanoTime()
        val first = service.completion(uri, actLine, 9).items
        println("CMP-FIRST ${first.size} items ${(System.nanoTime() - t0) / 1_000_000}ms")

        repeat(3) { i ->
            val t = System.nanoTime()
            service.didOpen(
                DidOpenTextDocumentParams(
                    TextDocumentItem(uri, "lua", 1, File(ws, "main.lua").readText() + "\n-- cycle $i")
                )
            )
            val mid = (System.nanoTime() - t) / 1_000_000
            val t2 = System.nanoTime()
            service.completion(uri, actLine, 9).items
            println("CYCLE$i open=${mid}ms cmp=${(System.nanoTime() - t2) / 1_000_000}ms")
        }
    }
}
