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

class HttpCompatibilityProxyEdgeCaseTest {
    @Test
    fun `half close with incomplete request returns error and releases flow slot`() {
        val proxy = proxy(maxFlows = 1)
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        try {
            val first = flow(2301)
            proxy.connect(first, events::offer)
            assertIs<TcpProxyEvent.Connected>(events.poll(2, TimeUnit.SECONDS))
            proxy.send(first, "GET / HTTP/1.0\r\nHost: example.test".toByteArray()).getOrThrow()
            proxy.shutdownOutput(first).getOrThrow()
            assertTrue(collectResponse(events).startsWith("HTTP/1.0 400 Bad Request\r\n"))
            assertCanConnect(proxy, 2302)
        } finally { proxy.close() }
    }

    @Test
    fun `empty half close returns EOF and releases flow slot`() {
        val proxy = proxy(maxFlows = 1)
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        try {
            val first = flow(2303)
            proxy.connect(first, events::offer)
            assertIs<TcpProxyEvent.Connected>(events.poll(2, TimeUnit.SECONDS))
            proxy.shutdownOutput(first).getOrThrow()
            assertIs<TcpProxyEvent.EndOfStream>(events.poll(2, TimeUnit.SECONDS))
            assertCanConnect(proxy, 2304)
        } finally { proxy.close() }
    }

