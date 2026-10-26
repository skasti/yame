package no.skasti.yame.runtime

import no.skasti.yame.function.FunctionStore
import no.skasti.yame.lifecycle.ApplicationFunctions
import no.skasti.yame.logging.*
import no.skasti.yame.observer.*
import no.skasti.yame.persistence.*
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.serial.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

class YameRuntimeLifecycleTest {
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

    private class Port(
        val opening: () -> Unit = {}, val initializing: () -> Unit = {}, val closing: () -> Unit = {},
    ) : SerialTransport {
        override val output = ByteArrayOutputStream()
        val opens = AtomicInteger()
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        @Volatile var stopped: ((Throwable?) -> Unit)? = null
        override fun open() { opens.incrementAndGet(); opening() }
        override fun setCarrierPresent(carrierPresent: Boolean) = Unit
        override fun startReading(onBytes: (ByteArray) -> Unit, onStopped: (Throwable?) -> Unit) {
            stopped = onStopped
            reads.incrementAndGet()
            initializing()
        }
        override fun close() { closes.incrementAndGet(); closing(); stopped?.invoke(null) }
    }

    private class Fixture(
        source: () -> List<SerialPortDescriptor> = { emptyList() },
        factory: () -> SerialTransport = { Port() },
        config: YameConfiguration = YameConfiguration(portName = "COM1"),
    ) : AutoCloseable {
        val directory = Files.createTempDirectory("yame-runtime-lifecycle")
        val logs = YameLogManager(YameLogManager.defaultLevels(), directory)
        val store = applicationConfigStore(config)
        val functions = FunctionStore()
        val ports = RuntimeTestPorts(source)
        val runtime = YameRuntime(store, functions, logs, NavigationResourceRegistry(), ports::scan,
            connectionFactory = { _, _, _, _ -> factory() })
        fun run() = RuntimeTestRun(runtime, ports)
        override fun close() {
            ports.releaseStartup()
            try { runtime.close() } finally { logs.close(); directory.toFile().deleteRecursively() }
        }
    }

    private class Worker(action: () -> Unit) {
        private val failure = AtomicReference<Throwable>()
        private val worker = thread { try { action() } catch (error: Throwable) { failure.set(error) } }
        fun finish(): Throwable? {
            worker.join(5_000)
            assertFalse(worker.isAlive, "operation deadlocked")
            return failure.get()
        }
    }

    @Test fun `startup registers once and registered commands validate quitting state`() {
        Fixture().use { f ->
            assertTrue(f.functions.keys().isEmpty())
            assertFailsWith<IllegalArgumentException> { f.functions.invoke(ConnectionFunctions.RefreshPorts) }
            f.run().use {
                assertEquals(listOf(ApplicationFunctions.Quit, ConnectionFunctions.Disconnect,
                    ConnectionFunctions.Reconnect, ConnectionFunctions.RefreshPorts), f.functions.keys())
                val duplicate = Worker { f.runtime.run() }
                assertIs<IllegalStateException>(duplicate.finish())
                assertEquals(4, f.functions.keys().size)
                assertTrue(f.functions.invoke(ConnectionFunctions.RefreshPorts).isEmpty())
                f.functions.invoke(ApplicationFunctions.Quit)
                assertEquals(4, f.functions.keys().size, "initial scan still holds the run thread")
                assertFailsWith<IllegalStateException> { f.functions.invoke(ConnectionFunctions.Disconnect) }
                assertFailsWith<IllegalStateException> { f.functions.invoke(ConnectionFunctions.Reconnect) }
                assertFailsWith<IllegalStateException> { f.functions.invoke(ConnectionFunctions.RefreshPorts) }
                assertFailsWith<IllegalStateException> { f.functions.invoke(ApplicationFunctions.Quit) }
            }
            assertTrue(f.functions.keys().isEmpty())
            assertFailsWith<IllegalArgumentException> { f.functions.invoke(ConnectionFunctions.RefreshPorts) }
            assertFailsWith<IllegalStateException> { f.runtime.run() }
        }
    }

