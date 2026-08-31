package no.skasti.serialmodem.ppp

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpCompatibilityProxyTest {
    @Test
    fun `compatibility proxy follows redirect and returns only final plain HTTP response`() {
        val finalServer = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val redirectServer = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val finalRequest = LinkedBlockingQueue<String>()

        val finalThread = serveOnce(finalServer) { request ->
            finalRequest.offer(request)
            val body = "modern upstream over redirected request"
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" +
                body
        }
        val redirectThread = serveOnce(redirectServer) {
            "HTTP/1.1 302 Found\r\n" +
                "Location: http://127.0.0.1:${finalServer.localPort}/final\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
        }

        val proxy = SystemHttpCompatibilityProxy(
            config = PppHttpCompatibilityConfig(
                enabled = true,
                requestTimeoutMillis = 2_000,
            ),
        )
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = httpFlow(peerPort = 2101)

        try {
            proxy.connect(flow, events::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))

            val request =
                "GET /start HTTP/1.0\r\n" +
                    "Host: 127.0.0.1:${redirectServer.localPort}\r\n" +
                    "User-Agent: YAME-test\r\n\r\n"
            proxy.send(flow, request.toByteArray(StandardCharsets.US_ASCII)).getOrThrow()

            val response = collectResponse(events)
            assertTrue(response.startsWith("HTTP/1.0 200 OK\r\n"))
            assertTrue(response.contains("modern upstream over redirected request"))
            assertTrue(!response.contains("302 Found"))
            assertTrue(!response.contains("Location:"))
            assertTrue(requireNotNull(finalRequest.poll(2, TimeUnit.SECONDS)).startsWith("GET /final HTTP/1.1"))
        } finally {
            proxy.close()
            runCatching { redirectServer.close() }
            runCatching { finalServer.close() }
            redirectThread.join(2_000)
            finalThread.join(2_000)
        }
    }

    @Test
    fun `compatibility proxy enforces redirect limit with legacy HTTP error`() {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val serverThread = Thread {
            server.use { listening ->
                repeat(2) {
                    val socket = listening.accept()
                    socket.use { accepted ->
                        readRequest(accepted)
                        val response =
                            "HTTP/1.1 302 Found\r\n" +
                                "Location: http://127.0.0.1:${server.localPort}/again\r\n" +
                                "Content-Length: 0\r\n" +
                                "Connection: close\r\n\r\n"
                        accepted.getOutputStream().write(response.toByteArray(StandardCharsets.US_ASCII))
                        accepted.getOutputStream().flush()
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val proxy = SystemHttpCompatibilityProxy(
            config = PppHttpCompatibilityConfig(
                enabled = true,
                maxRedirects = 1,
                requestTimeoutMillis = 2_000,
            ),
        )
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = httpFlow(peerPort = 2102)

        try {
            proxy.connect(flow, events::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))
            proxy.send(
                flow,
                ("GET / HTTP/1.0\r\nHost: 127.0.0.1:${server.localPort}\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII),
            ).getOrThrow()

            val response = collectResponse(events)
            assertTrue(response.startsWith("HTTP/1.0 502 Bad Gateway\r\n"))
        } finally {
            proxy.close()
            runCatching { server.close() }
            serverThread.join(2_000)
        }
    }

    @Test
    fun `compatibility mode is enabled by default`() {
        assertTrue(PppHttpCompatibilityConfig().enabled)

        val direct = RecordingProxy()
        val compatibility = RecordingProxy()
        val proxy = SystemRoutingTcpProxy(
            directProxy = direct,
            httpProxy = compatibility,
        )
        val flow = httpFlow(peerPort = 2199)

        try {
            proxy.connect(flow) {}
            assertEquals(listOf(flow), compatibility.connected)
            assertTrue(direct.connected.isEmpty())
        } finally {
            proxy.close()
        }
    }

    @Test
    fun `compatibility proxy rewrites HTTPS references for legacy HTML navigation`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val originalBody =
            "<html><a href=\"https://example.test/next\">next</a>" +
                "<form action=\"HTTPS://example.test/post\"></form></html>"
        val rewrittenBody =
            "<html><a href=\"http://example.test/next\">next</a>" +
                "<form action=\"http://example.test/post\"></form></html>"
        val thread = serveOnce(server) {
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Refresh: 5; url=https://example.test/later\r\n" +
                "Content-Length: ${originalBody.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n" +
                originalBody
        }
        val proxy = SystemHttpCompatibilityProxy(
            config = PppHttpCompatibilityConfig(requestTimeoutMillis = 2_000),
        )
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = httpFlow(peerPort = 2198)

        try {
            proxy.connect(flow, events::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))
            proxy.send(
                flow,
                ("GET / HTTP/1.0\r\nHost: 127.0.0.1:${server.localPort}\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII),
            ).getOrThrow()

            val response = collectResponse(events)
            assertTrue(
                response.contains("refresh: 5; url=http://example.test/later\r\n", ignoreCase = true),
                response,
            )
            assertTrue(response.contains(rewrittenBody), response)
            assertTrue(!response.contains("https://example.test/next"), response)
            assertTrue(!response.contains("HTTPS://example.test/post"), response)
            assertTrue(response.contains("http://example.test/next"), response)
            assertTrue(!response.contains(".yame/https"), response)
            assertTrue(
                response.contains(
                    "Content-Length: ${rewrittenBody.toByteArray(StandardCharsets.UTF_8).size}\r\n",
                ),
                response,
            )
        } finally {
            proxy.close()
            runCatching { server.close() }
            thread.join(2_000)
        }
    }

    @Test
    fun `session route preserves hidden origin for root-relative requests across TCP flows`() {
        val originServer = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val upstreamServer = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
        val upstreamRequests = LinkedBlockingQueue<String>()

        val upstreamThread = Thread {
            upstreamServer.use { listening ->
                repeat(2) { requestIndex ->
                    val socket = listening.accept()
                    socket.use { accepted ->
                        val request = readRequest(accepted)
                        upstreamRequests.offer(request)
                        val body = if (requestIndex == 0) {
                            "<html><img src=\"/asset.gif\"></html>"
                        } else {
                            "asset"
                        }
                        val response =
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: " + (if (requestIndex == 0) "text/html" else "image/gif") + "\r\n" +
                                (if (requestIndex == 0) "Set-Cookie: upstream=session; Path=/\r\n" else "") +
                                "Content-Length: ${body.toByteArray(StandardCharsets.ISO_8859_1).size}\r\n" +
                                "Connection: close\r\n\r\n" +
                                body
                        accepted.getOutputStream().write(response.toByteArray(StandardCharsets.ISO_8859_1))
                        accepted.getOutputStream().flush()
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val originThread = serveOnce(originServer) {
            "HTTP/1.1 302 Found\r\n" +
                "Location: http://127.0.0.1:${upstreamServer.localPort}/page\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
        }

        val proxy = SystemHttpCompatibilityProxy(
            config = PppHttpCompatibilityConfig(requestTimeoutMillis = 2_000),
        )
        val pageEvents = LinkedBlockingQueue<TcpProxyEvent>()
        val assetEvents = LinkedBlockingQueue<TcpProxyEvent>()
        val pageFlow = httpFlow(peerPort = 2196)
        val assetFlow = httpFlow(peerPort = 2195)

        try {
            proxy.connect(pageFlow, pageEvents::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(pageEvents.poll(2, TimeUnit.SECONDS)))
            proxy.send(
                pageFlow,
                ("GET /start HTTP/1.0\r\nHost: 127.0.0.1:${originServer.localPort}\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII),
            ).getOrThrow()
            val pageResponse = collectResponse(pageEvents)
            assertTrue(pageResponse.contains("<img src=\"/asset.gif\">"), pageResponse)

            proxy.connect(assetFlow, assetEvents::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(assetEvents.poll(2, TimeUnit.SECONDS)))
            proxy.send(
                assetFlow,
                (
                    "GET /asset.gif HTTP/1.0\r\n" +
                        "Host: 127.0.0.1:${originServer.localPort}\r\n" +
                        "Authorization: Basic bGVnYWN5OnNlY3JldA==\r\n" +
                        "Cookie: legacy=session\r\n\r\n"
                    )
                    .toByteArray(StandardCharsets.US_ASCII),
            ).getOrThrow()
            val assetResponse = collectResponse(assetEvents)
            assertTrue(assetResponse.endsWith("asset"), assetResponse)

            assertTrue(requireNotNull(upstreamRequests.poll(2, TimeUnit.SECONDS)).startsWith("GET /page HTTP/1.1"))
            val mappedAssetRequest = requireNotNull(upstreamRequests.poll(2, TimeUnit.SECONDS))
            assertTrue(mappedAssetRequest.startsWith("GET /asset.gif HTTP/1.1"))
            assertTrue(!mappedAssetRequest.contains("Authorization:", ignoreCase = true), mappedAssetRequest)
            assertTrue(!mappedAssetRequest.contains("legacy=session", ignoreCase = true), mappedAssetRequest)
            assertTrue(mappedAssetRequest.contains("Cookie: upstream=session", ignoreCase = true), mappedAssetRequest)
        } finally {
            proxy.close()
            runCatching { originServer.close() }
            runCatching { upstreamServer.close() }
            originThread.join(2_000)
            upstreamThread.join(2_000)
        }
    }

    @Test
    fun `compatibility proxy does not rewrite HTTPS byte sequences in binary responses`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val body = "binary-prefix-https://example.test/resource-binary-suffix"
        val thread = serveOnce(server) {
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: ${body.toByteArray(StandardCharsets.ISO_8859_1).size}\r\n" +
                "Connection: close\r\n\r\n" +
                body
        }
        val proxy = SystemHttpCompatibilityProxy(
            config = PppHttpCompatibilityConfig(
                requestTimeoutMillis = 2_000,
                maxResponseBytes = 16,
            ),
        )
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = httpFlow(peerPort = 2197)

        try {
            proxy.connect(flow, events::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))
            proxy.send(
                flow,
                ("GET /file.bin HTTP/1.0\r\nHost: 127.0.0.1:${server.localPort}\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII),
            ).getOrThrow()

            val response = collectResponse(events)
            assertTrue(response.endsWith(body), response)
            assertTrue(response.contains("https://example.test/resource"), response)
        } finally {
            proxy.close()
            runCatching { server.close() }
            thread.join(2_000)
        }
    }

    @Test
    fun `partial streamed response closes without appending a second HTTP response`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val thread = Thread {
            server.use { listening ->
                listening.accept().use { socket ->
                    readRequest(socket)
                    socket.getOutputStream().write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/octet-stream\r\n" +
                                "Content-Length: 100\r\n" +
                                "Connection: close\r\n\r\n" +
                                "partial"
                            ).toByteArray(StandardCharsets.ISO_8859_1),
                    )
                    socket.getOutputStream().flush()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val proxy = SystemHttpCompatibilityProxy(
            config = PppHttpCompatibilityConfig(requestTimeoutMillis = 2_000),
        )
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = httpFlow(peerPort = 2193)

        try {
            proxy.connect(flow, events::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))
            proxy.send(
                flow,
                ("GET /file.bin HTTP/1.0\r\nHost: 127.0.0.1:${server.localPort}\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII),
            ).getOrThrow()

            val response = collectResponse(events)
            assertTrue(response.startsWith("HTTP/1.0 200 OK\r\n"), response)
            assertEquals(1, Regex("HTTP/1\\.0 ").findAll(response).count(), response)
            assertTrue(!response.contains("502 Bad Gateway"), response)
        } finally {
            proxy.close()
            runCatching { server.close() }
            thread.join(2_000)
        }
    }

    @Test
    fun `compatibility proxy keeps rewrite cap for navigable text responses`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val body = "<html><a href=\"https://example.test/path\">link</a></html>"
        val thread = serveOnce(server) {
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: ${body.toByteArray(StandardCharsets.ISO_8859_1).size}\r\n" +
                "Connection: close\r\n\r\n" +
                body
        }
        val proxy = SystemHttpCompatibilityProxy(
            config = PppHttpCompatibilityConfig(
                requestTimeoutMillis = 2_000,
                maxResponseBytes = 16,
            ),
        )
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = httpFlow(peerPort = 2194)

        try {
            proxy.connect(flow, events::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))
            proxy.send(
                flow,
                ("GET /page HTTP/1.0\r\nHost: 127.0.0.1:${server.localPort}\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII),
            ).getOrThrow()

            val response = collectResponse(events)
            assertTrue(response.startsWith("HTTP/1.0 502 Bad Gateway\r\n"), response)
        } finally {
            proxy.close()
            runCatching { server.close() }
            thread.join(2_000)
        }
    }

    @Test
    fun `routing proxy intercepts only port 80 when compatibility mode is enabled`() {
        val direct = RecordingProxy()
        val compatibility = RecordingProxy()
        val proxy = SystemRoutingTcpProxy(
            httpConfig = PppHttpCompatibilityConfig(enabled = true),
            directProxy = direct,
            httpProxy = compatibility,
        )
        val httpFlow = httpFlow(peerPort = 2201)
        val httpsFlow = httpFlow(peerPort = 2202, remotePort = 443)

        try {
            proxy.connect(httpFlow) {}
            proxy.connect(httpsFlow) {}

            assertEquals(listOf(httpFlow), compatibility.connected)
            assertEquals(listOf(httpsFlow), direct.connected)
        } finally {
            proxy.close()
        }
    }

    @Test
    fun `routing proxy leaves port 80 transparent when compatibility mode is disabled`() {
        val direct = RecordingProxy()
        val compatibility = RecordingProxy()
        val proxy = SystemRoutingTcpProxy(
            httpConfig = PppHttpCompatibilityConfig(enabled = false),
            directProxy = direct,
            httpProxy = compatibility,
        )
        val flow = httpFlow(peerPort = 2203)

        try {
            proxy.connect(flow) {}
            assertEquals(listOf(flow), direct.connected)
            assertTrue(compatibility.connected.isEmpty())
        } finally {
            proxy.close()
        }
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
                val socket = listening.accept()
                socket.use { accepted ->
                    val request = readRequest(accepted)
                    val wire = response(request).toByteArray(StandardCharsets.ISO_8859_1)
                    accepted.getOutputStream().write(wire)
                    accepted.getOutputStream().flush()
                }
            }
        }.apply {
            isDaemon = true
            start()
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

    private fun httpFlow(peerPort: Int, remotePort: Int = 80): TcpProxyFlow =
        TcpProxyFlow(
            key = TcpFlowKey(
                peerAddress = Ipv4Address.parse("10.0.0.2"),
                peerPort = peerPort,
                remoteAddress = Ipv4Address.parse("127.0.0.1"),
                remotePort = remotePort,
            ),
            generation = 1,
        )

    private class RecordingProxy : TcpProxy {
        val connected = mutableListOf<TcpProxyFlow>()

        override fun connect(flow: TcpProxyFlow, onEvent: (TcpProxyEvent) -> Unit) {
            connected += flow
        }

        override fun send(flow: TcpProxyFlow, payload: ByteArray): Result<Unit> = Result.success(Unit)
        override fun shutdownOutput(flow: TcpProxyFlow): Result<Unit> = Result.success(Unit)
        override fun closeFlow(flow: TcpProxyFlow) = Unit
    }
}
