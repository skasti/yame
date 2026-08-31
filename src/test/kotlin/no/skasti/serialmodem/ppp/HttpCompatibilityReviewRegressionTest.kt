package no.skasti.serialmodem.ppp

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpCompatibilityReviewRegressionTest {
    @Test
    fun `redirected subresource remains an exact route instead of replacing the navigation origin`() {
        val originServer = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
        val resourceServer = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

        val originThread = Thread {
            originServer.use { listening ->
                listening.accept().use { socket ->
                    readRequest(socket)
                    writeResponse(
                        socket,
                        "HTTP/1.1 302 Found\r\n" +
                            "Location: http://127.0.0.1:${resourceServer.localPort}/logo.png\r\n" +
                            "Content-Length: 0\r\n" +
                            "Connection: close\r\n\r\n",
                    )
                }
                listening.accept().use { socket ->
                    val request = readRequest(socket)
                    assertTrue(request.startsWith("GET /next HTTP/1.1"), request)
                    val body = "origin-next"
                    writeResponse(
                        socket,
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: ${body.length}\r\n" +
                            "Connection: close\r\n\r\n" +
                            body,
                    )
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val resourceThread = Thread {
            resourceServer.use { listening ->
                listening.accept().use { socket ->
                    val request = readRequest(socket)
                    assertTrue(request.startsWith("GET /logo.png HTTP/1.1"), request)
                    val body = "logo"
                    writeResponse(
                        socket,
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: image/png\r\n" +
                            "Content-Length: ${body.length}\r\n" +
                            "Connection: close\r\n\r\n" +
                            body,
                    )
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val proxy = SystemHttpCompatibilityProxy(PppHttpCompatibilityConfig(requestTimeoutMillis = 2_000))
        try {
            val resourceResponse = request(proxy, 2301, originServer.localPort, "/logo.png")
            assertTrue(resourceResponse.endsWith("logo"), resourceResponse)

            val nextResponse = request(proxy, 2302, originServer.localPort, "/next")
            assertTrue(nextResponse.endsWith("origin-next"), nextResponse)
        } finally {
            proxy.close()
            runCatching { originServer.close() }
            runCatching { resourceServer.close() }
            originThread.join(2_000)
            resourceThread.join(2_000)
        }
    }

    @Test
    fun `HTTPS references with userinfo are rewritten as complete clean URLs`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val body = "<a href=\"https://alice:secret@example.test/private\">private</a>"
        val thread = serveOnce(server) {
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: ${body.toByteArray(StandardCharsets.ISO_8859_1).size}\r\n" +
                "Connection: close\r\n\r\n" +
                body
        }
        val proxy = SystemHttpCompatibilityProxy(PppHttpCompatibilityConfig(requestTimeoutMillis = 2_000))

        try {
            val response = request(proxy, 2303, server.localPort, "/")
            assertTrue(response.contains("http://alice:secret@example.test/private"), response)
            assertTrue(!response.contains("http://alice/:secret@example.test/private"), response)
        } finally {
            proxy.close()
            runCatching { server.close() }
            thread.join(2_000)
        }
    }

    @Test
    fun `rewritten bodies discard validators and range metadata for the old bytes`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val body = "<a href=\"https://example.test/next\">next</a>"
        val thread = serveOnce(server) {
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html\r\n" +
                "ETag: \"upstream-etag\"\r\n" +
                "Content-MD5: deadbeef\r\n" +
                "Digest: sha-256=deadbeef\r\n" +
                "Content-Digest: sha-256=:deadbeef:\r\n" +
                "Repr-Digest: sha-256=:deadbeef:\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Content-Length: ${body.toByteArray(StandardCharsets.ISO_8859_1).size}\r\n" +
                "Connection: close\r\n\r\n" +
                body
        }
        val proxy = SystemHttpCompatibilityProxy(PppHttpCompatibilityConfig(requestTimeoutMillis = 2_000))

        try {
            val response = request(proxy, 2304, server.localPort, "/")
            assertTrue(response.contains("http://example.test/next"), response)
            assertTrue(!response.contains("ETag:", ignoreCase = true), response)
            assertTrue(!response.contains("Content-MD5:", ignoreCase = true), response)
            assertTrue(!response.contains("Digest:", ignoreCase = true), response)
            assertTrue(!response.contains("Content-Digest:", ignoreCase = true), response)
            assertTrue(!response.contains("Repr-Digest:", ignoreCase = true), response)
            assertTrue(!response.contains("Accept-Ranges:", ignoreCase = true), response)
        } finally {
            proxy.close()
            runCatching { server.close() }
            thread.join(2_000)
        }
    }

    @Test
    fun `partial text responses keep their original representation and content range`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val body = "https://example.test/next"
        val thread = serveOnce(server) {
            "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Range: bytes 0-${body.length - 1}/100\r\n" +
                "ETag: \"partial-etag\"\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Connection: close\r\n\r\n" +
                body
        }
        val proxy = SystemHttpCompatibilityProxy(PppHttpCompatibilityConfig(requestTimeoutMillis = 2_000))

        try {
            val response = request(proxy, 2305, server.localPort, "/")
            assertTrue(response.startsWith("HTTP/1.0 206 Partial Content\r\n"), response)
            assertTrue(response.contains("Content-Range: bytes 0-${body.length - 1}/100", ignoreCase = true), response)
            assertTrue(response.contains("ETag: \"partial-etag\"", ignoreCase = true), response)
            assertTrue(response.endsWith(body), response)
        } finally {
            proxy.close()
            runCatching { server.close() }
            thread.join(2_000)
        }
    }

    private fun request(proxy: SystemHttpCompatibilityProxy, peerPort: Int, hostPort: Int, path: String): String {
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = httpFlow(peerPort)
        proxy.connect(flow, events::offer)
        assertIs<TcpProxyEvent.Connected>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))
        proxy.send(
            flow,
            ("GET $path HTTP/1.0\r\nHost: 127.0.0.1:$hostPort\r\n\r\n").toByteArray(StandardCharsets.US_ASCII),
        ).getOrThrow()
        return collectResponse(events)
    }

    private fun collectResponse(events: LinkedBlockingQueue<TcpProxyEvent>): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            when (val event = requireNotNull(events.poll(3, TimeUnit.SECONDS))) {
                is TcpProxyEvent.Payload -> event.bytes.forEach(bytes::add)
                is TcpProxyEvent.EndOfStream -> break
                is TcpProxyEvent.WriteCompleted -> Unit
                is TcpProxyEvent.Failure -> throw AssertionError("proxy failed", event.error)
                is TcpProxyEvent.Connected -> Unit
            }
        }
        return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
    }

    private fun serveOnce(server: ServerSocket, response: (String) -> String): Thread =
        Thread {
            server.use { listening ->
                listening.accept().use { socket ->
                    val request = readRequest(socket)
                    writeResponse(socket, response(request))
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

    private fun writeResponse(socket: java.net.Socket, response: String) {
        socket.getOutputStream().write(response.toByteArray(StandardCharsets.ISO_8859_1))
        socket.getOutputStream().flush()
    }

    private fun readRequest(socket: java.net.Socket): String {
        val input = socket.getInputStream()
        val output = ByteArrayOutputStream()
        var matched = 0
        val delimiter = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        while (matched < delimiter.size) {
            val value = input.read()
            if (value < 0) break
            output.write(value)
            matched = if (value.toByte() == delimiter[matched]) matched + 1 else 0
        }
        return output.toString(StandardCharsets.ISO_8859_1)
    }

    private fun httpFlow(peerPort: Int): TcpProxyFlow =
        TcpProxyFlow(
            key = TcpFlowKey(
                peerAddress = Ipv4Address.parse("10.0.0.2"),
                peerPort = peerPort,
                remoteAddress = Ipv4Address.parse("127.0.0.1"),
                remotePort = 80,
            ),
            generation = 1,
        )
}
