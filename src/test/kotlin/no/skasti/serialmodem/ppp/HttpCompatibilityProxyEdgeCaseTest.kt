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
        val proxy = SystemHttpCompatibilityProxy(
            PppHttpCompatibilityConfig(enabled = true, maxFlows = 1, requestTimeoutMillis = 2_000),
        )
        val firstEvents = LinkedBlockingQueue<TcpProxyEvent>()
        val first = flow(2301)
        try {
            proxy.connect(first, firstEvents::offer)
            assertIs<TcpProxyEvent.Connected>(firstEvents.poll(2, TimeUnit.SECONDS))
            proxy.send(first, "GET / HTTP/1.0\r\nHost: example.test".toByteArray()).getOrThrow()
            proxy.shutdownOutput(first).getOrThrow()

            val response = collectResponse(firstEvents)
            assertTrue(response.startsWith("HTTP/1.0 400 Bad Request\r\n"), response)

            val secondEvents = LinkedBlockingQueue<TcpProxyEvent>()
            proxy.connect(flow(2302), secondEvents::offer)
            assertIs<TcpProxyEvent.Connected>(secondEvents.poll(2, TimeUnit.SECONDS))
        } finally {
            proxy.close()
        }
    }

    @Test
    fun `expect 100 continue is acknowledged before body arrives`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val thread = serveOnce(server) {
            val body = "ok"
            "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        }
        val proxy = SystemHttpCompatibilityProxy(
            PppHttpCompatibilityConfig(enabled = true, requestTimeoutMillis = 2_000),
        )
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = flow(2303)
        try {
            proxy.connect(flow, events::offer)
            assertIs<TcpProxyEvent.Connected>(events.poll(2, TimeUnit.SECONDS))
            val headers =
                "POST / HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:${server.localPort}\r\n" +
                    "Content-Length: 4\r\n" +
                    "Expect: 100-continue\r\n\r\n"
            proxy.send(flow, headers.toByteArray(StandardCharsets.US_ASCII)).getOrThrow()

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

            proxy.send(flow, "data".toByteArray(StandardCharsets.US_ASCII)).getOrThrow()
            assertTrue(collectResponse(events).contains("ok"))
        } finally {
            proxy.close()
            runCatching { server.close() }
            thread.join(2_000)
        }
    }

    @Test
    fun `hidden redirects retain scoped cookies`() {
        val server = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
        val finalRequest = LinkedBlockingQueue<String>()
        val thread = Thread {
            server.use { listening ->
                listening.accept().use { socket ->
                    readRequest(socket)
                    val response =
                        "HTTP/1.1 302 Found\r\n" +
                            "Location: /final\r\n" +
                            "Set-Cookie: session=yame; Path=/; HttpOnly\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(response.toByteArray(StandardCharsets.US_ASCII))
                    socket.getOutputStream().flush()
                }
                listening.accept().use { socket ->
                    finalRequest.offer(readRequest(socket))
                    val body = "cookie-ok"
                    val response =
                        "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    socket.getOutputStream().write(response.toByteArray(StandardCharsets.US_ASCII))
                    socket.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }

        val proxy = SystemHttpCompatibilityProxy(
            PppHttpCompatibilityConfig(enabled = true, requestTimeoutMillis = 2_000),
        )
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = flow(2304)
        try {
            proxy.connect(flow, events::offer)
            assertIs<TcpProxyEvent.Connected>(events.poll(2, TimeUnit.SECONDS))
            val request = "GET /start HTTP/1.0\r\nHost: 127.0.0.1:${server.localPort}\r\n\r\n"
            proxy.send(flow, request.toByteArray(StandardCharsets.US_ASCII)).getOrThrow()
            assertTrue(collectResponse(events).contains("cookie-ok"))
            val redirected = requireNotNull(finalRequest.poll(2, TimeUnit.SECONDS))
            assertTrue(redirected.contains("Cookie: session=yame", ignoreCase = true), redirected)
        } finally {
            proxy.close()
            runCatching { server.close() }
            thread.join(2_000)
        }
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
        server.use { listening ->
            listening.accept().use { socket ->
                val request = readRequest(socket)
                val wire = response(request).toByteArray(StandardCharsets.ISO_8859_1)
                socket.getOutputStream().write(wire)
                socket.getOutputStream().flush()
            }
        }
    }.apply { isDaemon = true; start() }

    private fun readRequest(socket: java.net.Socket): String {
        val input = socket.getInputStream()
        val output = ByteArrayOutputStream()
        val delimiter = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        var matched = 0
        while (matched < delimiter.size) {
            val value = input.read()
            if (value < 0) break
            output.write(value)
            matched = if (value.toByte() == delimiter[matched]) matched + 1 else 0
        }
        return output.toString(StandardCharsets.ISO_8859_1)
    }

    private fun flow(peerPort: Int) = TcpProxyFlow(
        key = TcpFlowKey(
            peerAddress = Ipv4Address.parse("10.0.0.2"),
            peerPort = peerPort,
            remoteAddress = Ipv4Address.parse("127.0.0.1"),
            remotePort = 80,
        ),
        generation = 1,
    )
}
