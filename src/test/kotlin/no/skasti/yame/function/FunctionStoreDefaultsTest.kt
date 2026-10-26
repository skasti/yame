package no.skasti.yame.function

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.observer.*
import no.skasti.yame.persistence.YameConfiguration
import no.skasti.yame.persistence.applicationConfigStore
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.runtime.YameRuntime
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
            val terminal = Terminal(interactive = false)
            YameLogManager(YameLogManager.defaultLevels(), logDirectory = directory).use { logs ->
                YameRuntime(config, first, logs, NavigationResourceRegistry(), { emptyList() }).use {
                    assertSame(first, it.functions)
                    TuiYameObserver(config, first, terminal).use { observer -> observer.refreshPorts() }
                }
                InteractiveYameApplication(config, second, terminal).use {
                    assertSame(second, it.functions)
                }
                assertFalse(delegate.isInitialized(), "explicit component injection must bypass current")

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

                var refreshes = 0
                YameRuntime(configStore = config, logManager = logs,
                    resourceGraphs = NavigationResourceRegistry(), portProvider = { refreshes++; emptyList() }).use {
                    assertSame(shared, it.functions)
                    TuiYameObserver(configStore = config, terminal = terminal).use { observer ->
                        observer.refreshPorts()
                        assertEquals(1, refreshes)
                    }
                    assertTrue(first.keys().isEmpty())
                    assertTrue(second.keys().isEmpty())
                }
                assertTrue(shared.keys().isEmpty())
                InteractiveYameApplication(configStore = config, terminal = terminal).use { assertSame(shared, it.functions) }
                assertTrue(shared.keys().isEmpty())
            }
        } finally {
            broker.close()
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(Duration.ofSeconds(5)))
            installation.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun TuiYameObserver.refreshPorts() {
        "/refresh-ports".forEach { handleKey(it.toString()) }
        handleKey("Enter")
    }
}
