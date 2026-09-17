package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LspJavaOverloadHoverTddTest {
    @Test
    fun unresolved_overload_merges_one_varying_parameter() {
        val document = service().open(
            "workspace/java-overload-hover-unresolved.lua",
            """
            local Overloads = luajava.bindClass("lsp.LspJavaOverloadHoverTddTest${'$'}Overloads")
            local method = Overloads.pick
            return method
            """
        )

        val text = hoverText(document.service, document, "pick")

        assertTrue(text.contains("number"), text)
        assertTrue(text.contains("string"), text)
        assertTrue(text.contains(" | "), text)
        assertFalse(text.contains(" & fun"), text)
    }

    @Test
    fun concrete_argument_locks_hover_to_selected_overload() {
        val service = service()
        val numberDocument = service.open(
            "workspace/java-overload-hover-number.lua",
            """
            local Overloads = luajava.bindClass("lsp.LspJavaOverloadHoverTddTest${'$'}Overloads")
            local result = Overloads.pick(42)
            return result
            """
        )
        val stringDocument = service.open(
            "workspace/java-overload-hover-string.lua",
            """
            local Overloads = luajava.bindClass("lsp.LspJavaOverloadHoverTddTest${'$'}Overloads")
            local result = Overloads.pick("value")
            return result
            """
        )

        val numberHover = hoverText(service, numberDocument, "pick")
        val stringHover = hoverText(service, stringDocument, "pick")

        assertTrue(numberHover.contains("number"), numberHover)
        assertFalse(numberHover.contains("string"), numberHover)
        assertTrue(stringHover.contains("string"), stringHover)
        assertFalse(stringHover.contains("number"), stringHover)
    }

    private fun service(): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
            setWorkspaceMetadata(
                mapOf(
                    JvmClassModuleProvider.CLASSES_METADATA_KEY to
                        "lsp.LspJavaOverloadHoverTddTest${'$'}Overloads"
                )
            )
        }
    }

    private fun LuaLanguageService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(this, path, source.trimIndent())
        didOpen(DidOpenTextDocumentParams(TextDocumentItem(document.uri, "lua", 1, document.source)))
        return document
    }

    private fun hoverText(service: LuaLanguageService, document: OpenDocument, needle: String): String {
        val hover = assertNotNull(
            service.hover(
                HoverParams(
                    TextDocumentIdentifier(document.uri),
                    document.positionOf(needle)
                )
            )
        )
        return if (hover.contents.isRight) {
            hover.contents.right.value
        } else {
            hover.contents.left.joinToString("\n") { it.toString() }
        }
    }

    private data class OpenDocument(
        val service: LuaLanguageService,
        val path: String,
        val source: String
    ) {
        val uri: String = "file:///$path"

        fun positionOf(needle: String): Position {
            val offset = source.indexOf(needle)
            require(offset >= 0)
            val prefix = source.substring(0, offset)
            val line = prefix.count { it == '\n' }
            val lineStart = prefix.lastIndexOf('\n') + 1
            return Position(line, offset - lineStart)
        }
    }

    class Overloads {
        companion object {
            @JvmStatic
            fun pick(value: Int) = Unit

            @JvmStatic
            fun pick(value: String) = Unit
        }
    }
}