    @Test
    fun `closing immediately after complete request does not leak flow slot`() {
        val server = ServerSocket(0, 32, InetAddress.getLoopbackAddress())
        val serverThread = Thread {
            server.use { listening ->
                while (!listening.isClosed) {
                    runCatching {
                        listening.accept().use { socket ->
                            readRequest(socket)
                            Thread.sleep(25)
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                    }.onFailure { if (!listening.isClosed) throw it }
                }
            }
        }.apply { isDaemon = true; start() }
        val proxy = proxy(maxFlows = 1)
        try {
            repeat(20) { index ->
                val events = LinkedBlockingQueue<TcpProxyEvent>()
                val current = flow(2400 + index)
                proxy.connect(current, events::offer)
                assertIs<TcpProxyEvent.Connected>(events.poll(2, TimeUnit.SECONDS))
                proxy.send(current, "GET / HTTP/1.0\r\nHost: 127.0.0.1:${server.localPort}\r\n\r\n".toByteArray()).getOrThrow()
                proxy.closeFlow(current)
            }
            assertCanConnect(proxy, 2499)
        } finally {
            proxy.close(); runCatching { server.close() }; serverThread.join(2_000)
        }
    }

    @Test
    fun `expect 100 continue is acknowledged before body arrives`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val thread = serveOnce(server) { "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok" }
        val proxy = proxy()
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val current = flow(2501)
        try {
            proxy.connect(current, events::offer)
            assertIs<TcpProxyEvent.Connected>(events.poll(2, TimeUnit.SECONDS))
            val headers = "POST / HTTP/1.1\r\nHost: 127.0.0.1:${server.localPort}\r\nContent-Length: 4\r\nExpect: 100-continue\r\n\r\n"
            proxy.send(current, headers.toByteArray()).getOrThrow()
            var interim = ""
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!interim.contains("100 Continue") && System.nanoTime() < deadline) {
                when (val event = events.poll(100, TimeUnit.MILLISECONDS)) {
                    is TcpProxyEvent.Payload -> interim += event.bytes.toString(StandardCharsets.ISO_8859_1)
                    is TcpProxyEvent.Failure -> throw AssertionError("proxy failed", event.error)
                    else -> Unit
                }
            }
            assertTrue(interim.contains("HTTP/1.1 100 Continue\r\n\r\n"), interim)
            proxy.send(current, "data".toByteArray()).getOrThrow()
            assertTrue(collectResponse(events).contains("ok"))
        } finally { proxy.close(); runCatching { server.close() }; thread.join(2_000) }
    }

    @Test
    fun `hidden redirect can replace client cookie across paths without replaying stale value`() {
        val server = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
        val finalRequest = LinkedBlockingQueue<String>()
        val thread = Thread {
            server.use { listening ->
                listening.accept().use { socket ->
                    readRequest(socket)
                    socket.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: /dashboard\r\nSet-Cookie: session=new; Path=/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                }
                listening.accept().use { socket ->
                    finalRequest.offer(readRequest(socket))
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                }
            }
        }.apply { isDaemon = true; start() }
        val proxy = proxy()
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val current = flow(2502)
        try {
            proxy.connect(current, events::offer)
            assertIs<TcpProxyEvent.Connected>(events.poll(2, TimeUnit.SECONDS))
            proxy.send(current, "GET /login/start HTTP/1.0\r\nHost: 127.0.0.1:${server.localPort}\r\nCookie: session=old\r\n\r\n".toByteArray()).getOrThrow()
            assertTrue(collectResponse(events).contains("ok"))
            val redirected = requireNotNull(finalRequest.poll(2, TimeUnit.SECONDS))
            assertTrue(redirected.contains("session=new"), redirected)
            assertTrue(!redirected.contains("session=old"), redirected)
        } finally { proxy.close(); runCatching { server.close() }; thread.join(2_000) }
    }

    @Test
    fun `304 preserves upstream content length without a body`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val thread = serveOnce(server) { "HTTP/1.1 304 Not Modified\r\nContent-Length: 1234\r\nConnection: close\r\n\r\n" }
        val proxy = proxy()
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val current = flow(2503)
        try {
            proxy.connect(current, events::offer)
            assertIs<TcpProxyEvent.Connected>(events.poll(2, TimeUnit.SECONDS))
            proxy.send(current, "GET / HTTP/1.0\r\nHost: 127.0.0.1:${server.localPort}\r\nIf-None-Match: test\r\n\r\n".toByteArray()).getOrThrow()
            val response = collectResponse(events)
            assertTrue(response.startsWith("HTTP/1.0 304 Not Modified\r\n"), response)
            assertTrue(response.contains("Content-Length: 1234\r\n"), response)
            assertTrue(response.endsWith("\r\n\r\n"), response)
        } finally { proxy.close(); runCatching { server.close() }; thread.join(2_000) }
    }

    private fun proxy(maxFlows: Int = 16) = SystemHttpCompatibilityProxy(
        PppHttpCompatibilityConfig(enabled = true, maxFlows = maxFlows, requestTimeoutMillis = 2_000),
    )

    private fun assertCanConnect(proxy: SystemHttpCompatibilityProxy, port: Int) {
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        proxy.connect(flow(port), events::offer)
        assertIs<TcpProxyEvent.Connected>(events.poll(2, TimeUnit.SECONDS))
    }

    private fun collectResponse(events: LinkedBlockingQueue<TcpProxyEvent>): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            when (val event = requireNotNull(events.poll(3, TimeUnit.SECONDS))) {
                is TcpProxyEvent.Payload -> event.bytes.forEach(bytes::add)
                is TcpProxyEvent.EndOfStream -> break
                is TcpProxyEvent.WriteCompleted, is TcpProxyEvent.Connected -> Unit
                is TcpProxyEvent.Failure -> throw AssertionError("proxy failed", event.error)
            }
        }
        return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
    }

    private fun serveOnce(server: ServerSocket, response: (String) -> String): Thread = Thread {
        server.use { listening -> listening.accept().use { socket ->
            val request = readRequest(socket)
            socket.getOutputStream().write(response(request).toByteArray(StandardCharsets.ISO_8859_1))
            socket.getOutputStream().flush()
        } }
    }.apply { isDaemon = true; start() }

    private fun readRequest(socket: java.net.Socket): String {
        val input = socket.getInputStream(); val output = ByteArrayOutputStream(); val delimiter = "\r\n\r\n".toByteArray(); var matched = 0
        while (matched < delimiter.size) {
            val value = input.read(); if (value < 0) break; output.write(value)
            matched = if (value.toByte() == delimiter[matched]) matched + 1 else 0
        }
        return output.toString(StandardCharsets.ISO_8859_1)
    }

    private fun flow(peerPort: Int) = TcpProxyFlow(
        TcpFlowKey(Ipv4Address.parse("10.0.0.2"), peerPort, Ipv4Address.parse("127.0.0.1"), 80),
        generation = 1,
    )
}
