package no.skasti.yame.runtime

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.config.ConfigStore
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.persistence.YameConfiguration
import no.skasti.yame.persistence.applicationConfigStore
import no.skasti.yame.serial.*
import no.skasti.yame.ui.TuiYameObserver
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

class YameRuntimeConnectionTest {
    private class Port(private val failure: Exception? = null) : SerialTransport {
        override val output = ByteArrayOutputStream()
        var closed = false
        override fun open() { failure?.let { throw it } }
        override fun setCarrierPresent(carrierPresent: Boolean) = Unit
        override fun startReading(onBytes: (ByteArray) -> Unit, onStopped: (Throwable?) -> Unit) = Unit
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
                        functions = functions, logManager = logs, portProvider = { ports },
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
}
