package no.skasti.serialmodem.ppp.http

import no.skasti.serialmodem.ppp.ip.Ipv4Address
import no.skasti.serialmodem.ppp.tcp.TcpFlowKey
import no.skasti.serialmodem.ppp.tcp.TcpProxyEvent
import no.skasti.serialmodem.ppp.tcp.TcpProxyFlow
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpTcpProxyTest {
    @Test
    fun `adapts TCP payloads into an HTTP request and response`() {
        val requests = LinkedBlockingQueue<HttpRequest>()
        val handler =
            object : HttpRequestHandler {
                override fun handle(
                    connection: HttpConnection,
                    request: HttpRequest,
                ): HttpResponse {
                    requests.offer(request)
                    return HttpResponse(
                        statusCode = 200,
                        reasonPhrase = "OK",
                        headers = listOf("Content-Type" to "text/plain"),
                        body = "hello".toByteArray(StandardCharsets.US_ASCII),
                    )
                }
            }
        val proxy = HttpTcpProxy(handler, maxFlows = 1, maxRequestBytes = 1024, responseChunkBytes = 2)
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = flow()

        try {
            proxy.connect(flow, events::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))

            proxy.send(
                flow,
                "GET /demo HTTP/1.0\r\nHost: example.test\r\n\r\n"
                    .toByteArray(StandardCharsets.US_ASCII),
            ).getOrThrow()

            assertIs<TcpProxyEvent.WriteCompleted>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))
            val request = requireNotNull(requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/demo", request.target)

            val response = collectResponse(events)
            assertTrue(response.startsWith("HTTP/1.0 200 OK\r\n"), response)
            assertTrue(response.contains("Content-Length: 5\r\n"), response)
            assertTrue(response.endsWith("hello"), response)
        } finally {
            proxy.close()
        }
    }

    @Test
    fun `preserves explicit content length for HEAD style responses`() {
        val handler =
            object : HttpRequestHandler {
                override fun handle(
                    connection: HttpConnection,
                    request: HttpRequest,
                ): HttpResponse =
                    HttpResponse(
                        statusCode = 200,
                        reasonPhrase = "OK",
                        headers = listOf("Content-Length" to "123"),
                        body = ByteArray(0),
                    )
            }
        val proxy = HttpTcpProxy(handler, maxFlows = 1, maxRequestBytes = 1024)
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = flow()

        try {
            proxy.connect(flow, events::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))
            proxy.send(
                flow,
                "HEAD /demo HTTP/1.0\r\nHost: example.test\r\n\r\n"
                    .toByteArray(StandardCharsets.US_ASCII),
            ).getOrThrow()
            assertIs<TcpProxyEvent.WriteCompleted>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))

            val response = collectResponse(events)
            assertTrue(response.contains("Content-Length: 123\r\n"), response)
        } finally {
            proxy.close()
        }
    }

    private fun collectResponse(events: LinkedBlockingQueue<TcpProxyEvent>): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            when (val event = requireNotNull(events.poll(2, TimeUnit.SECONDS))) {
                is TcpProxyEvent.Payload -> event.bytes.forEach(bytes::add)
                TcpProxyEvent.EndOfStream -> break
                is TcpProxyEvent.Failure -> throw AssertionError("HTTP TCP adapter failed", event.error)
                TcpProxyEvent.Connected -> Unit
                is TcpProxyEvent.WriteCompleted -> Unit
            }
        }
        return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
    }

    private fun flow(): TcpProxyFlow =
        TcpProxyFlow(
            key =
                TcpFlowKey(
                    peerAddress = Ipv4Address.parse("10.0.0.2"),
                    peerPort = 1234,
                    remoteAddress = Ipv4Address.parse("203.0.113.1"),
                    remotePort = 80,
                ),
            generation = 1,
        )
}