    @Test fun `close before startup exposes no functions and removes configuration subscription`() {
        Fixture().use { f ->
            f.runtime.close()
            assertFailsWith<IllegalStateException> { f.runtime.run() }
            assertTrue(f.functions.keys().isEmpty())
            val original = f.logs.level(YameLogModule.MODEM)
            f.store.set(LogSettings.Levels.getValue(YameLogModule.MODEM) to YameLogLevel.DEBUG)
            assertEquals(original, f.logs.level(YameLogModule.MODEM))
        }
    }

    @Test fun `simultaneous run calls have one startup owner and one rejected caller`() {
        val scans = AtomicInteger()
        Fixture(source = { scans.incrementAndGet(); emptyList() }).use { f ->
            val go = CountDownLatch(1)
            val rejected = CountDownLatch(1)
            val attempts = (1..2).map {
                Worker {
                    check(go.await(5, TimeUnit.SECONDS))
                    try { f.runtime.run() } catch (error: IllegalStateException) { rejected.countDown() }
                }
            }
            go.countDown()
            try {
                f.ports.awaitStartup()
                assertTrue(rejected.await(5, TimeUnit.SECONDS))
                assertEquals(4, f.functions.keys().size)
                f.functions.invoke(ApplicationFunctions.Quit)
            } finally { f.ports.releaseStartup() }
            attempts.forEach { assertNull(it.finish()) }
            assertEquals(1, scans.get(), "only one startup loop may enumerate ports")
            assertTrue(f.functions.keys().isEmpty())
        }
    }

    @Test fun `automatic port selection and its configuration callback create one connection`() {
        val port = Port()
        Fixture(source = { listOf(SerialPortDescriptor("COM1", "test")) }, factory = { port },
            config = YameConfiguration()).use { f ->
            f.run().use {
                f.functions.invoke(ConnectionFunctions.Reconnect)
                assertEquals("COM1", f.store[SerialSettings.Port])
                assertEquals(1, port.opens.get())
                assertEquals(1, port.reads.get())
            }
            assertEquals(1, port.closes.get())
        }
    }

    @Test fun `shutdown waits for refresh IO and cancelled external refresh throws instead of returning empty`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        Fixture(source = {
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
            listOf(SerialPortDescriptor("COM1", "test"))
        }).use { f ->
            f.run().use {
                val refresh = Worker { f.functions.invoke(ConnectionFunctions.RefreshPorts) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val closing = CountDownLatch(1)
                broker.subscribe("closing-refresh", { it is ConnectionStateChanged }) { closing.countDown() }.use {
                    val closed = CountDownLatch(1)
                    val closer = Worker { f.runtime.close(); closed.countDown() }
                    try {
                        assertTrue(closing.await(5, TimeUnit.SECONDS))
                        assertTrue(f.functions.keys().isEmpty())
                        assertEquals(1L, closed.count, "refresh still owns OS enumeration")
                    } finally { f.ports.releaseStartup(); resume.countDown() }
                    assertIs<IllegalStateException>(refresh.finish())
                    assertNull(closer.finish())
                    assertEquals(0L, closed.count)
                }
            }
        }
    }

    @Test fun `shutdown fences a blocked reconnect attempt and all concurrent closers wait for cleanup`() {
        val opened = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val port = Port(opening = { opened.countDown(); check(resume.await(5, TimeUnit.SECONDS)) })
        Fixture(factory = { port }).use { f ->
            f.run().use {
                val reconnect = Worker { f.functions.invoke(ConnectionFunctions.Reconnect) }
                assertTrue(opened.await(5, TimeUnit.SECONDS))
                val closing = CountDownLatch(1)
                broker.subscribe("closing-open", {
                    it is ConnectionStateChanged && it.connectionId > 0 && it.state == ConnectionState.DISCONNECTED
                }) { closing.countDown() }.use {
                    val done = CountDownLatch(2)
                    val first = Worker { f.runtime.close(); done.countDown() }
                    assertTrue(closing.await(5, TimeUnit.SECONDS))
                    val second = Worker { f.runtime.close(); done.countDown() }
                    try {
                        assertTrue(f.functions.keys().isEmpty())
                        assertEquals(2L, done.count)
                        assertEquals(0, port.closes.get(), "initializer still owns the opening port")
                    } finally { f.ports.releaseStartup(); resume.countDown() }
                    assertNull(reconnect.finish())
                    assertNull(first.finish())
                    assertNull(second.finish())
                    assertEquals(0L, done.count)
                    assertEquals(1, port.closes.get())
                    assertEquals(0, port.reads.get())
                }
            }
        }
    }

