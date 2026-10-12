package no.skasti.yame.ppp.udp
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.udp.SystemUdpProxy
import no.skasti.yame.ppp.udp.UdpFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UdpProxyTest {
    @Test
    fun `retiring a UDP flow frees its socket without closing a different generation or namespace`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, loopback).use { firstServer ->
            DatagramSocket(0, loopback).use { secondServer ->
                firstServer.soTimeout = 2_000
                secondServer.soTimeout = 2_000
                SystemUdpProxy(maxFlows = 1, maxQueuedCommands = 8).use { proxy ->
                    val first = UdpFlow(1105, Ipv4Address.parse("127.0.0.1"), firstServer.localPort, 7,
                        UdpFlowNamespace.LOCAL_DNS)
                    val second = first.copy(destinationPort = secondServer.localPort)
                    val oldCallbacks = java.util.concurrent.CopyOnWriteArrayList<Result<ByteArray>>()
                    proxy.send(first, byteArrayOf(1), oldCallbacks::add)
                    firstServer.receive(DatagramPacket(ByteArray(8), 8))

                    proxy.closeFlow(first.copy(generation = 6)).getOrThrow()
                    proxy.closeFlow(first.copy(namespace = UdpFlowNamespace.EXTERNAL)).getOrThrow()
                    val failure = CountDownLatch(1)
                    var result: Result<ByteArray>? = null
                    proxy.send(second, byteArrayOf(2)) {
                        result = it
                        failure.countDown()
                    }
                    assertTrue(failure.await(2, TimeUnit.SECONDS))
                    assertEquals("UDP flow limit reached", requireNotNull(result).exceptionOrNull()?.message)

                    proxy.closeFlow(first).getOrThrow()
                    val received = CountDownLatch(1)
                    proxy.send(second, byteArrayOf(3)) {
                        result = it
                        received.countDown()
                    }
                    val incoming = DatagramPacket(ByteArray(8), 8)
                    secondServer.receive(incoming)
                    secondServer.send(DatagramPacket(byteArrayOf(4), 1, incoming.address, incoming.port))
                    assertTrue(received.await(2, TimeUnit.SECONDS))
                    assertContentEquals(byteArrayOf(4), requireNotNull(result).getOrThrow())
                    assertTrue(oldCallbacks.isEmpty(), "Retiring a flow must not report an upstream failure")

                    // A later client query may reuse the original tuple after retirement.
                    proxy.closeFlow(second).getOrThrow()
                    proxy.send(first, byteArrayOf(5), oldCallbacks::add)
                    val reopened = DatagramPacket(ByteArray(8), 8)
                    firstServer.receive(reopened)
                    assertEquals(5, reopened.data[0].toInt())
                }
            }
        }
    }

    @Test
    fun `system UDP proxy forwards datagram and returns reply`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, loopback).use { server ->
            val serverThread = thread(isDaemon = true) {
                val incoming = DatagramPacket(ByteArray(32), 32)
                server.receive(incoming)
                val reply = byteArrayOf(9, 8, 7)
                server.send(
                    DatagramPacket(
                        reply,
                        reply.size,
                        incoming.address,
                        incoming.port,
                    ),
                )
            }

            val proxy = SystemUdpProxy(
                idleTimeoutMillis = 1_000,
                maxFlows = 4,
                maxQueuedCommands = 4,
            )
            try {
                val latch = CountDownLatch(1)
                var reply: Result<ByteArray>? = null
                proxy.send(
                    flow = UdpFlow(
                        peerPort = 1037,
                        destination = Ipv4Address.parse("127.0.0.1"),
                        destinationPort = server.localPort,
                        generation = 0,
                    ),
                    payload = byteArrayOf(1, 2, 3),
                ) {
                    reply = it
                    latch.countDown()
                }

                assertTrue(latch.await(2, TimeUnit.SECONDS))
                assertContentEquals(byteArrayOf(9, 8, 7), requireNotNull(reply).getOrThrow())
                serverThread.join(1_000)
            } finally {
                proxy.close()
            }
        }
    }
}
