package no.skasti.serialmodem.ppp

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class UdpProxyTest {
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
