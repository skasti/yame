package no.skasti.yame.runtime

import no.skasti.yame.config.InstalledConfigTest
import no.skasti.yame.config.ConfigStore
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.lifecycle.ApplicationFunctions
import no.skasti.yame.logging.*
import no.skasti.yame.observer.*
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.serial.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

class YameRuntimeLifecycleTest : InstalledConfigTest() {
    private lateinit var broker: EventBroker
    private lateinit var installation: AutoCloseable
    @BeforeTest fun install() {
        broker = EventBroker(initialCapacity = 4096, initialPublishTimeout = java.time.Duration.ofMillis(10), initialMaxSubscribers = 64)
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
        val carrierDrops = AtomicInteger()
        @Volatile var stopped: ((Throwable?) -> Unit)? = null
        override fun open() { opens.incrementAndGet(); opening() }
        override fun setCarrierPresent(carrierPresent: Boolean) { if (!carrierPresent) carrierDrops.incrementAndGet() }
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
        val store: ConfigStore = ConfigStore(listOf(no.skasti.yame.serial.SerialSettings.Port to "COM1")),
    ) : AutoCloseable {
        val directory = Files.createTempDirectory("yame-runtime-lifecycle")
        val logs = YameLogManager(no.skasti.yame.logging.LoggingConfig(), directory)
        val functions = FunctionStore()
        val ports = RuntimeTestPorts(source)
        private val configInstallation = ConfigStore.install(store)
        val runtime = YameRuntime(functions, logs, NavigationResourceRegistry(), ports::scan,
            connectionFactory = { _, _, _, _ -> factory() })
        var expectedCloseFailure: Throwable? = null
        fun run() = RuntimeTestRun(runtime, ports)
        override fun close() {
            ports.releaseStartup()
            try {
                val expected = expectedCloseFailure
                if (expected == null) runtime.close() else assertSame(expected, assertFails { runtime.close() })
            } finally { logs.close(); configInstallation.close(); directory.toFile().deleteRecursively() }
        }
    }

    private class Worker(action: () -> Unit) {
        private val failure = AtomicReference<Throwable>()
        private val worker = thread { try { action() } catch (error: Throwable) { failure.set(error) } }
        fun interrupt() = worker.interrupt()
        fun finish(): Throwable? {
            worker.join(5_000)
            assertFalse(worker.isAlive, "operation deadlocked")
            return failure.get()
        }
    }

    // The concrete stores return non-throwing handles. Decorate the owned handles
    // only in tests, without adding failure-injection APIs to runtime or the stores.
    private fun decorateSubscription(runtime: YameRuntime, decorate: (AutoCloseable) -> AutoCloseable) {
        val field = YameRuntime::class.java.getDeclaredField("configurationSubscription").apply { isAccessible = true }
        field.set(runtime, decorate(field.get(runtime) as AutoCloseable))
    }

    @Suppress("UNCHECKED_CAST")
    private fun decorateRegistration(runtime: YameRuntime, index: Int, decorate: (AutoCloseable) -> AutoCloseable) {
        val field = YameRuntime::class.java.getDeclaredField("functionRegistrations").apply { isAccessible = true }
        val registrations = field.get(runtime) as MutableList<AutoCloseable>
        registrations[index] = decorate(registrations[index])
    }

