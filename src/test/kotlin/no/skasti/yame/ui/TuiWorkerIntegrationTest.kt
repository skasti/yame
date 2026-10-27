package no.skasti.yame.ui

import com.github.ajalt.mordant.terminal.*
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.lifecycle.ApplicationEventLifecycle
import no.skasti.yame.observer.EventBroker
import no.skasti.yame.persistence.YameConfiguration
import no.skasti.yame.persistence.applicationConfigStore
import no.skasti.yame.serial.ConnectionState
import no.skasti.yame.serial.ConnectionStateChanged
import no.skasti.yame.serial.SerialSettings
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.time.Duration
import kotlin.test.*

class TuiWorkerIntegrationTest {
    private fun TuiYameObserver.chooseNextBaud() {
        "/baud".forEach { handleKeyAndAwait(it.toString()) }
        handleKeyAndAwait("Enter")
        handleKeyAndAwait("ArrowDown")
        assertEquals("57600", paletteSnapshot()?.let { it.options[it.selectedIndex].value })
    }

    @Test fun `baud command commits synchronously and reentrant notification updates presentation in FIFO order`() {
        val recorder = TerminalRecorder(outputInteractive = true, inputInteractive = false)
        val savingThread = AtomicReference<Thread>()
        val config = applicationConfigStore(YameConfiguration(), save = { savingThread.set(Thread.currentThread()) })
        TuiYameObserver(config, FunctionStore(), Terminal(terminalInterface = recorder)).use { ui ->
            ui.start()
            ui.chooseNextBaud()
            ui.handleKeyAndAwait("Enter")
            ui.awaitIdle() // The configuration callback can be admitted after the first read barrier.
            assertEquals(57600, config[SerialSettings.Baud])
            assertEquals("yame-tui-worker", savingThread.get().name)
            assertContains(recorder.output(), "57600")
            assertNull(ui.fatalFailure)
            "/baud".forEach { ui.handleKeyAndAwait(it.toString()) }
            ui.handleKeyAndAwait("Enter")
            assertEquals("current", ui.paletteSnapshot()?.options?.single { it.value == "57600" }?.description)
        }
    }

    @Test fun `full queue during real baud change is independently fatal despite ConfigStore callback isolation`() {
        val saving = CountDownLatch(1)
        val save = CountDownLatch(1)
        val caught = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val callbackFailure = AtomicReference<Throwable>()
        val exits = LinkedBlockingQueue<Int>()
        val reports = CopyOnWriteArrayList<String>()
        val config = applicationConfigStore(YameConfiguration(), save = {
            saving.countDown(); check(save.await(5, TimeUnit.SECONDS))
        }, onSubscriberFailure = {
            callbackFailure.set(it); caught.countDown()
            check(releaseCallback.await(5, TimeUnit.SECONDS))
        })
        val ui = TuiYameObserver(config, FunctionStore(), Terminal(interactive = false))
        val broker = EventBroker()
        val owner = ApplicationEventLifecycle(broker, timeout = Duration.ofMillis(200),
            sealFacade = {}, report = reports::add, terminate = { exits.offer(it) })
        owner.consumer(ui)
        try {
            ui.chooseNextBaud()
            ui.handleKey("Enter")
            assertTrue(saving.await(2, TimeUnit.SECONDS))
            // The worker is inside ConfigStore.set(), before synchronous onChange delivery.
            repeat(256) { ui.handleKey("") }
            save.countDown()
            assertTrue(caught.await(2, TimeUnit.SECONDS), "reentrant admission must fail rather than wait on itself")
            assertEquals(57600, config[SerialSettings.Baud], "the commit precedes subscriber notification")
            assertSame(callbackFailure.get(), ui.fatalFailure)
            assertEquals(1, exits.poll(2, TimeUnit.SECONDS))
            assertEquals(1L, releaseCallback.count, "supervision cannot require the TUI worker to return")
            assertTrue(reports.any { it.contains("fatal presentation failure") })
            assertNull(broker.status.failure)
        } finally {
            save.countDown(); releaseCallback.countDown()
            assertFailsWith<IllegalStateException> { ui.close() }
            broker.close(); broker.awaitClosed(Duration.ofSeconds(2))
        }
    }

    @Test fun `worker initiated close can drain an already accepted start without stopping its timer early`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val functions = FunctionStore()
        lateinit var ui: TuiYameObserver
        functions.register(no.skasti.yame.serial.ConnectionFunctions.Disconnect) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            ui.close()
        }.use {
            ui = TuiYameObserver(applicationConfigStore(YameConfiguration()), functions, Terminal(interactive = false))
            try {
                "/disconnect".forEach { ui.handleKey(it.toString()) }
                ui.handleKey("Enter")
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                ui.start()
                release.countDown()
                ui.close()
                assertNull(ui.fatalFailure)
            } finally { release.countDown(); ui.close() }
        }
    }

    @Test fun `blocked terminal does not block observations config or keyboard and teardown is last output`() {
        val recorder = TerminalRecorder(outputInteractive = true, inputInteractive = false)
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val writers = CopyOnWriteArrayList<Thread>()
        val output = object : TerminalInterface by recorder {
            override fun completePrintRequest(request: PrintRequest) {
                writers += Thread.currentThread()
                if (request.text.contains("YAME") && first.compareAndSet(true, false)) {
                    blocked.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                recorder.completePrintRequest(request)
            }
        }
        val config = applicationConfigStore(YameConfiguration())
        val ui = TuiYameObserver(config, FunctionStore(), Terminal(terminalInterface = output))
        try {
            ui.start()
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            // These callbacks return while the terminal is still blocked.
            ui.onEvent(ConnectionStateChanged(1, ConnectionState.CONNECTED))
            config.set(SerialSettings.Baud to 57600)
            ui.handleKey("/")
            release.countDown()
            assertTrue(ui.connectedSnapshot())
            assertNotNull(ui.paletteSnapshot())
            ui.close()
            assertEquals(1, writers.toSet().size)
            assertEquals("yame-tui-worker", writers.first().name)
            assertContains(recorder.output(), "57600")
            val prints = writers.size
            ui.onEvent(ConnectionStateChanged(1, ConnectionState.DISCONNECTED))
            ui.handleKey("Escape")
            config.set(SerialSettings.Baud to 115200)
            assertEquals(prints, writers.size)
            assertTrue(ui.connectedSnapshot())
        } finally { release.countDown(); ui.close() }
    }
}
