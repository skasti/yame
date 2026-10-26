package no.skasti.yame.lifecycle

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.logging.DiagnosticMessage
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.observer.*
import no.skasti.yame.persistence.YameConfiguration
import no.skasti.yame.persistence.applicationConfigStore
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.presentation.PlainPresentation
import no.skasti.yame.presentation.Presentation
import no.skasti.yame.runtime.YameRuntime
import no.skasti.yame.serial.ConnectionFunctions
import no.skasti.yame.ui.InteractiveYameApplication
import java.net.URI
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Real presentations and core, with isolated event and registry instances. */
class ApplicationPresentationTest {
    private lateinit var broker: EventBroker
    private lateinit var installation: AutoCloseable

    @BeforeTest fun install() {
        broker = EventBroker()
        installation = YameEvents.install(broker)
    }

    @AfterTest fun release() {
        broker.close()
        assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(Duration.ofSeconds(5)))
        installation.close()
    }

    @Test fun `TUI is subscribed before core construction and starts without blocking runtime`() =
        runMode("tui")

    @Test fun `plain mode receives startup diagnostics and shuts down without TUI`() =
        runMode("plain")

    @Test fun `core runs with no presentation`() = runMode("none")

    private fun runMode(mode: String) {
        val directory = Files.createTempDirectory("yame-bootstrap")
        val lifecycle = ApplicationEventLifecycle(broker, sealFacade = {},
            report = { fail(it) }, terminate = { fail("unexpected exit $it") })
        val functions = FunctionStore()
        val store = applicationConfigStore(YameConfiguration())
        val registry = NavigationResourceRegistry()
        val output = CopyOnWriteArrayList<String>()
        var tui: InteractiveYameApplication? = null
        var started = mode == "none"
        var closed = mode == "none"
        var scanned = false
        var scannedAfterStart = false
        try {
            val logs = YameLogManager(YameLogManager.defaultLevels(), logDirectory = directory)
            lifecycle.consumer(logs)
            ApplicationBootstrap(lifecycle).run(
                createPresentation = {
                    val presentation = when (mode) {
                        "tui" -> InteractiveYameApplication(store, functions, Terminal(interactive = false))
                            .also { tui = it }
                        "plain" -> PlainPresentation(output::add)
                        else -> null
                    }
                    presentation?.let {
                        object : Presentation {
                            override fun start() {
                                assertTrue(ApplicationFunctions.Quit in functions.keys())
                                it.start()
                                started = true
                            }
                            override fun close() {
                                assertTrue(broker.status.terminated)
                                assertTrue(functions.keys().isEmpty(), "core functions released before UI")
                                it.close()
                                closed = true
                            }
                        }
                    }
                },
                createRuntime = {
                    YameEvents.publish(DiagnosticMessage("constructing"))
                    val graph = registry.startNavigation(URI("http://early.test/"))
                    assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(Duration.ofSeconds(5)))
                    tui?.let { assertEquals(setOf(graph.id), it.resourceGraphProjectionSnapshot().keys) }
                    YameRuntime(store, functions, logs, resourceGraphs = registry, portProvider = {
                        scannedAfterStart = started
                        scanned = true
                        functions.invoke(ApplicationFunctions.Quit)
                        YameEvents.publish(DiagnosticMessage("running"))
                        emptyList()
                    })
                },
            )
            assertTrue(scanned)
            assertTrue(scannedAfterStart, "runtime must not block presentation startup")
            assertTrue(closed)
            assertTrue(functions.keys().isEmpty())
            assertEquals(0L, broker.status.subscriberFailures, broker.status.lastSubscriberFailure.toString())
            if (mode == "plain") assertEquals(listOf("constructing", "running"), output.toList())
            else assertTrue(output.isEmpty())
        } finally {
            lifecycle.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun `failed core construction rolls back earlier function registrations and closes presentation`() {
        val directory = Files.createTempDirectory("yame-bootstrap-failure")
        val functions = FunctionStore()
        val existing = functions.register(ConnectionFunctions.Disconnect) { }
        val lifecycle = ApplicationEventLifecycle(broker, sealFacade = {},
            report = { fail(it) }, terminate = { fail("unexpected exit $it") })
        try {
            val logs = YameLogManager(YameLogManager.defaultLevels(), logDirectory = directory)
            lifecycle.consumer(logs)
            assertFailsWith<IllegalArgumentException> {
                ApplicationBootstrap(lifecycle).run(
                    createPresentation = { PlainPresentation {} },
                    createRuntime = {
                        YameRuntime(applicationConfigStore(YameConfiguration()), functions, logs,
                            resourceGraphs = NavigationResourceRegistry(), portProvider = { emptyList() })
                    },
                )
            }
            assertEquals(listOf(ConnectionFunctions.Disconnect), functions.keys())
            assertTrue(broker.status.terminated)
        } finally {
            lifecycle.close()
            existing.close()
            directory.toFile().deleteRecursively()
        }
    }
}
