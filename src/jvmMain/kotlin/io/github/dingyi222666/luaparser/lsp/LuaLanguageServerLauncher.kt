package io.github.dingyi222666.luaparser.lsp

import org.eclipse.lsp4j.jsonrpc.Launcher
import java.io.InputStream
import java.io.OutputStream

object LuaLanguageServerLauncher {
    fun start(input: InputStream = System.`in`, output: OutputStream = System.out): LuaLanguageServer {
        val server = LuaLanguageServer()
        val launcher = Launcher.Builder<org.eclipse.lsp4j.services.LanguageClient>()
            .setLocalService(server)
            .setRemoteInterface(org.eclipse.lsp4j.services.LanguageClient::class.java)
            .setInput(input)
            .setOutput(output)
            .create()
        server.connect(launcher.remoteProxy)
        launcher.startListening()
        return server
    }
}

fun main() {
    LuaLanguageServerLauncher.start()
}
