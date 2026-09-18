package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import io.github.dingyi222666.luaparser.lsp.serveSocket
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Integration proof for Android-style embedding (sora-editor pattern): a loopback TCP
 * [serveSocket] host, a raw `java.net.Socket` client, and a minimal LSP handshake driven
 * byte-for-byte over the wire (Content-Length framed JSON-RPC) — no lsp4j on the client
 * side, mirroring what a remote editor client actually sends:
 *
 * initialize (processId=null, rootUri=null, capabilities={}) -> initialize result with
 * server capabilities -> initialized notification -> shutdown request (result 0) ->
 * exit notification.
 *
 * Also locks in the per-connection contract: the host instantiates one fresh
 * [LuaLanguageServer] per accepted connection (LuaLanguageService keeps per-connection
 * workspace/document state, so sharing one instance across connections is forbidden),
 * and graceful close: connections drain after client disconnect and close() is
 * idempotent and stops the accept loop.
 */
class LuaLspServerHostTddTest {

    @Test
    fun serve_socket_answers_full_lsp_handshake_over_the_wire() {
        val createdServers = mutableListOf<LuaLanguageServer>()
        val host = serveSocket(
            "lua-lsp-handshake",
            serverFactory = { LuaLanguageServer().also(createdServers::add) }
        )
        try {
            assertTrue(host.port > 0, "ephemeral bind should expose a positive port")

            Socket("127.0.0.1", host.port).use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())

                writeFrame(output, initializeRequest())
                val initializeResponse = readResponse(input, id = 1)
                assertTrue(
                    Regex("\"result\"\\s*:\\s*\\{").containsMatchIn(initializeResponse),
                    "initialize result must be a JSON object, got: $initializeResponse"
                )
                assertTrue(
                    Regex("\"capabilities\"\\s*:\\s*\\{").containsMatchIn(initializeResponse),
                    "initialize result must advertise server capabilities, got: $initializeResponse"
                )

                writeFrame(output, initializedNotification())
                writeFrame(output, shutdownRequest())
                val shutdownResponse = readResponse(input, id = 2)
                assertTrue(
                    Regex("\"result\"\\s*:\\s*0\\b").containsMatchIn(shutdownResponse),
                    "shutdown must answer result 0, got: $shutdownResponse"
                )
                writeFrame(output, exitNotification())

                awaitCondition("host lists the live connection") { host.activeConnections.size == 1 }
            }

            // Client disconnect after exit: the connection drains itself (lsp4j reading
            // loop ends on EOF -> host removes + closes it).
            awaitCondition("connection drains after client disconnect") { host.activeConnections.isEmpty() }
            assertEquals(1, createdServers.size, "one LuaLanguageServer must serve one connection")
        } finally {
            host.close()
        }
        host.close() // idempotent
        assertTrue(host.isClosed, "host must report closed after close()")
    }

    @Test
    fun serve_socket_instantiates_one_fresh_server_per_connection() {
        val createdServers = mutableListOf<LuaLanguageServer>()
        val host = serveSocket(
            "lua-lsp-per-connection",
            serverFactory = { LuaLanguageServer().also(createdServers::add) }
        )
        try {
            val clientA = Socket("127.0.0.1", host.port).also { it.soTimeout = SOCKET_TIMEOUT_MS }
            val clientB = Socket("127.0.0.1", host.port).also { it.soTimeout = SOCKET_TIMEOUT_MS }
            try {
                awaitCondition("both connections are listed as active") { host.activeConnections.size == 2 }
                assertEquals(2, createdServers.size, "factory must run once per accepted connection")
                assertNotSame(
                    createdServers[0],
                    createdServers[1],
                    "each connection must get its own LuaLanguageServer (per-connection state contract)"
                )
                assertEquals(2, host.activeConnections.size)
            } finally {
                clientA.close()
                clientB.close()
            }
            awaitCondition("connections drain after both clients disconnect") { host.activeConnections.isEmpty() }
            assertTrue(host.activeConnections.isEmpty())
        } finally {
            host.close()
        }
    }

    @Test
    fun close_is_graceful_idempotent_and_stops_the_accept_loop() {
        val host = serveSocket("lua-lsp-close", port = 0)
        val port = host.port
        assertTrue(port > 0)

        host.close()
        host.close() // second close is a no-op, must not throw
        assertTrue(host.isClosed)
        assertTrue(host.activeConnections.isEmpty(), "close must leave no active connections")

        // Accept loop stopped: the loopback listener is gone, so a new client is refused.
        assertFailsWith<ConnectException>("connect must be refused after host close") {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MS
                socket.getInputStream().read()
            }
        }
    }

    // ------------------------------------------------------------------ LSP wire helpers

    /**
     * Writes one LSP Content-Length framed JSON-RPC message (VSCode-style transport):
     * `Content-Length: <bytes>\r\n\r\n<body>`.
     */
    private fun writeFrame(output: OutputStream, payload: String) {
        val body = payload.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        output.write(header)
        output.write(body)
        output.flush()
    }

    /** Reads exactly one LSP frame: headers up to the blank line, then Content-Length body bytes. */
    private fun readFrame(input: InputStream): String {
        val headerBytes = ArrayList<Byte>()
        while (true) {
            val byte = input.read()
            check(byte >= 0) { "Stream ended while reading LSP frame header" }
            headerBytes.add(byte.toByte())
            val size = headerBytes.size
            val headerEnd = size >= 4 &&
                headerBytes[size - 4] == 13.toByte() && headerBytes[size - 3] == 10.toByte() &&
                headerBytes[size - 2] == 13.toByte() && headerBytes[size - 1] == 10.toByte()
            if (headerEnd) break
        }
        val headerText = String(headerBytes.toByteArray(), Charsets.US_ASCII)
        val contentLength = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(headerText)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: error("Missing Content-Length header in: $headerText")
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val count = input.read(body, read, contentLength - read)
            check(count > 0) { "Stream ended while reading LSP frame body ($read/$contentLength)" }
            read += count
        }
        return String(body, Charsets.UTF_8)
    }

    /**
     * Reads frames until the JSON-RPC response with the expected id arrives, skipping any
     * server-initiated notifications in between (defensive; this handshake expects none).
     */
    private fun readResponse(input: InputStream, id: Int): String {
        val idPattern = Regex("\"id\"\\s*:\\s*$id\\b")
        repeat(MAX_FRAMES_PER_RESPONSE) {
            val frame = readFrame(input)
            if (idPattern.containsMatchIn(frame)) {
                return frame
            }
        }
        fail("No JSON-RPC response with id=$id arrived")
    }

    // Minimal LSP payloads, exactly what a lean editor client sends.
    private fun initializeRequest(): String =
        """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
            """"params":{"processId":null,"rootUri":null,"capabilities":{}}}"""

    private fun initializedNotification(): String =
        """{"jsonrpc":"2.0","method":"initialized","params":{}}"""

    private fun shutdownRequest(): String =
        """{"jsonrpc":"2.0","id":2,"method":"shutdown"}"""

    private fun exitNotification(): String =
        """{"jsonrpc":"2.0","method":"exit"}"""

    private fun awaitCondition(
        message: String,
        timeoutMs: Long = CONDITION_TIMEOUT_MS,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(25)
        }
        if (!condition()) {
            fail("$message within ${timeoutMs}ms")
        }
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 15_000
        const val CONDITION_TIMEOUT_MS = 10_000L
        const val MAX_FRAMES_PER_RESPONSE = 20
    }
}
