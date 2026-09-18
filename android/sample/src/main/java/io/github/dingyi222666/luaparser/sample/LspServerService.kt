package io.github.dingyi222666.luaparser.sample

import android.app.Service
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.IBinder
import android.util.Log
import io.github.dingyi222666.luaparser.lsp.LuaLanguageServerLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android host for the embedded Lua language server, sora-editor style
 * (the sample-side equivalent of the desktop [serveSocket] host in
 * `:android`'s jvmMain sources, `LuaLspServerHost.kt`).
 *
 * Instead of the TCP loopback `java.net.ServerSocket` used by the plain-JVM
 * host (and by sora-editor's own demo service), this service listens on an
 * abstract-namespace `android.net.LocalServerSocket("lua-lsp")`:
 *   - no port collision / no port to pick,
 *   - kernel-level same-uid isolation (no other app can connect),
 *   - no INTERNET permission and no loopback socket policy concerns.
 *
 * Per the concurrency contract documented on [LuaLspServerHost] (see the
 * `:android` sources), the accept loop creates ONE fresh
 * [io.github.dingyi222666.luaparser.lsp.LuaLanguageServer] PER ACCEPTED
 * CONNECTION via [LuaLanguageServerLauncher.launch] — never a shared instance:
 * each server owns its open-document overlays, workspace snapshot and client
 * reference, and a second `initialize` on a shared instance would fail.
 *
 * Lifecycle: [MainActivity] starts this service before connecting; the client
 * side ([MainActivity]'s CustomConnectProvider stream provider) retries until
 * the socket appears. [onDestroy] closes the listener and cancels every live
 * connection (the lsp4j reading loops exit with their streams).
 */
class LspServerService : Service() {

    companion object {
        /**
         * Abstract LocalSocket name. MUST stay in sync with the client side
         * (LocalSocketStreamProvider in MainActivity.kt).
         */
        const val SOCKET_NAME = "lua-lsp"

        private const val TAG = "LspServerService"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guard so repeated onStartCommand calls never spawn a second accept loop. */
    private val acceptLoopRunning = AtomicBoolean(false)

    private var serverSocket: LocalServerSocket? = null

    /** One Job per accepted connection; each job runs one lsp4j reading loop. */
    private val connections = CopyOnWriteArrayList<Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (acceptLoopRunning.compareAndSet(false, true)) {
            scope.launch { acceptLoop() }
        }
        // Not sticky: the Activity restarts the service on its next connect.
        return START_NOT_STICKY
    }

    /**
     * Blocking accept loop on Dispatchers.IO. Exits when [onDestroy] closes the
     * listener (accept() then throws IOException) or when the socket cannot be
     * bound at all (name already taken by a previous instance — the client's
     * connectWithTimeout retry loop against the still-live server is the right
     * outcome in that case).
     */
    private suspend fun acceptLoop() {
        val listener = try {
            LocalServerSocket(SOCKET_NAME)
        } catch (failure: IOException) {
            Log.e(TAG, "cannot bind LocalServerSocket \"$SOCKET_NAME\"", failure)
            acceptLoopRunning.set(false)
            stopSelf()
            return
        }
        serverSocket = listener
        Log.d(TAG, "Lua LSP listening on abstract local socket \"$SOCKET_NAME\"")

        while (acceptLoopRunning.get()) {
            val client = try {
                listener.accept()
            } catch (_: IOException) {
                // Expected shutdown path: onDestroy closed the listener.
                break
            }
            connections += scope.launch { serve(client) }
        }
    }

    /**
     * Serves one accepted connection to completion: launches a fresh
     * [LuaLanguageServerLauncher] connection over the socket streams and blocks
     * until the lsp4j reading loop finishes (peer disconnect, error or cancel).
     */
    private suspend fun serve(client: LocalSocket) {
        try {
            val connection = LuaLanguageServerLauncher.launch(
                client.inputStream,
                client.outputStream
                // Defaults are correct here: a FRESH LuaLanguageServer per
                // connection (mandatory, see LuaLspServerHost's concurrency
                // contract) and a no-op exit hook (an embedded server must
                // never kill the host app process on LSP `exit`).
            )
            // Blocks until the peer closes the stream or we get cancelled.
            connection.listening.get()
        } catch (failure: Throwable) {
            if (acceptLoopRunning.get()) {
                Log.w(TAG, "LSP connection ended with failure", failure)
            }
        } finally {
            try {
                client.close()
            } catch (_: IOException) {
                // best effort
            }
        }
    }

    override fun onDestroy() {
        acceptLoopRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // best effort
        }
        serverSocket = null
        connections.forEach { it.cancel() }
        connections.clear()
        scope.cancel()
        super.onDestroy()
    }
}
