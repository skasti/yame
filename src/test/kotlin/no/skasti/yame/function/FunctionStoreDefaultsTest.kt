package no.skasti.yame.function

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.config.ConfigStore
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.modem.ModemSettings
import no.skasti.yame.observer.*
import no.skasti.yame.persistence.YameConfiguration
import no.skasti.yame.persistence.applicationConfigStore
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

/** Fresh JVM: verify first access without exposing a reset or installation API. */
class FunctionStoreDefaultsTest {
    @Test fun `injection bypasses lazy default and default components share operations`() {
        // Inspect initialization only; never mutate or replace the delegate.
        val delegate = FunctionStore::class.java.getDeclaredField("current\$delegate").let {
            it.isAccessible = true
            it.get(null) as Lazy<*>
        }
        assertFalse(delegate.isInitialized())
        assertFailsWith<IllegalStateException> { ConfigStore.current }
        val first = FunctionStore()
        val second = FunctionStore()
        assertNotSame(first, second)
        val key = FunctionKey<Unit, String>("test.isolation")
        first.register(key) { "first" }.use {
            assertEquals("first", first.invoke(key))
            assertFailsWith<IllegalArgumentException> { second.invoke(key) }
        }
        val broker = EventBroker()
        val installation = YameEvents.install(broker)
        val directory = Files.createTempDirectory("yame-function-defaults")
        try {
            val config = applicationConfigStore(YameConfiguration())
            val otherConfig = applicationConfigStore(YameConfiguration())
            val terminal = Terminal(interactive = false)
            YameLogManager(YameLogManager.defaultLevels(), logDirectory = directory).use { logs ->
                val injectedPorts = RuntimeTestPorts()
                YameRuntime(config, first, logs, NavigationResourceRegistry(), injectedPorts::scan).use { runtime ->
                    assertSame(first, runtime.functions)
                    assertSame(config, runtime.configStore)
                    assertTrue(first.keys().isEmpty())
                    RuntimeTestRun(runtime, injectedPorts).use {
                        TuiYameObserver(config, first, terminal).use { observer ->
                            observer.refreshPorts()
                            observer.command("/tone off")
                        }
                    }
                }
                assertFalse(config[ModemSettings.Tone])
                assertTrue(otherConfig[ModemSettings.Tone])
                InteractiveYameApplication(otherConfig, second, terminal).use {
                    assertSame(second, it.functions)
                    assertSame(otherConfig, it.configStore)
                }
                assertFalse(delegate.isInitialized(), "explicit component injection must bypass current")
                assertFailsWith<IllegalStateException> { ConfigStore.current }

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
                assertSame(shared, FunctionStore.current)
                assertNotSame(first, shared)
                assertNotSame(second, shared)

                // Explicit configuration with default functions works before
                // any process configuration has been installed.
                var explicitConfigRefreshes = 0
                val explicitPorts = RuntimeTestPorts { explicitConfigRefreshes++; emptyList() }
                YameRuntime(configStore = config, logManager = logs,
                    resourceGraphs = NavigationResourceRegistry(),
                    portProvider = explicitPorts::scan).use { runtime ->
                    assertSame(config, runtime.configStore)
                    assertSame(shared, runtime.functions)
                    assertTrue(shared.keys().isEmpty())
                    RuntimeTestRun(runtime, explicitPorts).use {
                        TuiYameObserver(configStore = config, terminal = terminal).use { it.refreshPorts() }
                        assertEquals(1, explicitConfigRefreshes)
                        InteractiveYameApplication(configStore = config, terminal = terminal).use {
                            assertSame(config, it.configStore)
                            assertSame(shared, it.functions)
                        }
                    }
                }
                assertTrue(shared.keys().isEmpty())
                assertFailsWith<IllegalStateException> { ConfigStore.current }

                val processConfig = applicationConfigStore(YameConfiguration())
                var refreshes = 0
                val sharedPorts = RuntimeTestPorts { refreshes++; emptyList() }
                ConfigStore.install(processConfig).use {
                    YameRuntime(logManager = logs, resourceGraphs = NavigationResourceRegistry(),
                        portProvider = sharedPorts::scan).use { runtime ->
                        assertSame(processConfig, runtime.configStore)
                        assertSame(shared, runtime.functions)
                        assertTrue(shared.keys().isEmpty())
                        RuntimeTestRun(runtime, sharedPorts).use {
                            TuiYameObserver(terminal = terminal).use { observer ->
                                observer.refreshPorts()
                                observer.command("/tone off")
                                assertEquals(1, refreshes)
                            }
                            assertFalse(processConfig[ModemSettings.Tone])
                            InteractiveYameApplication(terminal = terminal).use {
                                assertSame(processConfig, it.configStore)
                                assertSame(runtime.functions, it.functions)
                            }

                            // Injected runtime/frontend must keep their own identity even
                            // when defaults exist and hold the same operation paths.
                            var isolatedRefreshes = 0
                            val isolatedPorts = RuntimeTestPorts { isolatedRefreshes++; emptyList() }
                            YameRuntime(config, first, logs, NavigationResourceRegistry(),
                                isolatedPorts::scan).use { isolated ->
                                assertSame(config, isolated.configStore)
                                assertSame(first, isolated.functions)
                                RuntimeTestRun(isolated, isolatedPorts).use {
                                    TuiYameObserver(config, first, terminal).use { observer ->
                                        observer.refreshPorts()
                                        observer.command("/tone on")
                                    }
                                    assertEquals(1, isolatedRefreshes)
                                    assertEquals(1, refreshes)
                                    assertTrue(config[ModemSettings.Tone])
                                    assertFalse(processConfig[ModemSettings.Tone])
                                    assertTrue(otherConfig[ModemSettings.Tone])
                                    InteractiveYameApplication(otherConfig, second, terminal).use {
                                        assertSame(second, it.functions)
                                        assertSame(otherConfig, it.configStore)
                                    }
                                }
                            }
                            assertTrue(first.keys().isEmpty())
                            assertTrue(second.keys().isEmpty())
                            assertTrue(shared.keys().isNotEmpty(), "isolated shutdown must preserve shared registrations")
                        }
                    }
                    assertTrue(shared.keys().isEmpty(), "runtime must release shared registrations")
                    // A later runtime can register the same paths on the same shared instance.
                    val laterPorts = RuntimeTestPorts()
                    YameRuntime(logManager = logs, resourceGraphs = NavigationResourceRegistry(),
                        portProvider = laterPorts::scan).use {
                        assertSame(shared, it.functions)
                        RuntimeTestRun(it, laterPorts).use { assertTrue(shared.keys().isNotEmpty()) }
                    }
                }
                assertTrue(shared.keys().isEmpty())
                assertSame(shared, FunctionStore.current)
                assertFailsWith<IllegalStateException> { ConfigStore.current }
            }
        } finally {
            broker.close()
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(Duration.ofSeconds(5)))
            installation.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun TuiYameObserver.refreshPorts() {
        command("/refresh-ports")
    }

    private fun TuiYameObserver.command(value: String) {
        value.forEach { handleKey(it.toString()) }
        handleKey("Enter")
    }
}
