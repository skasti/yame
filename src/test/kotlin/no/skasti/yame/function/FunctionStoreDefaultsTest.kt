package no.skasti.yame.function

import no.skasti.yame.ui.handleKeyAndAwait
import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.config.ConfigStore
import no.skasti.yame.logging.LoggingConfig
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.modem.ModemSettings
import no.skasti.yame.observer.*
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.runtime.YameRuntime
import no.skasti.yame.runtime.RuntimeTestPorts
import no.skasti.yame.runtime.RuntimeTestRun
import no.skasti.yame.ui.InteractiveYameApplication
import no.skasti.yame.ui.TuiYameObserver
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Fresh JVM: function injection is independent, configuration always uses the installed store. */
class FunctionStoreDefaultsTest {
    @Test fun `injection bypasses lazy function default while components share installed configuration`() {
        val delegate = FunctionStore::class.java.getDeclaredField("current\$delegate").let {
            it.isAccessible = true
            it.get(null) as Lazy<*>
        }
        assertFalse(delegate.isInitialized())
        val first = FunctionStore()
        val second = FunctionStore()
        val key = FunctionKey<Unit, String>("test.isolation")
        first.register(key) { "first" }.use {
            assertEquals("first", first.invoke(key))
            assertFailsWith<IllegalArgumentException> { second.invoke(key) }
        }
        val broker = testEventBroker()
        val installation = YameEvents.install(broker)
        val directory = Files.createTempDirectory("yame-function-defaults")
        try {
            val config = ConfigStore()
            val terminal = Terminal(interactive = false)
            YameLogManager(LoggingConfig(), logDirectory = directory).use { logs ->
                assertFailsWith<IllegalStateException> {
                    YameRuntime(first, logs, NavigationResourceRegistry())
                }
                assertFalse(delegate.isInitialized())
                ConfigStore.install(config).use {
                    val ports = RuntimeTestPorts()
                    YameRuntime(first, logs, NavigationResourceRegistry(), ports::scan).use { runtime ->
                        assertSame(first, runtime.functions)
                        RuntimeTestRun(runtime, ports).use {
                            TuiYameObserver(first, terminal).use { observer ->
                                observer.command("/tone off")
                            }
                        }
                    }
                    assertFalse(ConfigStore[ModemSettings.Tone])
                    InteractiveYameApplication(second, terminal).use {
                        assertSame(second, it.functions)
                    }
                    assertFalse(delegate.isInitialized(), "explicit function injection must bypass current")
                    val pool = Executors.newFixedThreadPool(8)
                    val start = CountDownLatch(1)
                    val shared = try {
                        val calls = (1..8).map { pool.submit<FunctionStore> {
                            check(start.await(5, TimeUnit.SECONDS))
                            FunctionStore.current
                        } }
                        start.countDown()
                        val stores = calls.map { it.get(5, TimeUnit.SECONDS) }
                        stores.forEach { assertSame(stores.first(), it) }
                        stores.first()
                    } finally { start.countDown(); pool.shutdownNow() }
                    assertTrue(delegate.isInitialized())
                    assertNotSame(first, shared)
                    val sharedPorts = RuntimeTestPorts()
                    YameRuntime(logManager = logs, resourceGraphs = NavigationResourceRegistry(),
                        portProvider = sharedPorts::scan).use { runtime ->
                        assertSame(shared, runtime.functions)
                        RuntimeTestRun(runtime, sharedPorts).use {
                            TuiYameObserver(terminal = terminal).use { observer ->
                                observer.command("/port")
                                observer.handleKeyAndAwait("Escape")
                                observer.command("/tone on")
                            }
                            assertTrue(config[ModemSettings.Tone])
                            InteractiveYameApplication(terminal = terminal).use {
                                assertSame(shared, it.functions)
                            }
                            // Separate function registries still share configuration.
                            val isolatedPorts = RuntimeTestPorts()
                            YameRuntime(first, logs, NavigationResourceRegistry(), isolatedPorts::scan).use { isolated ->
                                RuntimeTestRun(isolated, isolatedPorts).use {
                                    TuiYameObserver(first, terminal).use { it.command("/tone off") }
                                    assertFalse(ConfigStore[ModemSettings.Tone])
                                    assertSame(first, isolated.functions)
                                }
                            }
                        }
                    }
                    assertTrue(shared.keys().isEmpty())
                    val laterPorts = RuntimeTestPorts()
                    YameRuntime(logManager = logs, resourceGraphs = NavigationResourceRegistry(),
                        portProvider = laterPorts::scan).use {
                        RuntimeTestRun(it, laterPorts).use { assertTrue(shared.keys().isNotEmpty()) }
                    }
                    assertTrue(shared.keys().isEmpty())
                }
                assertFailsWith<IllegalStateException> { ConfigStore.current }
            }
        } finally {
            broker.close()
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(Duration.ofSeconds(5)))
            installation.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun TuiYameObserver.command(value: String) {
        value.forEach { handleKeyAndAwait(it.toString()) }
        handleKeyAndAwait("Enter")
    }
}