    @Test fun `shutdown does not release a connection while reader initialization still uses it`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val port = Port(initializing = { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) })
        Fixture(factory = { port }).use { f ->
            f.run().use {
                val reconnect = Worker { f.functions.invoke(ConnectionFunctions.Reconnect) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val closing = CountDownLatch(1)
                broker.subscribe("closing-initialize", {
                    it is ConnectionStateChanged && it.connectionId > 0 && it.state == ConnectionState.DISCONNECTED
                }) { closing.countDown() }.use {
                    val closer = Worker { f.runtime.close() }
                    try {
                        assertTrue(closing.await(5, TimeUnit.SECONDS))
                        assertEquals(0, port.closes.get())
                    } finally { f.ports.releaseStartup(); resume.countDown() }
                    assertNull(reconnect.finish())
                    assertNull(closer.finish())
                    assertEquals(1, port.closes.get())
                    port.stopped?.invoke(null) // Late reader callbacks are stale internal work.
                    assertEquals(1, port.closes.get())
                }
            }
        }
    }

    @Test fun `shutdown waits for configuration-owned detached cleanup and prevents its replacement connection`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val factories = AtomicInteger()
        val port = Port(closing = { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) })
        Fixture(factory = { factories.incrementAndGet(); port }).use { f ->
            f.run().use {
                f.functions.invoke(ConnectionFunctions.Reconnect)
                val change = Worker { f.store.set(SerialSettings.Baud to 57_600) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val closing = CountDownLatch(1)
                broker.subscribe("closing-detached", { it is ConnectionStateChanged }) { closing.countDown() }.use {
                    val finished = CountDownLatch(1)
                    val closer = Worker { f.runtime.close(); finished.countDown() }
                    try {
                        assertTrue(closing.await(5, TimeUnit.SECONDS))
                        assertEquals(1L, finished.count, "detached serial cleanup is still in flight")
                    } finally { f.ports.releaseStartup(); resume.countDown() }
                    assertNull(change.finish())
                    assertNull(closer.finish())
                    assertEquals(1, factories.get())
                    assertEquals(1, port.closes.get())
                    port.stopped?.invoke(null)
                    f.store.set(SerialSettings.Baud to 115_200)
                    assertEquals(1, factories.get())
                }
            }
        }
    }

    @Test fun `close from a live IO callback fails fast rather than waiting on itself`() {
        lateinit var f: Fixture
        val failure = AtomicReference<Throwable>()
        f = Fixture(source = {
            failure.set(runCatching { f.runtime.close() }.exceptionOrNull())
            emptyList()
        })
        f.use {
            f.run().use {
                assertTrue(f.functions.invoke(ConnectionFunctions.RefreshPorts).isEmpty())
                assertIs<IllegalStateException>(failure.get())
                assertEquals(4, f.functions.keys().size)
            }
        }
    }

    @Test fun `reader stop during initialization and stale callbacks have a single cleanup owner`() {
        lateinit var port: Port
        port = Port(initializing = { port.stopped?.invoke(IllegalStateException("reader stopped")) })
        Fixture(factory = { port }).use { f ->
            f.run().use {
                f.functions.invoke(ConnectionFunctions.Reconnect)
                assertEquals(1, port.closes.get())
                port.stopped?.invoke(null)
                assertEquals(1, port.closes.get())
                assertEquals(4, f.functions.keys().size)
            }
            assertEquals(1, port.closes.get())
        }
    }

    @Test fun `active reader callback releases resources once and late callbacks after shutdown are ignored`() {
        val port = Port()
        Fixture(factory = { port }).use { f ->
            f.run().use {
                f.functions.invoke(ConnectionFunctions.Reconnect)
                port.stopped?.invoke(IllegalStateException("lost carrier"))
                assertEquals(1, port.closes.get())
                port.stopped?.invoke(null)
                assertEquals(1, port.closes.get())
            }
            port.stopped?.invoke(null)
            assertEquals(1, port.closes.get())
            assertTrue(f.functions.keys().isEmpty())
        }
    }
}
