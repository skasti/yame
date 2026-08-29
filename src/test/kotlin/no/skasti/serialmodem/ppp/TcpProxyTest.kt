package no.skasti.serialmodem.ppp

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TcpProxyTest {
    @Test
    fun `system proxy connects exchanges bytes and reports host EOF`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val serverReceived = LinkedBlockingQueue<ByteArray>()
        val serverThread = Thread {
            server.use {
                val socket = it.accept()
                socket.use { accepted ->
                    val request = accepted.getInputStream().readNBytes(4)
                    serverReceived.offer(request)
                    accepted.getOutputStream().write("pong".encodeToByteArray())
                    accepted.getOutputStream().flush()
                    accepted.shutdownOutput()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val proxy = SystemTcpProxy(connectTimeoutMillis = 1_000)
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = TcpProxyFlow(
            key = TcpFlowKey(
                peerAddress = Ipv4Address.parse("10.0.0.2"),
                peerPort = 1025,
                remoteAddress = Ipv4Address.parse("127.0.0.1"),
                remotePort = server.localPort,
            ),
            generation = 1,
        )

        try {
            proxy.connect(flow, events::offer)
            assertIs<TcpProxyEvent.Connected>(requireNotNull(events.poll(2, TimeUnit.SECONDS)))

            proxy.send(flow, "ping".encodeToByteArray()).getOrThrow()
            assertContentEquals(
                "ping".encodeToByteArray(),
                requireNotNull(serverReceived.poll(2, TimeUnit.SECONDS)),
            )

            val payload = assertIs<TcpProxyEvent.Payload>(
                requireNotNull(events.poll(2, TimeUnit.SECONDS)),
            )
            assertContentEquals("pong".encodeToByteArray(), payload.bytes)
            assertIs<TcpProxyEvent.EndOfStream>(
                requireNotNull(events.poll(2, TimeUnit.SECONDS)),
            )
        } finally {
            proxy.close()
            runCatching { server.close() }
            serverThread.join(2_000)
        }
    }

    @Test
    fun `system proxy reports connection refusal without creating a flow`() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val proxy = SystemTcpProxy(connectTimeoutMillis = 1_000)
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = TcpProxyFlow(
            key = TcpFlowKey(
                peerAddress = Ipv4Address.parse("10.0.0.2"),
                peerPort = 1026,
                remoteAddress = Ipv4Address.parse("127.0.0.1"),
                remotePort = port,
            ),
            generation = 1,
        )

        try {
            proxy.connect(flow, events::offer)
            val event = requireNotNull(events.poll(2, TimeUnit.SECONDS))
            assertTrue(event is TcpProxyEvent.Failure)
        } finally {
            proxy.close()
        }
    }
}
