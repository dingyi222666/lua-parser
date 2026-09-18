package io.github.dingyi222666.luaparser.lsp

import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Future

/**
 * One launched LSP connection: the [server] instance serving it, the lsp4j [launcher]
 * wired over the connection streams, and the lsp4j reading-loop future ([listening],
 * completes when the peer stream closes or the loop is cancelled).
 *
 * This is the handle an embedded host keeps per connection; see [LuaLanguageServerLauncher.launch].
 */
data class LuaLanguageServerConnection(
    val server: LuaLanguageServer,
    val launcher: Launcher<LanguageClient>,
    val listening: Future<Void>
)

object LuaLanguageServerLauncher {

    /**
     * Desktop stdio entry point: launches over the given streams and returns the server
     * without blocking. Wires [kotlin.system.exitProcess] as the `exit` hook so a desktop
     * client's `exit` notification terminates the process (VSCode extension behavior);
     * embedded hosts must use [launch] with a no-op (default) or custom hook instead.
     */
    fun start(input: InputStream = System.`in`, output: OutputStream = System.out): LuaLanguageServer {
        return launch(input, output, processExit = { code -> kotlin.system.exitProcess(code) }).server
    }

    /**
     * Desktop stdio entry point: launches over the given streams and blocks until the
     * lsp4j reading loop finishes (peer closed the stream). Wires
     * [kotlin.system.exitProcess] as the `exit` hook (desktop/VSCode behavior).
     */
    fun startAndWait(input: InputStream = System.`in`, output: OutputStream = System.out): LuaLanguageServer {
        val connection = launch(input, output, processExit = { code -> kotlin.system.exitProcess(code) })
        connection.listening.get()
        return connection.server
    }

    /**
     * Launches one [LuaLanguageServer] over arbitrary [input]/[output] streams and starts
     * the lsp4j message loop. This is the embedding entry point for Android hosts
     * (sora-editor style, in-process server): call it ONCE PER CONNECTION with fresh
     * streams — e.g. per socket accepted from `java.net.ServerSocket` (see
     * [LuaLspServerHost.serveSocket]) or per `android.net.LocalServerSocket.accept()` stream.
     *
     * The returned [LuaLanguageServerConnection.server] must be kept by the host for the
     * lifetime of the connection; [LuaLanguageServerConnection.listening] completes when
     * the connection ends (peer closed, error, or [LuaLspServerHost.close] cancelled it).
     *
     * @param processExit hook invoked when the client sends the LSP `exit` notification.
     *   Defaults to a no-op: an embedded server must never kill its host app process.
     *   The server still transitions to EXITED (post-exit requests are rejected, see
     *   [LuaLanguageServer]), so the host only needs to drop the connection when
     *   [LuaLanguageServerConnection.listening] completes. The desktop stdio entry points
     *   ([start]/[startAndWait]/[main]) pin `exitProcess` here to keep process semantics.
     * @param server optional pre-built server instance (e.g. from an embedded host's
     *   per-connection factory, see [LuaLspServerHost.serveSocket]); when null a fresh
     *   [LuaLanguageServer] with [processExit] is created. NOTE: a provided instance
     *   keeps its own `processExit` hook (its constructor default is already a no-op).
     */
    fun launch(
        input: InputStream = System.`in`,
        output: OutputStream = System.out,
        processExit: (Int) -> Unit = {},
        server: LuaLanguageServer? = null
    ): LuaLanguageServerConnection {
        val service = server ?: LuaLanguageServer(processExit = processExit)
        val launcher = Launcher.Builder<LanguageClient>()
            .setLocalService(service)
            .setRemoteInterface(LanguageClient::class.java)
            .setInput(input)
            .setOutput(output)
            .create()
        service.connect(launcher.remoteProxy)
        val listening = launcher.startListening()
        return LuaLanguageServerConnection(service, launcher, listening)
    }
}

fun main() {
    LuaLanguageServerLauncher.startAndWait()
}
