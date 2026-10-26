package no.skasti.yame.runtime

import no.skasti.yame.function.FunctionStore
import no.skasti.yame.lifecycle.ApplicationFunctions
import no.skasti.yame.logging.*
import no.skasti.yame.observer.*
import no.skasti.yame.persistence.YameConfiguration
import no.skasti.yame.persistence.applicationConfigStore
import no.skasti.yame.ppp.PppFrame
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.ppp.session.*
import no.skasti.yame.serial.*
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class RuntimeObservationIntegrationTest {
    private fun isolated(config: EventBrokerConfig = EventBrokerConfig(), body: (EventBroker) -> Unit) {
        val broker = EventBroker(config)
        val installation = YameEvents.install(broker)
        try { body(broker) } finally {
            broker.close()
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(Duration.ofSeconds(5)))
            installation.close()
        }
    }

    @Test fun `startup discovery reaches early subscribers and typed query returns immutable deduplicated snapshots`() = isolated { broker ->
        val root = Files.createTempDirectory("yame-ports")
        val source = java.util.concurrent.atomic.AtomicReference<List<SerialPortDescriptor>>(emptyList())
        val events = CopyOnWriteArrayList<AvailablePortsChanged>()
        val initial = CountDownLatch(1)
        val changed = CountDownLatch(1)
        val functions = FunctionStore()
        val subscription = YameEvents.subscribe("ports", { it is AvailablePortsChanged }) {
            events += it as AvailablePortsChanged
            if (events.size == 1) initial.countDown() else changed.countDown()
        }
        try {
            YameLogManager(YameLogManager.defaultLevels(), root).use { logs ->
                YameRuntime(applicationConfigStore(YameConfiguration()), functions, logs,
                    resourceGraphs = NavigationResourceRegistry(), portProvider = source::get).use { runtime ->
                    val worker = thread { runtime.run() }
                    try {
                        assertTrue(initial.await(5, TimeUnit.SECONDS))
                        val first = functions.invoke(ConnectionFunctions.RefreshPorts)
                        assertTrue(first.isEmpty())
                        assertFailsWith<UnsupportedOperationException> { (first as MutableList).add(SerialPortDescriptor("X", "X")) }
                        val replacement = mutableListOf(SerialPortDescriptor("COM2", "two"), SerialPortDescriptor("COM3", "three"))
                        source.set(replacement)
                        // The monitor, without an explicit query, must publish the change.
                        assertTrue(changed.await(5, TimeUnit.SECONDS))
                        val second = functions.invoke(ConnectionFunctions.RefreshPorts)
                        functions.invoke(ApplicationFunctions.Quit)
                        worker.join(5_000)
                        replacement.clear()
                        assertEquals(listOf("COM2", "COM3"), second.map { it.systemPortName })
                        assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(Duration.ofSeconds(5)))
                        assertEquals(listOf(emptyList(), second), events.map { it.ports })
                        assertFailsWith<UnsupportedOperationException> { (events.last().ports as MutableList).clear() }
                    } finally {
                        runtime.close()
                        worker.join(5_000)
                        assertFalse(worker.isAlive)
                    }
                }
            }
        } finally { subscription.close(); root.toFile().deleteRecursively() }
    }

    @Test fun `producer publishes through facade even when independent diagnostic tee fails`() = isolated { broker ->
        val observed = CopyOnWriteArrayList<YameEvent>()
        val subscription = YameEvents.subscribe("packet") { observed += it }
        PppSession(sendFrame = {}, logger = {}, diagnosticSink = { if (it is UnhandledPacket) error("failed tee") }).use { session ->
            session.receive(PppFrame(0x8057, byteArrayOf(1, 2)))
            assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(Duration.ofSeconds(5)))
            assertEquals(1, observed.filterIsInstance<UnhandledPacket>().size)
        }
        subscription.close()
    }

    @Test fun `module log and capture execute once even when broker rejects the observation`() = isolated(EventBrokerConfig(capacity = 1)) { broker ->
        val root = Files.createTempDirectory("yame-independent-capture")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        broker.subscribe("blocked") { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        try {
            broker.publish(DiagnosticMessage("blocked"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            broker.publish(DiagnosticMessage("pending"))
            YameLogManager(YameLogManager.defaultLevels(), root).use { logs ->
                val session = PppSession(sendFrame = {}, logger = {}, diagnosticSink = logs::eventSink)
                try {
                    assertFailsWith<QueueFullException> { session.receive(PppFrame(0x8057, byteArrayOf(1, 2, 3))) }
                } finally { runCatching { session.close() } }
            }
            val text = Files.readString(root.resolve("unhandled.log"))
            assertEquals(1, text.lines().count { " WARN " in it })
            assertEquals(1, text.lines().count { "capture=" in it })
            val captures = Files.walk(root).use { paths -> paths.filter { it.fileName.toString().endsWith(".txt") }.count() }
            assertEquals(1, captures)
        } finally { release.countDown(); root.toFile().deleteRecursively() }
    }
}
