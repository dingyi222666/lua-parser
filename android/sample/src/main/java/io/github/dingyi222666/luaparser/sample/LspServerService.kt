package io.github.dingyi222666.luaparser.sample

import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo
import android.app.NotificationManager
import android.app.NotificationChannel
import android.app.Service
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.Log
import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
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
        @Volatile
        private var pendingWorkspaceMetadata: Map<String, String> = emptyMap()

        /** Supplies per-connection settings before the LSP initialize request. */
        fun configureWorkspaceMetadata(metadata: Map<String, String>) {
            pendingWorkspaceMetadata = metadata.toMap()
        }

        /**
         * Abstract LocalSocket name. MUST stay in sync with the client side
         * (LocalSocketStreamProvider in MainActivity.kt).
         */
        const val SOCKET_NAME = "lua-lsp"

        /** Live workspace-build status for the app UI ("PARSING 5/12", ...). */
        val buildProgress = kotlinx.coroutines.flow.MutableStateFlow("indexing: starting")
        private const val CHANNEL_ID = "lsp-server"
        private const val NOTIFICATION_ID = 42

        private const val TAG = "LspServerService"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guard so repeated onStartCommand calls never spawn a second accept loop. */
    private val acceptLoopRunning = AtomicBoolean(false)

    private var serverSocket: LocalServerSocket? = null
    private val listenerLock = Any()
    private val lifecycleHandler = Handler(Looper.getMainLooper())
    private var latestStartId = 0
    private val stopWhenIdle = Runnable {
        if (clients.isEmpty()) stopSelfResult(latestStartId)
    }

    /** One Job per accepted connection; each job runs one lsp4j reading loop. */
    private val connections = CopyOnWriteArrayList<Job>()
    private val clients = CopyOnWriteArrayList<LocalSocket>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        lifecycleHandler.removeCallbacks(stopWhenIdle)
        lifecycleHandler.postDelayed(stopWhenIdle, 60_000)
        promoteToForeground()
        if (acceptLoopRunning.compareAndSet(false, true)) {
            scope.launch { acceptLoop() }
        }
        // Not sticky: the Activity restarts the service on its next connect.
        return START_NOT_STICKY
    }

    /**
     * Foreground promotion: without it MIUI freezes the whole process seconds
     * after the app goes background — freezing the LSP socket, threads and the
     * in-flight workspace build mid-handshake (device-verified: "Killing ...
     * for binder freeze failed but process frozen").
     */
    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL_ID, "Lua language server", android.app.NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Lua language server")
            .setContentText("Indexing workspace")
            .setOngoing(true)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
            synchronized(listenerLock) {
                if (!acceptLoopRunning.get()) return
                LocalServerSocket(SOCKET_NAME).also { serverSocket = it }
            }
        } catch (failure: IOException) {
            Log.e(TAG, "cannot bind LocalServerSocket \"$SOCKET_NAME\"", failure)
            acceptLoopRunning.set(false)
            stopSelf()
            return
        }
        Log.d(TAG, "Lua LSP listening on abstract local socket \"$SOCKET_NAME\"")

        while (acceptLoopRunning.get()) {
            val client = try {
                listener.accept()
            } catch (_: IOException) {
                // Expected shutdown path: onDestroy closed the listener.
                break
            }
            // NOTE: never call client.remoteSocketAddress here — LocalSocket
            // throws UnsupportedOperationException for abstract-namespace
            // sockets (device-verified crash).
            Log.i(TAG, "LSP client connected")
            clients += client
            lifecycleHandler.removeCallbacks(stopWhenIdle)
            if (!acceptLoopRunning.get()) {
                clients.remove(client)
                client.close()
                break
            }
            val job = scope.launch { serve(client) }
            connections += job
            job.invokeOnCompletion { connections.remove(job) }
        }
    }

    /**
     * Serves one accepted connection to completion: launches a fresh
     * [LuaLanguageServerLauncher] connection over the socket streams and blocks
     * until the lsp4j reading loop finishes (peer disconnect, error or cancel).
     */
    private suspend fun serve(client: LocalSocket) {
        var server: LuaLanguageServer? = null
        try {
            // Fresh LuaLanguageServer per connection (mandatory, see
            // LuaLspServerHost's concurrency contract) + no-op exit hook.
            server = LuaLanguageServer(
                languageService = LuaLanguageService(
                    initialWorkspaceMetadata = pendingWorkspaceMetadata,
                    deferDocumentAnalysis = true
                )
            )
            server.setOnBuildProgressListener { done, total, phase ->
                buildProgress.value = "$phase $done/$total"
            }
            val connection = LuaLanguageServerLauncher.launch(
                client.inputStream,
                client.lspOutputStream(),
                server = server
            )
            // Blocks until the peer closes the stream or we get cancelled.
            connection.listening.get()
        } catch (failure: Throwable) {
            if (acceptLoopRunning.get()) {
                Log.w(TAG, "LSP connection ended with failure", failure)
            }
        } finally {
            server?.exit()
            clients.remove(client)
            lifecycleHandler.post {
                if (acceptLoopRunning.get() && clients.isEmpty()) {
                    lifecycleHandler.removeCallbacks(stopWhenIdle)
                    lifecycleHandler.postDelayed(stopWhenIdle, 60_000)
                }
            }
            try {
                client.close()
            } catch (_: IOException) {
                // best effort
            }
        }
    }

    override fun onDestroy() {
        acceptLoopRunning.set(false)
        lifecycleHandler.removeCallbacksAndMessages(null)
        synchronized(listenerLock) {
            // Closing another thread's fd alone need not interrupt accept on
            // Android. Wake it first so the abstract socket name is released.
            if (serverSocket != null) {
                runCatching {
                    LocalSocket().use { it.connect(LocalSocketAddress(SOCKET_NAME)) }
                }
                runCatching { serverSocket?.close() }
                serverSocket = null
            }
        }
        clients.forEach { client -> runCatching { client.close() } }
        clients.clear()
        connections.forEach { it.cancel() }
        connections.clear()
        scope.cancel()
        super.onDestroy()
    }
}
