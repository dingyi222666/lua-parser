package io.github.dingyi222666.luaparser.sample

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Pair
import io.github.rosemoe.sora.lsp.client.connection.CustomConnectProvider
import java.io.InputStream
import java.io.OutputStream

/**
 * Client-side half of the abstract LocalSocket connection to
 * [LspServerService]: adapts an `android.net.LocalSocket` to sora-editor
 * 0.23.6's [CustomConnectProvider.StreamProvider].
 *
 * This mirrors what sora master later shipped as
 * `LocalSocketStreamConnectionProvider` (connection { local("name") }): the
 * abstract namespace (`LocalSocketAddress.Namespace.ABSTRACT`) is the default
 * for a bare name there, and [LspServerService]'s `LocalServerSocket(name)`
 * constructor also binds abstract — so the two ends meet without any
 * filesystem socket file and without any permission.
 */
class LocalSocketStreamProvider(
    private val socketName: String
) : CustomConnectProvider.StreamProvider {

    private var socket: LocalSocket? = null

    /**
     * Called by CustomConnectProvider.start() on a worker thread (inside
     * LspEditor's connect flow on Dispatchers.IO); may be retried by
     * connectWithTimeout until the service has bound the socket, in which case
     * connect() throws and a fresh provider is created per attempt.
     */
    override fun getStreams(): Pair<InputStream, OutputStream> {
        val localSocket = LocalSocket()
        localSocket.connect(
            LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)
        )
        socket = localSocket
        // android.util.Pair — the StreamProvider contract's pair type.
        return Pair(localSocket.inputStream, localSocket.outputStream)
    }
}
