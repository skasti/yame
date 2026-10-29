package no.skasti.yame.ui

import no.skasti.yame.config.InstalledConfigTest
import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.config.ConfigStore
import no.skasti.yame.ppp.session.*
import no.skasti.yame.serial.*
import no.skasti.yame.logging.LogEntry
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.logging.YameLogLevel
import no.skasti.yame.observer.EventBroker
import no.skasti.yame.observer.EventBrokerConfig
import no.skasti.yame.observer.BrokerWaitResult
import no.skasti.yame.ppp.proxy.TransferResourceChanged
import no.skasti.yame.ppp.proxy.TransferResourcePhase
import java.net.URI
import java.time.Instant
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RuntimeObservationProjectionTest : InstalledConfigTest() {
    private fun observer(functions: FunctionStore = FunctionStore()) = run { installConfig(ConfigStore()); TuiYameObserver(functions, Terminal(interactive = false)) }

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
            val history = ui.transferProjectionSnapshot()
            assertEquals(TransferState.CLOSED, history.single { it.destination == "old" }.state)
            val transfer = history.single { it.destination == "new" }
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

    private fun metadata(identity: TransferIdentity, name: String, phase: TransferResourcePhase = TransferResourcePhase.RESPONSE,
                         request: Long = 1) = TransferResourceChanged(identity, request, phase,
        URI("http://download.test/original"), URI("https://download.test/$name"), name, Instant.EPOCH)

    @Test fun `resource metadata is deferred and response wins over delayed request without changing counters or state`() {
        observer().use { ui ->
            val session = PppSessionIdentity(1, 1)
            val identity = TransferIdentity.create(session, 0)
            ui.onEvent(metadata(identity, "WINZIP95.EXE"))
            ui.onEvent(metadata(identity, "original", TransferResourcePhase.REQUEST))
            ui.onEvent(TransferStarted("same", "host", "test", TransferKind.TCP, session, transferIdentity = identity))
            ui.onEvent(TransferBytes("same", TransferDirection.TO_PEER, 123, session, transferIdentity = identity))
            ui.onEvent(TransferStateChanged("same", TransferState.CLOSED, session = session, transferIdentity = identity))
            ui.onEvent(metadata(identity, "latest.exe", request = 2))
            ui.onEvent(metadata(identity, "old.exe"))
            val transfer = ui.transferProjectionSnapshot().single()
            assertEquals("latest.exe", transfer.resource?.displayFilename)
            assertEquals(123, transfer.toPeerBytes)
            assertEquals(TransferState.CLOSED, transfer.state)
        }
    }

    @Test fun `reused tuples generations and reconnects keep their metadata on their own row`() {
        observer().use { ui ->
            val session = PppSessionIdentity(1, 1)
            val first = TransferIdentity.create(session, 0)
            val reused = TransferIdentity.create(session, 0)
            val renegotiated = TransferIdentity.create(session, 1)
            val reconnected = TransferIdentity.create(PppSessionIdentity(2, 2), 0)
            for ((index, identity) in listOf(first, reused, renegotiated, reconnected).withIndex()) {
                ui.onEvent(TransferStarted("same", "host$index", "test", TransferKind.TCP,
                    identity.session, transferIdentity = identity))
                ui.onEvent(metadata(identity, "file$index.exe"))
            }
            // Previous session observations are rejected, and same-session old tuple
            // metadata cannot overwrite the later tuple's resource either.
            ui.onEvent(metadata(first, "obsolete.exe", request = 2))
            val history = ui.transferProjectionSnapshot()
            assertEquals(listOf("file0.exe", "file1.exe", "file2.exe", "file3.exe"), history.map { it.resource?.displayFilename })
            assertEquals(TransferState.CONNECTING, history.last().state)
        }
    }

    @Test fun `unknown metadata remains bounded and yields buffer capacity to required observations`() {
        observer().use { ui ->
            val session = PppSessionIdentity(1, 1)
            repeat(300) { ui.onEvent(metadata(TransferIdentity.create(session, 0), "unknown.exe")) }
            val identity = TransferIdentity.create(session, 0)
            ui.onEvent(TransferBytes("known", TransferDirection.TO_PEER, 99, session, transferIdentity = identity))
            ui.onEvent(TransferStarted("known", "host", "test", TransferKind.TCP, session, transferIdentity = identity))
            assertEquals(99, ui.transferProjectionSnapshot().single().toPeerBytes)
            assertNull(ui.observationFailure)
        }
    }

    @Test fun `legacy flow ID can start fresh after its previous transfer was retired`() {
        observer().use { ui ->
            val session = PppSessionIdentity(1, 1)
            ui.onEvent(TransferStarted("reused", "old", "test", TransferKind.TCP, session))
            ui.onEvent(TransferBytes("reused", TransferDirection.TO_HOST, 100, session))
            ui.onEvent(TransferBytes("reused", TransferDirection.TO_PEER, 200, session))
            ui.onEvent(TransferStateChanged("reused", TransferState.CLOSED, "old connection", session))
            repeat(20) { index ->
                ui.onEvent(TransferStarted("other$index", "other$index", "test", TransferKind.TCP, session))
            }
            assertEquals(20, ui.transferProjectionSnapshot().size)
            assertFalse(ui.transferProjectionSnapshot().any { it.flowId == "reused" })

            // Retirement still suppresses details until a new legacy start arrives.
            ui.onEvent(TransferBytes("reused", TransferDirection.TO_PEER, 999, session))
            ui.onEvent(TransferStateChanged("reused", TransferState.FAILED, session = session))
            ui.onEvent(TransferStarted("reused", "new", "test", TransferKind.TCP, session))
            val fresh = ui.transferProjectionSnapshot().single { it.flowId == "reused" }
            assertEquals("new", fresh.destination)
            assertEquals(TransferState.CONNECTING, fresh.state)
            assertEquals(0, fresh.toHostBytes)
            assertEquals(0, fresh.toPeerBytes)
            assertNull(fresh.detail)
            assertEquals(20, ui.transferProjectionSnapshot().size)

            ui.onEvent(TransferBytes("reused", TransferDirection.TO_HOST, 31, session))
            ui.onEvent(TransferBytes("reused", TransferDirection.TO_PEER, 47, session))
            ui.onEvent(TransferStateChanged("reused", TransferState.OPEN, "new connection", session))
            val updated = ui.transferProjectionSnapshot().single { it.flowId == "reused" }
            assertEquals(31, updated.toHostBytes)
            assertEquals(47, updated.toPeerBytes)
            assertEquals(TransferState.OPEN, updated.state)
            assertEquals("new connection", updated.detail)
            assertNull(ui.observationFailure)
        }
    }

    @Test fun `retirement retains only the latest 128 evicted transfer identities`() {
        observer().use { ui ->
            val session = PppSessionIdentity(1, 1)
            val identities = (0 until 149).map { TransferIdentity.create(session, 0) }
            fun start(index: Int) = ui.onEvent(TransferStarted("flow$index", "host$index", "test",
                TransferKind.TCP, session, transferIdentity = identities[index]))
            identities.forEachIndexed { index, identity ->
                start(index)
                ui.onEvent(TransferStateChanged("flow$index", TransferState.CLOSED,
                    session = session, transferIdentity = identity))
            }
            // Twenty retained rows leave 129 evictions: identity 0 has aged out
            // of retirement, while identities 1..128 are still protected.
            assertEquals(20, ui.transferProjectionSnapshot().size)
            start(1)
            start(128)
            assertFalse(ui.transferProjectionSnapshot().any { it.transferIdentity in identities.take(129) })
            start(0)
            assertTrue(ui.transferProjectionSnapshot().any { it.transferIdentity == identities[0] })
            assertEquals(20, ui.transferProjectionSnapshot().size)
            assertNull(ui.observationFailure)
        }
    }

    @Test fun `eviction prefers closed rows and late metadata never resurrects removed transfers`() {
        observer().use { ui ->
            val session = PppSessionIdentity(1, 1)
            val identities = (0..20).map { TransferIdentity.create(session, 0) }
            identities.forEachIndexed { index, identity ->
                ui.onEvent(TransferStarted("flow$index", "host$index", "test", TransferKind.TCP, session, transferIdentity = identity))
                if (index == 1) ui.onEvent(TransferStateChanged("flow1", TransferState.CLOSED,
                    session = session, transferIdentity = identity))
            }
            assertEquals(20, ui.transferProjectionSnapshot().size)
            assertTrue(ui.transferProjectionSnapshot().any { it.destination == "host0" })
            assertFalse(ui.transferProjectionSnapshot().any { it.destination == "host1" })
            repeat(300) { ui.onEvent(metadata(identities[1], "late.exe")) }
            repeat(129) {
                ui.onEvent(TransferBytes("flow1", TransferDirection.TO_PEER, 1, session,
                    transferIdentity = identities[1]))
                ui.onEvent(TransferStateChanged("flow1", TransferState.OPEN, session = session,
                    transferIdentity = identities[1]))
            }
            ui.onEvent(TransferStarted("flow1", "delayed", "test", TransferKind.TCP, session,
                transferIdentity = identities[1]))
            assertFalse(ui.transferProjectionSnapshot().any { it.transferIdentity == identities[1] })
            assertEquals(20, ui.transferProjectionSnapshot().size)
            assertNull(ui.observationFailure)
        }
    }

    @Test fun `port palette uses typed refresh result and automatic changes refresh an open palette`() {
        val functions = FunctionStore()
        functions.register(ConnectionFunctions.RefreshPorts) { listOf(SerialPortDescriptor("COM1", "one")) }
        observer(functions).use { ui ->
            "/port".forEach { ui.handleKeyAndAwait(it.toString()) }; ui.handleKeyAndAwait("Enter")
            assertEquals(listOf("COM1"), ui.paletteSnapshot()!!.options.map { it.value })
            ui.onEvent(AvailablePortsChanged(listOf(SerialPortDescriptor("COM2", "two"))))
            assertEquals(listOf("COM2"), ui.paletteSnapshot()!!.options.map { it.value })
        }
    }

    @Test fun `dependency buffering is bounded and exposes a local projection failure`() {
        val ui = observer()
        repeat(128) { ui.onEvent(TransferBytes("unknown", TransferDirection.TO_HOST, 1)) }
        ui.onEvent(TransferBytes("unknown", TransferDirection.TO_HOST, 1))
        assertFailsWith<IllegalStateException> { ui.close() }
        assertNotNull(ui.observationFailure)
        assertNotNull(ui.fatalFailure)
    }
    @Test fun `actual TUI admission proceeds without the observer monitor`() {
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
                broker.publish(LogEntry(YameLogModule.PPP, YameLogLevel.INFO, "render"))
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(Duration.ofSeconds(2)))
                broker.publish(LogEntry(YameLogModule.PPP, YameLogLevel.INFO, "pending"))
                assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(Duration.ofSeconds(2)))
                ui.awaitIdle()
                assertEquals(0, broker.status.queuedEvents)
            } finally { release.countDown(); holder.join(5_000) }
            assertEquals(BrokerWaitResult.COMPLETED,
                broker.drain().await(Duration.ofSeconds(5)))
        }
        broker.close()
        assertEquals(BrokerWaitResult.COMPLETED,
            broker.awaitClosed(Duration.ofSeconds(5)))
    }

}
