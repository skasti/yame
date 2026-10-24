package no.skasti.yame.ui

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.persistence.YameConfiguration
import no.skasti.yame.persistence.applicationConfigStore
import no.skasti.yame.ppp.session.*
import no.skasti.yame.serial.*
import no.skasti.yame.logging.DiagnosticMessage
import no.skasti.yame.observer.EventBroker
import no.skasti.yame.observer.EventBrokerConfig
import no.skasti.yame.observer.BrokerWaitResult
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RuntimeObservationProjectionTest {
    private fun observer(functions: FunctionStore = FunctionStore()) = TuiYameObserver(
        applicationConfigStore(YameConfiguration()), functions, Terminal(interactive = false))

    @Test fun `delayed old session teardown and reused flow IDs cannot modify the new connection`() {
        observer().use { ui ->
            val old = PppSessionIdentity(1, 1)
            val current = PppSessionIdentity(2, 2)
            ui.onEvent(ConnectionStateChanged(1, ConnectionState.CONNECTED))
            ui.onEvent(TransferStarted("same", "old", "test", TransferKind.TCP, old))
            ui.onEvent(ConnectionStateChanged(2, ConnectionState.CONNECTED))
            ui.onEvent(TransferStarted("same", "new", "test", TransferKind.TCP, current))
            ui.onEvent(TransferStateChanged("same", TransferState.OPEN, session = current))
            ui.onEvent(PppSessionEnded(old))
            ui.onEvent(ConnectionStateChanged(1, ConnectionState.DISCONNECTED))
            ui.onEvent(TransferStateChanged("same", TransferState.CLOSED, session = old))
            ui.onEvent(TransferBytes("same", TransferDirection.TO_HOST, 100, old))
            val transfer = ui.transferProjectionSnapshot().single()
            assertTrue(ui.connectedSnapshot())
            assertEquals("new", transfer.destination)
            assertEquals(TransferState.OPEN, transfer.state)
            assertEquals(0, transfer.toHostBytes)
        }
    }

    @Test fun `consumer defers transfer details before start and session end dominates delayed observations`() {
        observer().use { ui ->
            val session = PppSessionIdentity(2, 3)
            ui.onEvent(TransferBytes("flow", TransferDirection.TO_PEER, 12, session))
            ui.onEvent(TransferStateChanged("flow", TransferState.OPEN, session = session))
            ui.onEvent(TransferStarted("flow", "host", "test", TransferKind.TCP, session))
            // Connection notification may follow its PPP observations.
            ui.onEvent(ConnectionStateChanged(2, ConnectionState.CONNECTED))
            assertEquals(12, ui.transferProjectionSnapshot().single().toPeerBytes)
            ui.onEvent(PppSessionEnded(session))
            ui.onEvent(TransferStarted("flow", "obsolete", "test", TransferKind.TCP, session))
            assertEquals(TransferState.CLOSED, ui.transferProjectionSnapshot().single().state)
            ui.onEvent(ConnectionStateChanged(2, ConnectionState.DISCONNECTED))
            ui.onEvent(ConnectionStateChanged(2, ConnectionState.CONNECTED))
            assertFalse(ui.connectedSnapshot())
        }
    }

    @Test fun `port palette uses typed refresh result and automatic changes refresh an open palette`() {
        val functions = FunctionStore()
        functions.register(ConnectionFunctions.RefreshPorts) { listOf(SerialPortDescriptor("COM1", "one")) }
        observer(functions).use { ui ->
            "/port".forEach { ui.handleKey(it.toString()) }; ui.handleKey("Enter")
            assertEquals(listOf("COM1"), ui.paletteSnapshot()!!.options.map { it.value })
            ui.onEvent(AvailablePortsChanged(listOf(SerialPortDescriptor("COM2", "two"))))
            assertEquals(listOf("COM2"), ui.paletteSnapshot()!!.options.map { it.value })
        }
    }

    @Test fun `dependency buffering is bounded and exposes a local projection failure`() {
        observer().use { ui ->
            repeat(128) { ui.onEvent(TransferBytes("unknown", TransferDirection.TO_HOST, 1)) }
            assertFailsWith<IllegalStateException> { ui.onEvent(TransferBytes("unknown", TransferDirection.TO_HOST, 1)) }
            assertNotNull(ui.observationFailure)
        }
    }
    @Test fun `actual TUI callback may block broker but never executes on publisher`() {
        val broker = EventBroker(EventBrokerConfig(capacity = 1))
        val entered = CountDownLatch(1)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        observer().use { ui ->
            val holder = Thread {
                synchronized(ui) { locked.countDown(); release.await(5, TimeUnit.SECONDS) }
            }.apply { start() }
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS))
                broker.subscribe("actual-tui") { entered.countDown(); ui.onEvent(it) }
                broker.publish(DiagnosticMessage("render"))
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                broker.publish(DiagnosticMessage("pending"))
                assertEquals(1, broker.status.queuedEvents)
                assertEquals("actual-tui", broker.status.activeSubscriber)
            } finally { release.countDown(); holder.join(5_000) }
            assertEquals(BrokerWaitResult.COMPLETED,
                broker.drain().await(Duration.ofSeconds(5)))
        }
        broker.close()
        assertEquals(BrokerWaitResult.COMPLETED,
            broker.awaitClosed(Duration.ofSeconds(5)))
    }

}
