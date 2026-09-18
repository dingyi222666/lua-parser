package io.github.dingyi222666.luaparser.lsp

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * # Embedding the Lua LSP server in an Android host (sora-editor style)
 *
 * This file is the plain-JVM socket host for in-process LSP serving: the editor client
 * (e.g. sora-editor's lsp module) connects over a local socket and speaks LSP over
 * Content-Length framed JSON-RPC, with one lsp4j launcher per connection
 * ([LuaLanguageServerLauncher.launch]).
 *
 * ## Concurrency contract — ONE [LuaLanguageServer] PER CONNECTION (mandatory)
 *
 * [LuaLanguageService], the engine behind every server instance, holds per-connection
 * mutable state guarded by its `stateLock`:
 *  - `openDocuments` / `documentUris` — the didOpen/didChange/didClose text overlays,
 *    authoritative for completion, hover, definition, diagnostics, ...
 *  - `indexedWorkspaceFiles` / `indexedWorkspaceUris` / `workspaceFolderUriPrefixes`
 *    and `workspaceMetadata` / `workspaceFolders` — the workspace snapshot.
 *  - `semanticTokensCache` — per-document token cache.
 *  - client capabilities captured once at `initialize` (hierarchical document symbols,
 *    modern `workspace.symbol.resolveSupport`, `definition.linkSupport`).
 *
 * [LuaLanguageServer] additionally holds exactly one `client` ([LanguageClient]) reference
 * (diagnostics are published to it) and a single-shot lifecycle state
 * (CREATED -> INITIALIZED -> SHUTDOWN/EXITED; a second `initialize` fails with
 * "already initialized").
 *
 * Sharing one instance across two connections would therefore: publish editor A's
 * diagnostics to editor B, mix A's open buffers into B's queries, and hard-fail B's
 * `initialize`. Hosts MUST create one [LuaLanguageServer] per accepted connection —
 * exactly what the sora-editor lua sample does (a fresh server per accepted socket).
 * [serveSocket] enforces this by invoking [serveSocket.serverFactory] once per accepted
 * connection; custom hosts calling [LuaLanguageServerLauncher.launch] directly must do
 * the same.
 *
 * ## TCP loopback vs `android.net.LocalServerSocket`
 *
 * This module is plain JVM (`jvmMain`), so the accept loop uses `java.net.ServerSocket`
 * bound to loopback only — this works unchanged on Android (loopback traffic stays inside
 * the app sandbox; no INTERNET permission is required for 127.0.0.1). Hosts that prefer
 * `android.net.LocalServerSocket` (abstract UNIX domain sockets: no port collision,
 * kernel-level same-uid isolation) run their own accept loop and delegate to
 * [LuaLanguageServerLauncher.launch] per accepted stream:
 *
 * ```kotlin
 * val listener = android.net.LocalServerSocket("lua.lsp")
 * while (true) {
 *     val socket = listener.accept() // LocalSocket
 *     LuaLanguageServerLauncher.launch(socket.inputStream, socket.outputStream)
 * }
 * ```
 */
class LuaLspServerHost internal constructor(
    /** Label from [serveSocket]; used for accept/connection thread names and diagnostics. */
    val socketName: String,
    private val boundPort: Int,
    private val serverSocket: ServerSocket,
    private val acceptThread: Thread,
    private val connections: CopyOnWriteArrayList<LuaLspHostConnection>,
    private val closed: AtomicBoolean
) : Closeable {

    /** Actual bound TCP port; the ephemeral port when [serveSocket] was called with port 0. */
    val port: Int
        get() = boundPort

    val isClosed: Boolean
        get() = closed.get()

    /** Live connections (one [LuaLanguageServer] each), in accept order. */
    val activeConnections: List<LuaLspHostConnection>
        get() = connections.toList()

    /**
     * Graceful shutdown: stops the accept loop, closes the server socket, closes every
     * active connection (cancelling its lsp4j reading loop and closing its socket), then
     * joins the accept thread. Idempotent; safe to call from any thread.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        try {
            serverSocket.close()
        } catch (_: Exception) {
            // best effort — the accept loop observes closure via its flag
        }
        connections.toList().forEach { it.close() }
        connections.clear()
        try {
            acceptThread.join(TimeUnit.SECONDS.toMillis(ACCEPT_THREAD_JOIN_TIMEOUT_SECONDS))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val ACCEPT_THREAD_JOIN_TIMEOUT_SECONDS = 5L
    }
}

/**
 * One accepted client connection hosted by [LuaLspServerHost]: its socket, the
 * [LuaLanguageServer] instance created for it (fresh per connection — see the
 * per-connection contract on [LuaLspServerHost]), and the lsp4j reading-loop future
 * (completes when the peer disconnects or the connection is closed).
 */
class LuaLspHostConnection internal constructor(
    val socket: Socket,
    val server: LuaLanguageServer,
    val listening: Future<Void>
) : Closeable {

    private val closed = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closed.get()

    /** Cancels the lsp4j reading loop and closes the socket. Idempotent. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        listening.cancel(true)
        try {
            socket.close()
        } catch (_: Exception) {
            // best effort
        }
    }
}

/**
 * Opens a loopback TCP server socket and serves one fresh [LuaLanguageServer] per
 * accepted connection on a daemon background thread. Android-compatible
 * (plain `java.net`; see the `android.net.LocalServerSocket` alternative documented on
 * [LuaLspServerHost]).
 *
 * @param socketName label for the accept/reader thread names and the returned host;
 *   on the TCP route it is informational only (no unix-socket namespace exists here).
 * @param serverFactory invoked once PER accepted connection; must return a fresh
 *   [LuaLanguageServer] each call (mandatory — see the per-connection contract on
 *   [LuaLspServerHost]). The default creates a plain `LuaLanguageServer()`.
 * @param onError invoked (on the accept thread) for accept/launch failures that are not
 *   the host's own shutdown; keep it cheap and non-throwing — it can never take down the
 *   accept loop.
 * @param port TCP port to bind on loopback; 0 picks an ephemeral port (the resolved port
 *   is exposed as [LuaLspServerHost.port]).
 * @return the live [LuaLspServerHost]; call [LuaLspServerHost.close] on host shutdown.
 */
fun serveSocket(
    socketName: String,
    serverFactory: () -> LuaLanguageServer = { LuaLanguageServer() },
    onError: (Throwable) -> Unit = {},
    port: Int = 0
): LuaLspServerHost {
    val serverSocket = ServerSocket()
    // Loopback only: an LSP endpoint must never be reachable from the network, and
    // Android app-internal clients always connect through 127.0.0.1.
    serverSocket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
    val boundPort = serverSocket.localPort

    val closed = AtomicBoolean(false)
    val connections = CopyOnWriteArrayList<LuaLspHostConnection>()

    fun notifyError(failure: Throwable) {
        try {
            onError(failure)
        } catch (_: Throwable) {
            // onError must never take down the accept loop
        }
    }

    fun watchConnection(connection: LuaLspHostConnection) {
        val watcher = Thread({
            try {
                connection.listening.get()
            } catch (_: Exception) {
                // reading loop failed or was cancelled — either way this connection is done
            } finally {
                connections.remove(connection)
                connection.close()
            }
        }, "lua-lsp-conn-$socketName-${connection.socket.port}")
        watcher.isDaemon = true
        watcher.start()
    }

    fun serveClient(client: Socket) {
        try {
            // Per-connection contract: a FRESH server instance per accepted socket
            // (see the concurrency contract on [LuaLspServerHost]).
            val launched = LuaLanguageServerLauncher.launch(
                client.getInputStream(),
                client.getOutputStream(),
                server = serverFactory()
            )
            val connection = LuaLspHostConnection(client, launched.server, launched.listening)
            connections.add(connection)
            watchConnection(connection)
        } catch (failure: Throwable) {
            notifyError(failure)
            try {
                client.close()
            } catch (_: Exception) {
                // best effort
            }
        }
    }

    val acceptLoop = Runnable {
        while (!closed.get()) {
            val client: Socket = try {
                serverSocket.accept()
            } catch (failure: Exception) {
                if (closed.get() || serverSocket.isClosed) {
                    break // expected shutdown path: close() unblocked us
                }
                notifyError(failure)
                try {
                    Thread.sleep(ACCEPT_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            serveClient(client)
        }
    }

    val acceptThread = Thread(acceptLoop, "lua-lsp-accept-$socketName")
    acceptThread.isDaemon = true
    val host = LuaLspServerHost(socketName, boundPort, serverSocket, acceptThread, connections, closed)
    acceptThread.start()
    return host
}

private const val ACCEPT_RETRY_DELAY_MS = 50L
