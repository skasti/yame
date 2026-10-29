package no.skasti.yame.ui

import no.skasti.yame.config.InstalledConfigTest
import com.github.ajalt.mordant.terminal.*
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.lifecycle.ApplicationEventLifecycle
import no.skasti.yame.logging.LogEntry
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.logging.YameLogLevel
import no.skasti.yame.observer.*
import no.skasti.yame.config.ConfigStore
import no.skasti.yame.serial.SerialSettings
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Exercises the production subscription, including its remaining-budget close delegation. */
class TuiShutdownIntegrationTest : InstalledConfigTest() {
    private val timeout = Duration.ofSeconds(5)
    private lateinit var broker: EventBroker
    private lateinit var installation: AutoCloseable

    @BeforeTest fun install() {
        broker = EventBroker()
        installation = YameEvents.install(broker)
    }

    @AfterTest fun release() {
        broker.close()
        assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(timeout))
        installation.close()
    }

    @Test fun `concurrent shutdown drains captured and queued broker callbacks through TUI before terminal teardown`() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val brokerClosing = CountDownLatch(1)
        val terminal = BlockedTerminal()
        val config = ConfigStore()
        val reports = CopyOnWriteArrayList<String>()
        val exits = CopyOnWriteArrayList<Int>()
        broker.subscribe("pause-before-tui") {
            if ((it as LogEntry).message == "captured-before-close") {
                callbackEntered.countDown()
                check(releaseCallback.await(5, TimeUnit.SECONDS))
            }
        }
        val app = run { installConfig(config); InteractiveYameApplication(FunctionStore(), terminal.terminal) }
        val owner = ApplicationEventLifecycle(broker, sealFacade = {}, report = reports::add, terminate = exits::add,
            awaitBrokerClosed = { remaining -> brokerClosing.countDown(); broker.awaitClosed(remaining) })
        owner.consumer(app)
        owner.producer(AutoCloseable { YameEvents.publish(LogEntry(YameLogModule.PPP, YameLogLevel.INFO, "producer-final")) })
        val failures = CopyOnWriteArrayList<Throwable>()
        val closers = List(2) { Thread { try { owner.close() } catch (error: Throwable) { failures += error } } }
        try {
            app.start()
            terminal.entered.awaitReady()
            YameEvents.publish(LogEntry(YameLogModule.PPP, YameLogLevel.INFO, "captured-before-close"))
            callbackEntered.awaitReady()
            YameEvents.publish(LogEntry(YameLogModule.PPP, YameLogLevel.INFO, "queued-before-close"))
            closers.forEach(Thread::start)
            brokerClosing.awaitReady()
            assertFalse(broker.status.terminated, "the in-flight callback must finish before consumer close")
            assertEquals(1L, terminal.tornDown.count)
            releaseCallback.countDown()
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(timeout))
            assertEquals(broker.status.lastAcceptedSequence, broker.status.lastDeliveredSequence)
            assertEquals(0L, broker.status.subscriberFailures)
            assertTrue(closers.all { it.isAlive }, "close must still wait for accepted TUI work")
            assertEquals(1L, terminal.tornDown.count)
            terminal.release.countDown()
            closers.forEach { it.join(5_000) }
            assertTrue(closers.none { it.isAlive })
            assertTrue(failures.isEmpty(), failures.toString())
            assertTrue(reports.isEmpty(), reports.toString())
            assertTrue(exits.isEmpty(), exits.toString())
            assertNull(app.fatalFailure)
            terminal.assertDrained("captured-before-close", "queued-before-close", "producer-final")
            val prints = terminal.prints.size
            app.start()
            app.close()
            config.set(SerialSettings.Baud to 57600)
            assertFailsWith<BrokerClosedException> { YameEvents.publish(LogEntry(YameLogModule.PPP, YameLogLevel.INFO, "too-late")) }
            assertEquals(prints, terminal.prints.size, "no terminal IO after teardown")
        } finally {
            releaseCallback.countDown(); terminal.release.countDown()
            closers.filter { it.state != Thread.State.NEW }.forEach { it.join(5_000) }
            owner.close()
            app.close()
        }
    }

    @Test fun `expired application budget cannot give a blocked terminal another three seconds to drain`() {
        val terminal = BlockedTerminal()
        val app = run { installConfig(ConfigStore()); InteractiveYameApplication(FunctionStore(), terminal.terminal) }
        val producerEntered = CountDownLatch(1)
        val releaseProducer = CountDownLatch(1)
        val consumersFinished = CountDownLatch(1)
        val cleanupFinished = CountDownLatch(1)
        val exits = LinkedBlockingQueue<Int>()
        val owner = ApplicationEventLifecycle(broker, timeout = Duration.ofMillis(200),
            sealFacade = { cleanupFinished.countDown() }, report = {}, terminate = { exits.offer(it) })
        // Reverse cleanup order: the sentinel runs after the real presentation close returns.
        owner.consumer(AutoCloseable { consumersFinished.countDown() })
        owner.consumer(app)
        owner.producer(AutoCloseable {
            producerEntered.countDown(); check(releaseProducer.await(5, TimeUnit.SECONDS))
        })
        val closer = Thread { owner.close() }
        try {
            app.start()
            terminal.entered.awaitReady()
            YameEvents.publish(LogEntry(YameLogModule.PPP, YameLogLevel.INFO, "accepted-before-deadline"))
            assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(timeout))
            closer.start()
            producerEntered.awaitReady()
            assertEquals(1, exits.poll(2, TimeUnit.SECONDS), "the deadline cannot require producer or TUI progress")
            closer.join(2_000)
            assertFalse(closer.isAlive)
            assertEquals(1L, terminal.tornDown.count)
            // The injected terminate returns. Let cleanup continue with an exhausted budget.
            releaseProducer.countDown()
            consumersFinished.awaitReady()
            cleanupFinished.awaitReady()
            assertNotNull(app.fatalFailure, "the zero remaining TUI drain budget must be enforced")
            assertEquals(1L, terminal.release.count, "consumer cleanup returned while rendering remained blocked")
            assertEquals(1L, terminal.tornDown.count)
            terminal.release.countDown()
            assertFailsWith<IllegalStateException> { app.close() }
            terminal.assertDrained("accepted-before-deadline")
        } finally {
            releaseProducer.countDown(); terminal.release.countDown()
            if (closer.state != Thread.State.NEW) closer.join(5_000)
            runCatching { app.close() }
        }
    }

    @Test fun `broker isolated TUI overload still enforces fatal deadline while terminal rendering is blocked`() {
        val terminal = BlockedTerminal()
        val app = run { installConfig(ConfigStore()); InteractiveYameApplication(FunctionStore(), terminal.terminal) }
        val exits = LinkedBlockingQueue<Int>()
        val reports = CopyOnWriteArrayList<String>()
        val owner = ApplicationEventLifecycle(broker, timeout = Duration.ofMillis(200),
            sealFacade = {}, report = reports::add, terminate = { exits.offer(it) })
        owner.consumer(app)
        try {
            app.start()
            terminal.entered.awaitReady()
            repeat(256) { YameEvents.publish(LogEntry(YameLogModule.PPP, YameLogLevel.INFO, "accepted-$it")) }
            assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(timeout))
            YameEvents.publish(LogEntry(YameLogModule.PPP, YameLogLevel.INFO, "overflow"))
            assertEquals(1, exits.poll(2, TimeUnit.SECONDS))
            assertNotNull(app.fatalFailure)
            assertTrue(reports.any { it.contains("fatal presentation failure") })
            assertEquals(1L, broker.status.subscriberFailures)
            assertNull(broker.status.failure, "the broker's separate fatal contract is unchanged")
            assertEquals(1L, terminal.release.count)
            assertEquals(1L, terminal.tornDown.count, "the blocked worker cannot delay independent fatal exit")
        } finally {
            terminal.release.countDown()
            assertFailsWith<IllegalStateException> { app.close() }
        }
        terminal.assertDrained("accepted-255")
    }

    private fun CountDownLatch.awaitReady() = assertTrue(await(2, TimeUnit.SECONDS))

    private class BlockedTerminal {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tornDown = CountDownLatch(1)
        val prints = CopyOnWriteArrayList<String>()
        private val first = AtomicBoolean(true)
        private val recorder = TerminalRecorder(outputInteractive = true, inputInteractive = false)
        val terminal = Terminal(terminalInterface = object : TerminalInterface by recorder {
            override fun completePrintRequest(request: PrintRequest) {
                if (request.text.contains("YAME") && first.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                prints += request.text
                recorder.completePrintRequest(request)
                if (request.text.contains("\u001B[?1049l")) tornDown.countDown()
            }
        })

        fun assertDrained(vararg messages: String) {
            assertEquals(0L, tornDown.count)
            assertTrue(prints.last().contains("\u001B[?1049l"), "teardown must be the last terminal write")
            val frames = prints.dropLast(1)
            val positions = messages.map { message -> frames.indexOfFirst { it.contains(message) }.also { assertTrue(it >= 0, message) } }
            assertEquals(positions.sorted(), positions, "accepted observations must be applied in FIFO order")
        }
    }
}
