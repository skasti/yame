package no.skasti.yame.runtime

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.config.ConfigStore
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig
import no.skasti.yame.ppp.proxy.ResourceRelation
import no.skasti.yame.ppp.proxy.ResourceState
import no.skasti.yame.ppp.proxy.SystemRoutingTcpProxy
import no.skasti.yame.ppp.tcp.TcpFlowKey
import no.skasti.yame.ppp.tcp.TcpProxyEvent
import no.skasti.yame.ppp.tcp.TcpProxyFlow
import no.skasti.yame.persistence.YameConfiguration
import no.skasti.yame.persistence.applicationConfigStore
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.serial.*
import no.skasti.yame.ui.TuiYameObserver
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class YameRuntimeConnectionTest {
    private class Port(
        private val failure: Exception? = null,
        private val onReadingStarted: () -> Unit = {},
    ) : SerialTransport {
        override val output = ByteArrayOutputStream()
        var closed = false
        override fun open() { failure?.let { throw it } }
        override fun setCarrierPresent(carrierPresent: Boolean) = Unit
        override fun startReading(onBytes: (ByteArray) -> Unit, onStopped: (Throwable?) -> Unit) = onReadingStarted()
        override fun close() { closed = true }
    }

    @Test
    fun `plain startup propagates invalid port construction and busy port open failures without retrying`() {
        val directory = Files.createTempDirectory("yame-fail-fast")
        try {
            for (constructionFails in listOf(true, false)) {
                val functions = FunctionStore()
                val store = applicationConfigStore(YameConfiguration(portName = "invalid"))
                val failure = IOException(if (constructionFails) "invalid port" else "port busy")
                val port = Port(failure)
                var attempts = 0
                val messages = mutableListOf<String>()
                YameLogManager(YameLogManager.defaultLevels(), logDirectory = directory).use { logs ->
                    YameRuntime(
                        observer = object : RuntimeObserver { override fun onLog(message: String) { messages += message } },
                        configStore = store, functions = functions, logManager = logs,
                        resourceGraphs = NavigationResourceRegistry(),
                        portProvider = { emptyList() }, failFastStartup = true,
                        connectionFactory = { _, _, _, _ ->
                            attempts++
                            if (constructionFails) throw failure
                            port
                        },
                    ).use { runtime ->
                        assertSame(failure, assertFailsWith<IOException> { runtime.run() })
                        assertEquals(1, attempts)
                        assertTrue(messages.any { it.contains("Could not open invalid") })
                        if (!constructionFails) assertTrue(port.closed)
                    }
                }
                assertTrue(functions.keys().isEmpty())
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `reselection reconnects unchanged serial settings without saves or configuration events`() {
        val directory = Files.createTempDirectory("yame-reselect")
        try {
            var saves = 0
            var events = 0
            val store = applicationConfigStore(YameConfiguration(portName = "COM1"), save = { saves++ })
            val functions = FunctionStore()
            val ports = listOf(SerialPortDescriptor("COM1", "test port"))
            val opened = mutableListOf<Triple<String, Int, SerialFlowControl>>()
            store.onChange.subscribe { events++ }.use {
                YameLogManager(YameLogManager.defaultLevels(), logDirectory = directory).use { logs ->
                    YameRuntime(
                        observer = object : RuntimeObserver {}, configStore = store,
                        functions = functions, logManager = logs,
                        resourceGraphs = NavigationResourceRegistry(), portProvider = { ports },
                        connectionFactory = { port, baud, flow, _ ->
                            opened += Triple(port, baud, flow)
                            Port()
                        },
                    ).use {
                        TuiYameObserver(store, functions, Terminal(interactive = false)).use { ui ->
                            ui.updateAvailablePorts(ports)
                            for (command in listOf("/port", "/baud", "/flow-control")) {
                                functions.invoke(ConnectionFunctions.Disconnect)
                                command.forEach { ui.handleKey(it.toString()) }
                                ui.handleKey("Enter")
                                ui.handleKey("Enter")
                            }
                            assertEquals(List(3) { Triple("COM1", 38400, SerialFlowControl.DISABLED) }, opened)
                            assertEquals(0, saves)
                            assertEquals(0, events)
                        }
                    }
                }
            }
            assertFailsWith<IllegalStateException> { ConfigStore.current }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `serial reconnect keeps resource graph created through HTTP compatibility handling`() {
        val directory = Files.createTempDirectory("yame-registry-reconnect")
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val page = "<html><img src=\"/image.gif\"></html>"
        val serverThread = Thread {
            server.use { listening ->
                listening.accept().use { socket ->
                    readRequest(socket)
                    val body = page.toByteArray(StandardCharsets.UTF_8)
                    val headers =
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    socket.getOutputStream().write(headers.toByteArray(StandardCharsets.US_ASCII))
                    socket.getOutputStream().write(body)
                    socket.getOutputStream().flush()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            val functions = FunctionStore()
            val store = applicationConfigStore(YameConfiguration(portName = "COM1"))
            val registry = NavigationResourceRegistry(maxContexts = 4)

            // Create graph state through the same HTTP routing path used by PPP sessions.
            val httpHandler = SystemRoutingTcpProxy(
                httpConfig = PppHttpCompatibilityConfig(enabled = true, requestTimeoutMillis = 2_000),
                resourceGraphs = registry,
            )
            val httpEvents = LinkedBlockingQueue<TcpProxyEvent>()
            val flow = httpFlow(peerPort = 2390)
            try {
                httpHandler.connect(flow, httpEvents::offer)
                assertIs<TcpProxyEvent.Connected>(requireNotNull(httpEvents.poll(2, TimeUnit.SECONDS)))
                httpHandler.send(
                    flow,
                    ("GET /page HTTP/1.0\r\nHost: 127.0.0.1:${server.localPort}\r\n\r\n")
                        .toByteArray(StandardCharsets.US_ASCII),
                ).getOrThrow()
                assertTrue(collectResponse(httpEvents).startsWith("HTTP/1.0 200 OK\r\n"))
            } finally {
                httpHandler.close()
            }

            val root = URI("http://127.0.0.1:${server.localPort}/page")
            val graph = registry.startNavigation(root)
            val graphSnapshot = registry.snapshots().single()
            val image = URI("http://127.0.0.1:${server.localPort}/image.gif")
            assertTrue(graphSnapshot.edges.any {
                it.childLegacyUri == image && it.relation == ResourceRelation.IMG_SRC
            })
            assertEquals(
                ResourceState.READY,
                graphSnapshot.nodes.single { it.legacyUri.path == "/page" }.state,
            )
            var readingStarted = 0
            var attempts = 0
            val ports = listOf(SerialPortDescriptor("COM1", "test port"))

            YameLogManager(YameLogManager.defaultLevels(), logDirectory = directory).use { logs ->
                val runtime = YameRuntime(
                    observer = object : RuntimeObserver {},
                    configStore = store,
                    functions = functions,
                    logManager = logs,
                    resourceGraphs = registry,
                    portProvider = { ports },
                    connectionFactory = { _, _, _, _ ->
                        attempts++
                        Port(onReadingStarted = { readingStarted++ })
                    },
                )
                try {
                    functions.invoke(ConnectionFunctions.Reconnect)
                    assertEquals(1, readingStarted)
                    functions.invoke(ConnectionFunctions.Reconnect)
                    assertEquals(2, readingStarted)
                    assertEquals(2, attempts)
                    assertEquals(graph.id, registry.startNavigation(root).id)
                    assertEquals(
                        graphSnapshot,
                        registry.snapshots().single(),
                        "reconnect should preserve every graph node and edge",
                    )
                } finally {
                    runtime.close()
                }
            }
        } finally {
            runCatching { server.close() }
            serverThread.join(2_000)
            directory.toFile().deleteRecursively()
        }
    }

    private fun collectResponse(events: LinkedBlockingQueue<TcpProxyEvent>): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            when (val event = requireNotNull(events.poll(3, TimeUnit.SECONDS))) {
                is TcpProxyEvent.Payload -> event.bytes.forEach(bytes::add)
                is TcpProxyEvent.EndOfStream -> break
                is TcpProxyEvent.WriteCompleted, is TcpProxyEvent.Connected -> Unit
                is TcpProxyEvent.Failure -> throw AssertionError("HTTP proxy failed", event.error)
            }
        }
        return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
    }

    private fun readRequest(socket: java.net.Socket): String {
        val output = ByteArrayOutputStream()
        var matched = 0
        val delimiter = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        while (matched < delimiter.size) {
            val value = socket.getInputStream().read()
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