    private fun closingSignal(runtime: YameRuntime): CountDownLatch = CountDownLatch(1).also { signal ->
        decorateRegistration(runtime, 3) { original -> AutoCloseable { original.close(); signal.countDown() } }
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
            store = ConfigStore()).use { f ->
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
                val closing = closingSignal(f.runtime)
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
                val closing = closingSignal(f.runtime)
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

    @Test fun `early cleanup failures still stop monitor and release IO before all closers receive the same failure`() {
        val subscriptionFailure = IllegalStateException("subscription cleanup")
        val firstRegistrationFailure = IllegalStateException("first registration cleanup")
        val nextRegistrationFailure = IllegalStateException("next registration cleanup")
        val serialFailure = IllegalStateException("serial cleanup")
        val monitorEntered = CountDownLatch(1)
        val monitorReleased = CountDownLatch(1)
        val monitorInterrupted = CountDownLatch(1)
        val monitor = AtomicReference<Thread>()
        val serialClosing = CountDownLatch(1)
        val releaseSerial = CountDownLatch(1)
        val port = Port(closing = {
            serialClosing.countDown()
            check(releaseSerial.await(5, TimeUnit.SECONDS))
            throw serialFailure
        })
        val observed = CopyOnWriteArrayList<ConnectionStateChanged>()
        broker.subscribeTyped<ConnectionStateChanged>("cleanup-failures") { observed += it }.use {
            Fixture(source = {
                if (Thread.currentThread().name == "yame-port-monitor") {
                    monitor.set(Thread.currentThread())
                    monitorEntered.countDown()
                    try { check(monitorReleased.await(5, TimeUnit.SECONDS)) }
                    catch (error: InterruptedException) { monitorInterrupted.countDown(); throw error }
                }
                emptyList()
            }, factory = { port }, store = ConfigStore()).use { f ->
                val runner = Worker { f.runtime.run() }
                f.ports.awaitStartup()
                f.ports.releaseStartup()
                assertTrue(monitorEntered.await(5, TimeUnit.SECONDS))
                // The monitor is now inside an admitted port scan. Connect on this
                // thread so shutdown must independently clean both resource owners.
                f.store.set(SerialSettings.Port to "COM1")
                assertEquals(1, port.reads.get())
                decorateSubscription(f.runtime) { original -> AutoCloseable { original.close(); throw subscriptionFailure } }
                decorateRegistration(f.runtime, 0) { original -> AutoCloseable { original.close(); throw firstRegistrationFailure } }
                decorateRegistration(f.runtime, 1) { original -> AutoCloseable { original.close(); throw nextRegistrationFailure } }
                f.expectedCloseFailure = subscriptionFailure
                val first = Worker {
                    val failure = assertFails { f.runtime.close() }
                    assertSame(subscriptionFailure, failure)
                    // Even the original closing thread must see the latched result
                    // on a later close, rather than appearing to succeed.
                    assertSame(failure, assertFails { f.runtime.close() })
                    throw failure
                }
                assertTrue(serialClosing.await(5, TimeUnit.SECONDS))
                val secondEntered = CountDownLatch(1)
                val secondDone = CountDownLatch(1)
                val secondInterrupted = AtomicReference<Boolean>()
                val second = Worker {
                    secondEntered.countDown()
                    try { f.runtime.close() } finally {
                        secondInterrupted.set(Thread.currentThread().isInterrupted)
                        secondDone.countDown()
                    }
                }
                try {
                    assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
                    assertTrue(monitorInterrupted.await(5, TimeUnit.SECONDS), "monitor interruption was skipped")
                    assertTrue(f.functions.keys().isEmpty(), "every registration must be released")
                    second.interrupt()
                    assertEquals(1L, secondDone.count, "physical cleanup has not completed")
                } finally { releaseSerial.countDown(); monitorReleased.countDown() }
                assertSame(subscriptionFailure, first.finish())
                assertSame(subscriptionFailure, second.finish())
                assertEquals(true, secondInterrupted.get())
                assertSame(subscriptionFailure, runner.finish())
                assertEquals(listOf(firstRegistrationFailure, nextRegistrationFailure, serialFailure),
                    subscriptionFailure.suppressed.toList())
                assertFalse(monitor.get().isAlive)
                assertEquals(1, port.closes.get())
                assertEquals(2, port.carrierDrops.get(), "modem close follows failed serial close")
                port.stopped?.invoke(null)
                assertEquals(1, port.closes.get(), "late serial callbacks cannot reclaim detached IO")
                val level = f.logs.level(YameLogModule.MODEM)
                f.store.set(LogSettings.Levels.getValue(YameLogModule.MODEM) to YameLogLevel.DEBUG)
                assertEquals(level, f.logs.level(YameLogModule.MODEM), "configuration subscription was released")
                assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(Duration.ofSeconds(5)))
                val identity = observed.single { it.state == ConnectionState.CONNECTED }.connectionId
                assertEquals(1, observed.count { it.connectionId == identity && it.state == ConnectionState.DISCONNECTED })
            }
        }
    }

