package no.skasti.serialmodem.ppp

import java.net.InetAddress
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
    fun `pending connects reserve max-flow capacity before socket connect completes`() {
        val connectEntered = CountDownLatch(1)
        val releaseConnect = CountDownLatch(1)
        val proxy = SystemTcpProxy(
            connectTimeoutMillis = 1_000,
            maxFlows = 1,
            connectOperation = { _, _, _ ->
                connectEntered.countDown()
                releaseConnect.await(2, TimeUnit.SECONDS)
                throw IOException("synthetic connect failure")
            },
        )
        val firstEvents = LinkedBlockingQueue<TcpProxyEvent>()
        val secondEvents = LinkedBlockingQueue<TcpProxyEvent>()
        val first = flow(peerPort = 2001, remotePort = 80)
        val second = flow(peerPort = 2002, remotePort = 81)

        try {
            proxy.connect(first, firstEvents::offer)
            assertTrue(connectEntered.await(1, TimeUnit.SECONDS))

            proxy.connect(second, secondEvents::offer)
            val rejected = assertIs<TcpProxyEvent.Failure>(
                requireNotNull(secondEvents.poll(1, TimeUnit.SECONDS)),
            )
            assertTrue(rejected.error.message?.contains("flow limit") == true)

            releaseConnect.countDown()
            assertIs<TcpProxyEvent.Failure>(
                requireNotNull(firstEvents.poll(2, TimeUnit.SECONDS)),
            )
        } finally {
            releaseConnect.countDown()
            proxy.close()
        }
    }

    @Test
    fun `host writes run on a worker instead of blocking the caller`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val accepted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val serverThread = Thread {
            server.use {
                it.accept().use {
                    accepted.countDown()
                    releaseServer.await(3, TimeUnit.SECONDS)
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val proxy = SystemTcpProxy(
            connectTimeoutMillis = 1_000,
            writeOperation = { _, _ ->
                writeStarted.countDown()
                releaseWrite.await(3, TimeUnit.SECONDS)
            },
        )
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = flow(peerPort = 2003, remotePort = server.localPort)
        val sender = Executors.newSingleThreadExecutor()

        try {
            proxy.connect(flow, events::offer)
            assertIs<TcpProxyEvent.Connected>(
                requireNotNull(events.poll(2, TimeUnit.SECONDS)),
            )
            assertTrue(accepted.await(1, TimeUnit.SECONDS))

            val result = sender.submit<Result<Unit>> {
                proxy.send(flow, ByteArray(1024) { 7 })
            }.get(500, TimeUnit.MILLISECONDS)
            result.getOrThrow()

            assertTrue(writeStarted.await(1, TimeUnit.SECONDS))
            assertTrue(releaseWrite.count == 1L)
        } finally {
            releaseWrite.countDown()
            releaseServer.countDown()
            sender.shutdownNow()
            proxy.close()
            runCatching { server.close() }
            serverThread.join(2_000)
        }
    }

    @Test
    fun `paused host reads stop callbacks until the flow is resumed`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val serverThread = Thread {
            server.use {
                it.accept().use { accepted ->
                    accepted.getOutputStream().write("abcdefgh".encodeToByteArray())
                    accepted.getOutputStream().flush()
                    accepted.shutdownOutput()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val proxy = SystemTcpProxy(
            connectTimeoutMillis = 1_000,
            readBufferSize = 4,
        )
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        val flow = flow(peerPort = 2004, remotePort = server.localPort)

        try {
            proxy.connect(flow) { event ->
                if (event is TcpProxyEvent.Connected) {
                    proxy.pauseReads(flow)
                }
                events.offer(event)
            }

            assertIs<TcpProxyEvent.Connected>(
                requireNotNull(events.poll(2, TimeUnit.SECONDS)),
            )
            assertNull(events.poll(200, TimeUnit.MILLISECONDS))

            proxy.resumeReads(flow)

            val first = assertIs<TcpProxyEvent.Payload>(
                requireNotNull(events.poll(2, TimeUnit.SECONDS)),
            )
            val second = assertIs<TcpProxyEvent.Payload>(
                requireNotNull(events.poll(2, TimeUnit.SECONDS)),
            )
            assertContentEquals("abcd".encodeToByteArray(), first.bytes)
            assertContentEquals("efgh".encodeToByteArray(), second.bytes)
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

    private fun flow(
        peerPort: Int,
        remotePort: Int,
    ): TcpProxyFlow =
        TcpProxyFlow(
            key = TcpFlowKey(
                peerAddress = Ipv4Address.parse("10.0.0.2"),
                peerPort = peerPort,
                remoteAddress = Ipv4Address.parse("127.0.0.1"),
                remotePort = remotePort,
            ),
            generation = 1,
        )

}
