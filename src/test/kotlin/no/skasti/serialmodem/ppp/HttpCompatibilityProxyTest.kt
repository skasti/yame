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
    fun `compatibility proxy exposes a visible redirect and serves the followed URL`() {
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
        val firstFlow = httpFlow(peerPort = 2101)
        val followedFlow = httpFlow(peerPort = 2103)

        try {
            proxy.connect(firstFlow, events::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))

            val request =
                "GET /start HTTP/1.0\r\n" +
                    "Host: 127.0.0.1:${redirectServer.localPort}\r\n" +
                    "User-Agent: YAME-test\r\n\r\n"
            proxy.send(firstFlow, request.toByteArray(StandardCharsets.US_ASCII)).getOrThrow()

            val redirect = collectResponse(events)
            assertTrue(redirect.startsWith("HTTP/1.0 302 Found\r\n"), redirect)
            assertTrue(
                redirect.contains("location: http://127.0.0.1:${finalServer.localPort}/final", ignoreCase = true),
                redirect,
            )

            proxy.connect(followedFlow, events::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))
            proxy.send(
                followedFlow,
                (
                    "GET /final HTTP/1.0\r\n" +
                        "Host: 127.0.0.1:${finalServer.localPort}\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII),
            ).getOrThrow()

            val response = collectResponse(events)
            assertTrue(response.startsWith("HTTP/1.0 200 OK\r\n"), response)
            assertTrue(response.contains("modern upstream over redirected request"), response)
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
    fun `generated HTTP location keeps its hidden HTTPS route`() {
        val tlsProbe = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val redirectServer = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val firstUpstreamByte = LinkedBlockingQueue<Int>()

        val tlsProbeThread = Thread {
            runCatching {
                tlsProbe.accept().use { accepted ->
                    firstUpstreamByte.offer(accepted.getInputStream().read())
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val redirectThread = serveOnce(redirectServer) {
            "HTTP/1.1 302 Found\r\n" +
                "Location: https://127.0.0.1:${tlsProbe.localPort}/final\r\n" +
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
        val firstFlow = httpFlow(peerPort = 2111)
        val followedFlow = httpFlow(peerPort = 2112)

        try {
            proxy.connect(firstFlow, events::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))
            proxy.send(
                firstFlow,
                (
                    "GET /start HTTP/1.0\r\n" +
                        "Host: 127.0.0.1:${redirectServer.localPort}\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII),
            ).getOrThrow()

            val redirect = collectResponse(events)
            assertTrue(
                redirect.contains("location: http://127.0.0.1/final", ignoreCase = true),
                redirect,
            )

            proxy.connect(followedFlow, events::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))
            proxy.send(
                followedFlow,
                "GET /final HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(StandardCharsets.US_ASCII),
            ).getOrThrow()

            assertEquals(0x16, firstUpstreamByte.poll(2, TimeUnit.SECONDS))
        } finally {
            proxy.close()
            runCatching { redirectServer.close() }
            runCatching { tlsProbe.close() }
            redirectThread.join(2_000)
            tlsProbeThread.join(2_000)
        }
    }

    @Test
    fun `fragment-only redirect is returned to the browser`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val thread = serveOnce(server) {
            "HTTP/1.1 302 Found\r\n" +
                "Location: #section\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
        }
        val proxy = SystemHttpCompatibilityProxy(
            config = PppHttpCompatibilityConfig(requestTimeoutMillis = 2_000),
        )
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = httpFlow(peerPort = 2113)

        try {
            proxy.connect(flow, events::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))
            proxy.send(
                flow,
                (
                    "GET /page HTTP/1.0\r\n" +
                        "Host: 127.0.0.1:${server.localPort}\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII),
            ).getOrThrow()

            val redirect = collectResponse(events)
            assertTrue(redirect.startsWith("HTTP/1.0 302 Found\r\n"), redirect)
            assertTrue(
                redirect.contains(
                    "location: http://127.0.0.1:${server.localPort}/page#section",
                    ignoreCase = true,
                ),
                redirect,
            )
        } finally {
            proxy.close()
            runCatching { server.close() }
            thread.join(2_000)
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
                                "Location: http://127.0.0.1:${server.localPort}/\r\n" +
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
            "<html><head><meta http-equiv=\"refresh\" content=\"0;url=https://example.test/meta\"></head>" +
                "<a href=\"https://example.test/next\">next</a>" +
                "<a href=https://example.test/unquoted>legacy</a>" +
                "<form action=\"HTTPS://example.test/post\"></form></html>"
        val rewrittenBody =
            "<html><head><meta http-equiv=\"refresh\" content=\"0;url=http://example.test/meta\"></head>" +
                "<a href=\"http://example.test/next\">next</a>" +
                "<a href=http://example.test/unquoted>legacy</a>" +
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
            assertTrue(!response.contains("https://example.test/unquoted"), response)
            assertTrue(!response.contains("https://example.test/meta"), response)
            assertTrue(response.contains("http://example.test/next"), response)
            assertTrue(response.contains("href=http://example.test/unquoted"), response)
            assertTrue(response.contains("content=\"0;url=http://example.test/meta\""), response)
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
    fun `geocities fixture keeps relative URLs on the document origin while rewriting HTTPS scripts`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val originalBody = """
            <html><head>
            <meta http-equiv="Content-Language" content="en-us">
            <meta http-equiv="Content-Type" content="text/html; charset=windows-1252">
            <title>Oldternet Links</title><link rel="shortcut icon" href="oldnet.ico" type="image/x-icon">
            </head>
            <body bgcolor="#C0C0C0"><!-- Google tag (gtag.js) -->
            <script async="" src="https://www.googletagmanager.com/gtag/js?id=G-4KX380T5BD"></script>
            <a href="index.htm"><img src="home.gif" border="0"></a>
            <img border="0" src="rbow_div.gif" width="600" height="1">
            <a href="https://baloo.neocities.org/">Baloo's Revue</a>
            <script type="module" src="https://static.cloudflareinsights.com/beacon.min.js/v3d52b47920f24c319d37e2661827c42b1787588026925"></script>
            </body></html>
        """.trimIndent()
        val thread = serveOnce(server) {
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=windows-1252\r\n" +
                "Content-Length: ${originalBody.toByteArray(java.nio.charset.Charset.forName("windows-1252")).size}\r\n" +
                "Connection: close\r\n\r\n" +
                originalBody
        }
        val proxy = SystemHttpCompatibilityProxy(
            config = PppHttpCompatibilityConfig(requestTimeoutMillis = 2_000),
        )
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = httpFlow(peerPort = 2196)

        try {
            proxy.connect(flow, events::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))
            proxy.send(
                flow,
                ("GET /oldternet/links.htm HTTP/1.0\r\nHost: 127.0.0.1:${server.localPort}\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII),
            ).getOrThrow()

            val response = collectResponse(events)
            assertTrue(
                response.contains("src=\"http://www.googletagmanager.com/gtag/js?id=G-4KX380T5BD\""),
                response,
            )
            assertTrue(
                response.contains("src=\"http://static.cloudflareinsights.com/beacon.min.js/"),
                response,
            )
            assertTrue(response.contains("href=\"index.htm\""), response)
            assertTrue(response.contains("src=\"home.gif\""), response)
            assertTrue(response.contains("src=\"rbow_div.gif\""), response)
            assertTrue(
                response.contains("href=\"http://baloo.neocities.org/\""),
                response,
            )
            assertTrue(!response.contains("https://www.googletagmanager.com/"), response)
        } finally {
            proxy.close()
            runCatching { server.close() }
            thread.join(2_000)
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
