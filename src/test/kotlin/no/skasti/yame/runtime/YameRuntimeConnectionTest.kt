package no.skasti.yame.runtime

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.config.ConfigStore
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.ppp.proxy.ResourceKind
import no.skasti.yame.ppp.proxy.ResourceRelation
import no.skasti.yame.persistence.YameConfiguration
import no.skasti.yame.persistence.applicationConfigStore
import no.skasti.yame.serial.*
import no.skasti.yame.ui.TuiYameObserver
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
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
    fun `serial reconnect keeps resource graph state in the injected process registry`() {
        val directory = Files.createTempDirectory("yame-registry-reconnect")
        try {
            val functions = FunctionStore()
            val store = applicationConfigStore(YameConfiguration(portName = "COM1"))
            val registry = NavigationResourceRegistry(maxContexts = 4)
            val root = URI("http://runtime-reconnect-99.test/page")
            val graph = registry.startNavigation(root)
            registry.discover(
                graph,
                graph.rootLegacyUri,
                URI("http://runtime-reconnect-cdn-99.test/image.gif"),
                URI("https://runtime-reconnect-cdn-99.test/image.gif"),
                ResourceRelation.IMG_SRC,
                ResourceKind.IMAGE,
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
                    assertEquals(2, registry.snapshots().single().nodes.size)
                } finally {
                    runtime.close()
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