    @Test fun `failed serial disconnect closes modem and publishes logical disconnection only once`() {
        val failure = IllegalStateException("serial close failed")
        val port = Port(closing = { throw failure })
        val observed = CopyOnWriteArrayList<YameEvent>()
        broker.subscribe("failed-disconnect") { observed += it }.use {
            Fixture(factory = { port }).use { f ->
                f.run().use {
                    f.functions.invoke(ConnectionFunctions.Reconnect)
                    assertSame(failure, assertFails { f.functions.invoke(ConnectionFunctions.Disconnect) })
                    assertEquals(2, port.carrierDrops.get())
                    port.stopped?.invoke(null)
                    assertEquals(1, port.closes.get())
                }
                assertTrue(f.functions.keys().isEmpty())
                assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(Duration.ofSeconds(5)))
                val states = observed.filterIsInstance<ConnectionStateChanged>()
                val identity = states.single { it.state == ConnectionState.CONNECTED }.connectionId
                assertEquals(1, states.count { it.connectionId == identity && it.state == ConnectionState.DISCONNECTED })
                assertFalse(observed.filterIsInstance<LogEntry>().any { it.message == "Serial connection closed" },
                    "failed physical cleanup must not be reported as successful")
            }
        }
    }

    @Test fun `disconnect publication failure completes its transition and permits subsequent shutdown`() {
        val port = Port()
        Fixture(factory = { port }).use { f ->
            f.run().use {
                f.functions.invoke(ConnectionFunctions.Reconnect)
                broker.close() // Reject publication through the real broker, without a runtime event seam.
                assertFailsWith<BrokerClosedException> { f.functions.invoke(ConnectionFunctions.Disconnect) }
                assertEquals(1, port.closes.get())
                assertEquals(2, port.carrierDrops.get())
                port.stopped?.invoke(null)
            }
            assertTrue(f.functions.keys().isEmpty())
            assertEquals(1, port.closes.get(), "shutdown must not reclaim the already detached connection")
        }
    }

    @Test fun `serial and publication failures retain primary error and do not strand transition accounting`() {
        val failure = IllegalStateException("physical close failed")
        val port = Port(closing = { throw failure })
        Fixture(factory = { port }).use { f ->
            f.run().use {
                f.functions.invoke(ConnectionFunctions.Reconnect)
                broker.close()
                assertSame(failure, assertFails { f.functions.invoke(ConnectionFunctions.Disconnect) })
                assertIs<BrokerClosedException>(failure.suppressed.single())
                assertEquals(2, port.carrierDrops.get())
            }
            assertTrue(f.functions.keys().isEmpty())
            assertEquals(1, port.closes.get())
        }
    }

    @Test fun `shutdown publication failure is shared after connection and startup transitions finish`() {
        val port = Port()
        Fixture(factory = { port }).use { f ->
            val runner = Worker { f.runtime.run() }
            f.ports.awaitStartup()
            f.functions.invoke(ConnectionFunctions.Reconnect)
            val closing = closingSignal(f.runtime)
            broker.close()
            val closer = Worker { f.runtime.close() }
            try { assertTrue(closing.await(5, TimeUnit.SECONDS)) } finally { f.ports.releaseStartup() }
            val failure = closer.finish()
            assertIs<BrokerClosedException>(failure)
            f.expectedCloseFailure = failure
            assertSame(failure, runner.finish())
            assertSame(failure, assertFails { f.runtime.close() })
            assertTrue(f.functions.keys().isEmpty())
            assertEquals(1, port.closes.get())
            assertEquals(2, port.carrierDrops.get())
        }
    }

    @Test fun `interrupted cleanup still waits for owned IO and restores interruption after shutdown`() {
        Fixture().use { f ->
            val runner = Worker { f.runtime.run() }
            f.ports.awaitStartup()
            val closing = CountDownLatch(1)
            val releaseHandle = CountDownLatch(1)
            val interruptedHandle = CountDownLatch(1)
            decorateRegistration(f.runtime, 0) { original -> AutoCloseable {
                original.close()
                closing.countDown()
                try { check(releaseHandle.await(5, TimeUnit.SECONDS)) }
                catch (error: InterruptedException) { interruptedHandle.countDown(); throw error }
            } }
            val done = CountDownLatch(1)
            val wasInterrupted = AtomicReference<Boolean>()
            val closer = Worker {
                try { f.runtime.close() } finally {
                    wasInterrupted.set(Thread.currentThread().isInterrupted)
                    done.countDown()
                }
            }
            try {
                assertTrue(closing.await(5, TimeUnit.SECONDS))
                closer.interrupt()
                assertTrue(interruptedHandle.await(5, TimeUnit.SECONDS))
                assertEquals(1L, done.count, "initial scan still owns a resource transition")
            } finally { releaseHandle.countDown(); f.ports.releaseStartup() }
            val failure = closer.finish()
            assertIs<InterruptedException>(failure)
            f.expectedCloseFailure = failure
            assertSame(failure, runner.finish())
            assertEquals(true, wasInterrupted.get())
            assertTrue(f.functions.keys().isEmpty())
        }
    }
}
