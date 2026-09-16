package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import java.io.File
import kotlin.test.Test

/** Reproduce the broken fluent chain after a void-returning Java method with table arg. */
class ScratchChainProbe {
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
        val src = """
            import "android.view.*"
            import "android.animation.*"
            local animator = ViewAnimationUtils.createCircularReveal(nil, 0, 0, 0, 0)
            animator.setDuration(500)
            animator.addListener {
              onAnimationEnd = function()
              end
            }
            animator.start()
            animator.setInterpolator(nil)
            animator.cancel()
        """.trimIndent()
        val uri = "file:///workspace/probe.lua"
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, src)))
        val lines = src.lines()

        fun hoverOn(needle: String) {
            val idx = lines.indexOfFirst { it.contains(needle) }
            val col = lines[idx].indexOf(needle)
            val hover = service.hover(HoverParams(org.eclipse.lsp4j.TextDocumentIdentifier(uri), org.eclipse.lsp4j.Position(idx, col)))
            val markup = if (hover?.contents?.isLeft == true) hover.contents.left?.toString() else hover?.contents?.right?.value
            println("PROBE hover '$needle' -> ${markup?.take(120)}")
        }
        hoverOn("createCircularReveal")
        hoverOn("setDuration(500)")
        hoverOn("addListener {")
        hoverOn("start()")
        hoverOn("setInterpolator(nil)")
        hoverOn("cancel()")

        // member completion after the void-returning addListener call
        val aLine = lines.indexOfFirst { it.contains("addListener {") }
        fun memberCompletion(lineIdx: Int, after: String) {
            val col = lines[lineIdx].indexOf(after) + after.length
            val labels = service.completion(uri, lineIdx, col).items.map { it.label }
            println("PROBE member after '$after' (line $lineIdx) -> ${labels.size} items, has start=${"start" in labels}, has cancel=${"cancel" in labels}, sample=${labels.take(8)}")
        }
        memberCompletion(aLine, "animator.")

        // now the chained form as in main.aly
        val src2 = """
            import "android.view.*"
            import "android.animation.*"
            local animator = ViewAnimationUtils.createCircularReveal(nil, 0, 0, 0, 0)
            animator.setDuration(500).setInterpolator(nil).start()
            animator.addListener {
              onAnimationEnd = function()
              end
            }
            animator.setDuration(500).start()
        """.trimIndent()
        val uri2 = "file:///workspace/probe2.lua"
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri2, "lua", 1, src2)))
        val lines2 = src2.lines()
        fun member2(needle: String, after: String) {
            val idx = lines2.indexOfFirst { it.contains(needle) }
            val col = lines2[idx].indexOf(needle) + needle.length
            val labels = service.completion(uri2, idx, col).items.map { it.label }
            println("PROBE after '$needle' -> ${labels.size} items, has start=${"start" in labels}, has setDuration=${"setDuration" in labels}, sample=${labels.take(6)}")
        }
        member2("animator.setDuration(500).", ".")
        member2("animator.setDuration(500).start()", "animator.")
        member2("animator.addListener {", "animator.")
        member2("animator.setDuration(500).start()", ".start")
    }
}
