package no.skasti.yame.ui

import com.github.ajalt.mordant.terminal.*
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.persistence.YameConfiguration
import no.skasti.yame.persistence.applicationConfigStore
import no.skasti.yame.serial.ConnectionState
import no.skasti.yame.serial.ConnectionStateChanged
import no.skasti.yame.serial.SerialSettings
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class TuiWorkerIntegrationTest {
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
