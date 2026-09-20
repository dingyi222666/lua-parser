package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import org.eclipse.lsp4j.InitializeParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LspCompletionCapabilitiesTddTest {
    @Test
    fun initialize_advertises_lua_member_completion_trigger_characters() {
        val server = LuaLanguageServer()

        val completionProvider = assertNotNull(server.initialize(InitializeParams()).get().capabilities.completionProvider)

        assertEquals(listOf(".", ":"), completionProvider.triggerCharacters)
    }
}
