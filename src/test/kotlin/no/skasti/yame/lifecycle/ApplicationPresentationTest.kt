package no.skasti.yame.lifecycle

import no.skasti.yame.config.InstalledConfigTest
import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.config.ConfigStore
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.logging.DiagnosticMessage
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.main as applicationMain
import no.skasti.yame.observer.*
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.presentation.PlainPresentation
import no.skasti.yame.presentation.Presentation
import no.skasti.yame.runtime.YameRuntime
import no.skasti.yame.serial.AvailablePortsChanged
import no.skasti.yame.serial.ConnectionFunctions
import no.skasti.yame.ui.InteractiveYameApplication
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ApplicationPresentationTest : InstalledConfigTest() {
    @Test fun `Main starts subscribed TUI before constructing and running core`() = scenario("tui")
    @Test fun `Main plain execution failure cleans up runtime and diagnostics`() = scenario("plain")
    @Test fun `Main cleans up shared infrastructure if presentation construction fails`() = scenario("construct")
    @Test fun `Main owns presentation before its startup can fail`() = scenario("start")
    @Test fun `Main closes started presentation if core startup fails`() = scenario("runtime")

    private fun scenario(name: String) {
        // Main seals YameEvents at process exit. Test the real entry point in a
        // fresh JVM instead of adding a reset or reproducing its orchestration.
        val output = Files.createTempFile("yame-main-$name", ".log")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("yame.test.runtimeClasspath"),
            MainStartupProcess::class.java.name, name,
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(25, TimeUnit.SECONDS), "Main did not exit: ${Files.readString(output)}")
            val text = Files.readString(output)
            assertEquals(0, process.exitValue(), text)
            assertTrue(text.contains("verified $name startup"), text)
        } finally {
            process.destroyForcibly()
            Files.deleteIfExists(output)
        }
    }

    @Test fun `plain presentation receives observations without a runtime or TUI`() {
        val broker = EventBroker()
        val installation = YameEvents.install(broker)
        val seen = CopyOnWriteArrayList<String>()
        try {
            PlainPresentation(seen::add).use {
                YameEvents.publish(DiagnosticMessage("before start"))
                it.start()
                YameEvents.publish(DiagnosticMessage("after start"))
                assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(Duration.ofSeconds(5)))
            }
            YameEvents.publish(DiagnosticMessage("after close"))
            assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(Duration.ofSeconds(5)))
            assertEquals(listOf("before start", "after start"), seen.toList())
        } finally {
            broker.close()
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(Duration.ofSeconds(5)))
            installation.close()
        }
    }

    @Test fun `core remains usable without presentation and releases injected registrations`() {
        val broker = EventBroker()
        val installation = YameEvents.install(broker)
        val directory = Files.createTempDirectory("yame-headless")
        val functions = FunctionStore()
        val config = ConfigStore()
        try {
            YameLogManager(no.skasti.yame.logging.LoggingConfig(), logDirectory = directory).use { logs ->
                run { installConfig(config); YameRuntime(functions, logs, NavigationResourceRegistry(), {
                    functions.invoke(ApplicationFunctions.Quit)
                    emptyList()
                }) }.use {
                    assertTrue(functions.keys().isEmpty())
                    it.run()
                }
            }
            assertTrue(functions.keys().isEmpty())
            assertSame(config, ConfigStore.current)
        } finally {
            broker.close()
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(Duration.ofSeconds(5)))
            installation.close()
            directory.toFile().deleteRecursively()
        }
    }
}

