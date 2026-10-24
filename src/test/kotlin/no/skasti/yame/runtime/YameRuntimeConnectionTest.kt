package no.skasti.yame.runtime

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.observer.YameEvent
import no.skasti.yame.observer.YameEvents
import no.skasti.yame.observer.EventBroker
import no.skasti.yame.observer.BrokerWaitResult
import no.skasti.yame.config.ConfigStore
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.lifecycle.ApplicationFunctions
import no.skasti.yame.logging.YameLogLevel
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.ppp.proxy.ResourceGraphChange
import no.skasti.yame.ppp.proxy.ResourceGraphLifecycleEvent
import no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig
import no.skasti.yame.ppp.proxy.ResourceRelation
import no.skasti.yame.ppp.proxy.ResourceState
import no.skasti.yame.ppp.proxy.SystemRoutingTcpProxy
import no.skasti.yame.ppp.tcp.TcpFlowKey
import no.skasti.yame.ppp.tcp.TcpProxyEvent
import no.skasti.yame.ppp.tcp.TcpProxyFlow
import no.skasti.yame.persistence.YameConfiguration
import no.skasti.yame.persistence.applicationConfigStore
import no.skasti.yame.persistence.lazyApplicationConfigStore
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
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class YameRuntimeConnectionTest {
    private val resourceEvents = Collections.synchronizedList(mutableListOf<YameEvent>())
    private lateinit var broker: EventBroker
    private lateinit var installation: AutoCloseable
    private lateinit var subscription: AutoCloseable

    @BeforeTest fun setUpEvents() {
        broker = EventBroker()
        installation = YameEvents.install(broker)
        subscription = YameEvents.subscribe("runtime-resource-test") { resourceEvents += it }
    }

    @AfterTest fun tearDownEvents() {
        subscription.close()
        broker.close()
        assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(Duration.ofSeconds(5)))
        installation.close()
        resourceEvents.clear()
    }

    private class Port(
        private val failure: Exception? = null,
        private val onReadingStarted: () -> Unit = {},
        private val onOpen: () -> Unit = {},
        private val onClose: () -> Unit = {},
    ) : SerialTransport {
        override val output = ByteArrayOutputStream()
        var closed = false
        override fun open() { onOpen(); failure?.let { throw it } }
        override fun setCarrierPresent(carrierPresent: Boolean) = Unit
        override fun startReading(onBytes: (ByteArray) -> Unit, onStopped: (Throwable?) -> Unit) = onReadingStarted()
        override fun close() { closed = true; onClose() }
    }

    @Test
    fun `fresh lazy runtime reads settings and a stale connection cannot replace a newer one`() {
        val directory = Files.createTempDirectory("yame-lazy-runtime")
        try {
            Files.writeString(directory.resolve("yame.ini"),
                "[serial]\nport=COM1\nbaud=38400\n[logging]\nlevel-modem=DEBUG\n")
            val store = lazyApplicationConfigStore(directory.resolve("yame.ini"))
            val functions = FunctionStore()
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val firstClosed = CountDownLatch(1)
            val secondReady = CountDownLatch(1)
            val openedBauds = java.util.Collections.synchronizedList(mutableListOf<Int>())
            val messages = java.util.Collections.synchronizedList(mutableListOf<String>())
            val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            val first = Port(onOpen = {
                firstEntered.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
            }, onClose = { firstClosed.countDown() })
            YameLogManager(YameLogManager.defaultLevels(), logDirectory = directory).use { logs ->
                YameRuntime(
                    observer = object : RuntimeObserver { override fun onLog(message: String) {
                        messages += message
                        if (message.contains("YAME ready on COM1 at 57600 baud")) secondReady.countDown()
                    } },
                    configStore = store, functions = functions, logManager = logs,
                    resourceGraphs = NavigationResourceRegistry(),
                    portProvider = { listOf(SerialPortDescriptor("COM1", "test port")) },
                    connectionFactory = { _, baud, _, _ ->
                        openedBauds += baud
                        if (baud == 38_400) first else Port()
                    },
                ).use { runtime ->
                    assertEquals(YameLogLevel.DEBUG, logs.level(YameLogModule.MODEM))
                    val worker = thread {
                        try { runtime.run() } catch (error: Throwable) { failures += error }
                    }
                    try {
                        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
                        store.set(SerialSettings.Baud to 57_600)
                        releaseFirst.countDown()
                        assertTrue(secondReady.await(8, TimeUnit.SECONDS))
                        assertTrue(firstClosed.await(5, TimeUnit.SECONDS))
                        assertTrue(first.closed)
                        assertEquals(listOf(38_400, 57_600), openedBauds.toList())
                        assertEquals(1, messages.count { it.contains("YAME ready on COM1 at 57600 baud") })
                        assertTrue(messages.none { it.contains("YAME ready on COM1 at 38400 baud") })
                    } finally {
                        releaseFirst.countDown()
                        functions.invoke(ApplicationFunctions.Quit)
                        worker.join(5_000)
                        assertFalse(worker.isAlive)
                    }
                    assertTrue(failures.isEmpty(), failures.toString())
                }
            }
        } finally { directory.toFile().deleteRecursively() }
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
        val server = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
        val page = "<html><img src=\"/image.gif\"></html>"
        val serverThread = Thread {
            server.use { listening ->
                repeat(2) {
                    listening.accept().use { socket ->
                        val request = readRequest(socket)
                        val (contentType, bodyText) = if (request.startsWith("GET /page ")) {
                            "text/html; charset=utf-8" to page
                        } else {
                            "image/gif" to "GIF89a"
                        }
                        val body = bodyText.toByteArray(StandardCharsets.UTF_8)
                        val headers =
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: $contentType\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        socket.getOutputStream().write(headers.toByteArray(StandardCharsets.US_ASCII))
                        socket.getOutputStream().write(body)
                        socket.getOutputStream().flush()
                    }
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
            val httpConfig = PppHttpCompatibilityConfig(enabled = true, requestTimeoutMillis = 2_000)

            // Create graph state through the same HTTP routing path used by PPP sessions.
            val httpHandler = SystemRoutingTcpProxy(
                httpConfig = httpConfig,
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
            val initialGraphSnapshot = registry.snapshots().single()
            val image = URI("http://127.0.0.1:${server.localPort}/image.gif")
            assertTrue(initialGraphSnapshot.edges.any {
                it.childLegacyUri == image && it.relation == ResourceRelation.IMG_SRC
            })
            assertEquals(
                ResourceState.READY,
                initialGraphSnapshot.nodes.single { it.legacyUri.path == "/page" }.state,
            )
            val oldImageAttempt = requireNotNull(registry.beginFetch(graph, image, image, image))
            val graphSnapshot = registry.snapshots().single()
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
                    assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(Duration.ofSeconds(5)))
                    val published = synchronized(resourceEvents) { resourceEvents.toList() }
                    assertTrue(published.filterIsInstance<ResourceGraphLifecycleEvent>().any {
                        it.change == ResourceGraphChange.USED && it.graphId == graph.id
                    })
                    assertFalse(published.filterIsInstance<ResourceGraphLifecycleEvent>().any {
                        it.change == ResourceGraphChange.REMOVED && it.graphId == graph.id
                    })
                    assertEquals(
                        graphSnapshot,
                        registry.snapshots().single(),
                        "reconnect should preserve every graph node and edge",
                    )

                    // A fresh HTTP proxy generation after the serial reconnect continues the
                    // discovered image request in the same graph and supersedes its old fetch.
                    val replacementHttpHandler = SystemRoutingTcpProxy(
                        httpConfig = httpConfig,
                        resourceGraphs = registry,
                    )
                    val replacementEvents = LinkedBlockingQueue<TcpProxyEvent>()
                    val imageFlow = httpFlow(peerPort = 2393).copy(generation = 2)
                    try {
                        replacementHttpHandler.connect(imageFlow, replacementEvents::offer)
                        assertIs<TcpProxyEvent.Connected>(
                            requireNotNull(replacementEvents.poll(2, TimeUnit.SECONDS)),
                        )
                        replacementHttpHandler.send(
                            imageFlow,
                            ("GET /image.gif HTTP/1.0\r\nHost: 127.0.0.1:${server.localPort}\r\n\r\n")
                                .toByteArray(StandardCharsets.US_ASCII),
                        ).getOrThrow()
                        assertTrue(collectResponse(replacementEvents).endsWith("GIF89a"))
                    } finally {
                        replacementHttpHandler.close()
                    }

                    val afterReplacement = registry.snapshots().single()
                    assertEquals(graphSnapshot.id, afterReplacement.id)
                    assertEquals(graphSnapshot.edges, afterReplacement.edges)
                    assertEquals(
                        ResourceState.READY,
                        afterReplacement.nodes.single { it.legacyUri == image }.state,
                    )
                    assertFalse(
                        registry.markFetchState(
                            graph,
                            oldImageAttempt,
                            image,
                            image,
                            image,
                            ResourceState.FAILED,
                        ),
                    )
                    assertEquals(
                        ResourceState.READY,
                        registry.snapshots().single().nodes.single { it.legacyUri == image }.state,
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
