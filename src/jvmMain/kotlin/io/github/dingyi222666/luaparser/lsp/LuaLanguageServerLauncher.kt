package io.github.dingyi222666.luaparser.lsp

import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Future

data class LuaLanguageServerConnection(
    val server: LuaLanguageServer,
    val launcher: Launcher<LanguageClient>,
    val listening: Future<*>
)

object LuaLanguageServerLauncher {
    fun start(input: InputStream = System.`in`, output: OutputStream = System.out): LuaLanguageServer {
        return launch(input, output).server
    }

    fun startAndWait(input: InputStream = System.`in`, output: OutputStream = System.out): LuaLanguageServer {
        val connection = launch(input, output)
        connection.listening.get()
        return connection.server
    }

    fun launch(input: InputStream = System.`in`, output: OutputStream = System.out): LuaLanguageServerConnection {
        val server = LuaLanguageServer()
        val launcher = Launcher.Builder<LanguageClient>()
            .setLocalService(server)
            .setRemoteInterface(LanguageClient::class.java)
            .setInput(input)
            .setOutput(output)
            .create()
        server.connect(launcher.remoteProxy)
        val listening = launcher.startListening()
        return LuaLanguageServerConnection(server, launcher, listening)
    }
}

fun main() {
    LuaLanguageServerLauncher.startAndWait()
}