/** Real Main/runtime/lifecycle, with only presentation construction intercepted. */
object MainStartupProcess {
    @JvmStatic fun main(args: Array<String>) {
        val scenario = args.single()
        val directory = Files.createTempDirectory("yame-main-startup")
        val broker = EventBroker()
        val installation = YameEvents.install(broker)
        val functions = FunctionStore.current
        val trace = CopyOnWriteArrayList<String>()
        val injectedFailure = IllegalStateException("injected presentation failure")
        var closes = 0
        var conflict: AutoCloseable? = null
        try {
            assertTrue(functions.keys().isEmpty())
            val mode = if (scenario == "plain") "plain" else "tui"
            val cli = arrayOf("--ui", mode, "--config", directory.resolve("yame.ini").toString(),
                "--log-dir", directory.resolve("logs").toString()) +
                if (scenario == "plain") arrayOf("--port", "YAME-nonexistent-serial-port") else emptyArray()
            val failure = runCatching {
                applicationMain(cli) { useTui, _ ->
                    assertEquals(mode == "tui", useTui)
                    assertNotNull(ConfigStore.current)
                    assertTrue(functions.keys().isEmpty())
                    if (scenario == "construct") throw injectedFailure
                    val tui = if (useTui) InteractiveYameApplication(terminal = Terminal(interactive = false)) else null
                    val actual: Presentation = tui ?: PlainPresentation {}
                    trace += "constructed"
                    val observations = broker.subscribe("startup-test", { it is AvailablePortsChanged }) {
                        assertTrue("started" in trace, "runtime executed before presentation start")
                        val firstExecution = trace.addIfAbsent("runtime")
                        if (scenario == "tui" && firstExecution) {
                            assertTrue(ApplicationFunctions.Quit in functions.keys(), "runtime functions unavailable during execution")
                            functions.invoke(ApplicationFunctions.Quit)
                        }
                    }
                    // Prove that the real TUI subscribed during construction,
                    // without runtime registration or a replay/bootstrap query.
                    if (tui != null) {
                        val graph = NavigationResourceRegistry().startNavigation(URI("http://early.test/"))
                        assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(Duration.ofSeconds(5)))
                        assertEquals(setOf(graph.id), tui.resourceGraphProjectionSnapshot().keys)
                    }
                    trace += "subscribed"
                    object : Presentation {
                        override fun start() {
                            assertTrue(functions.keys().isEmpty())
                            actual.start() // Must return before runtime functions exist.
                            trace += "started"
                            if (scenario == "start") throw injectedFailure
                            if (scenario == "runtime") {
                                conflict = functions.register(ConnectionFunctions.Disconnect) { }
                            }
                        }
                        override fun close() {
                            closes++
                            assertTrue(broker.status.terminated, "presentation closed before broker drain")
                            assertNotNull(ConfigStore.current, "configuration released before presentation")
                            assertEquals(if (scenario == "runtime") listOf(ConnectionFunctions.Disconnect) else emptyList(),
                                functions.keys(), "runtime must release its registrations before UI closes")
                            conflict?.close()
                            observations.close()
                            actual.close()
                            trace += "closed"
                        }
                    }
                }
            }.exceptionOrNull()
            when (scenario) {
                "construct", "start" -> assertSame(injectedFailure, failure)
                "runtime" -> assertIs<IllegalArgumentException>(failure)
                "plain" -> assertIs<Exception>(failure) // Real fail-fast serial-open failure.
                else -> assertNull(failure)
            }
            assertEquals(if (scenario == "construct") 0 else 1, closes)
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(Duration.ofSeconds(5)))
            assertEquals(0L, broker.status.subscriberFailures, broker.status.lastSubscriberFailure.toString())
            assertTrue(functions.keys().isEmpty())
            assertSame(functions, FunctionStore.current)
            assertFailsWith<IllegalStateException> { ConfigStore.current }
            if (scenario == "tui" || scenario == "plain") {
                assertEquals(listOf("constructed", "subscribed", "started", "runtime", "closed"), trace.toList())
            } else assertFalse("runtime" in trace)
            println("verified $scenario startup")
        } finally {
            conflict?.close()
            broker.close()
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(Duration.ofSeconds(5)))
            installation.close()
            directory.toFile().deleteRecursively()
        }
    }
}
