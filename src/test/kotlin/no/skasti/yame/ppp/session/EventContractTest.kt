package no.skasti.yame.ppp.session

import no.skasti.yame.observer.testEventBroker
import no.skasti.yame.observer.AbstractYameEvent
import no.skasti.yame.observer.BrokerWaitResult
import no.skasti.yame.observer.EventBroker
import no.skasti.yame.observer.VersionNumber
import no.skasti.yame.observer.YameEvent
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class EventContractTest {
    data class Probe(val id: Int, val optional: String? = null) : AbstractYameEvent() {
        override val typeName = "test.producer.probe"
        override val version = VersionNumber(1, 2, 3)
        val expensive: Int get() { reads.incrementAndGet(); return id * 2 }
        private val hidden = "internal"

        companion object { val reads = AtomicInteger() }
    }

    // The convenience superclass is optional: independent implementations can provide a map.
    private class Direct : YameEvent, Map<String, Any?> by mapOf("value" to 4) {
        override val typeName = "test.producer.direct"
        override val version = VersionNumber(1, 0)
    }

    data class TypedProbe(val id: Int) : AbstractYameEvent() {
        override val typeName = "test.producer.typed-probe"
        override val version = VersionNumber(1, 0)
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    @Test fun `metadata is separate from public payload properties`() {
        Probe.reads.set(0)
        val event = Probe(3)
        assertNull(AbstractYameEvent.cachedMetadataFor(Probe::class.java))
        assertEquals("test.producer.probe", event.typeName)
        assertEquals(VersionNumber(1, 2, 3), event.version)
        assertEquals("1.2.3", event.version.toString())
        assertEquals(3, event.id)
        assertEquals(0, Probe.reads.get())
        assertEquals(3, event["id"])
        val metadata = assertNotNull(AbstractYameEvent.cachedMetadataFor(Probe::class.java))
        assertSame(metadata, AbstractYameEvent.cachedMetadataFor(Probe::class.java))
        assertSame(metadata, AbstractYameEvent.cachedMetadataFor(Probe(4).javaClass))
        assertTrue(event.containsKey("optional"))
        assertNull(event["optional"])
        assertFalse(event.containsKey("typeName"))
        assertFalse(event.containsKey("version"))
        assertFalse(event.containsKey("hidden"))
        assertFalse(event.containsKey("entries"))
        assertEquals(setOf("id", "optional", "expensive"), event.keys)
        assertEquals(0, Probe.reads.get()) // single lookup does not read unrelated getters
        assertEquals(6, event["expensive"])
        assertEquals(1, Probe.reads.get())
        assertEquals(mapOf("id" to 3, "optional" to null, "expensive" to 6), event.toMap())
        assertEquals(4, Direct()["value"])
        assertFailsWith<UnsupportedOperationException> {
            (event as java.util.Map<String, Any?>).put("id", 10)
        }
    }

    @Test fun `publication and typed access do not read generic payload`() {
        assertNull(AbstractYameEvent.cachedMetadataFor(TypedProbe::class.java))
        val broker = testEventBroker()
        val delivered = CountDownLatch(1)
        try {
            broker.subscribeTyped<TypedProbe>("probe") { assertEquals(5, it.id); delivered.countDown() }
            val event = TypedProbe(5)
            assertEquals(5, event.id)
            broker.publish(event)
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(Duration.ofSeconds(5)))
            assertNull(AbstractYameEvent.cachedMetadataFor(TypedProbe::class.java))
        } finally {
            broker.close()
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(Duration.ofSeconds(5)))
        }
    }

    @Test fun `concurrent first generic access and later instances agree`() {
        assertNull(AbstractYameEvent.cachedMetadataFor(ConcurrentProbe::class.java))
        val pool = Executors.newFixedThreadPool(8)
        val gate = CyclicBarrier(8)
        try {
            val results = (1..8).map { id -> pool.submit<Map<String, Any?>> {
                gate.await(5, TimeUnit.SECONDS)
                ConcurrentProbe(id).toMap()
            } }
            results.forEachIndexed { index, result ->
                assertEquals(index + 1, result.get(5, TimeUnit.SECONDS)["id"])
            }
            val metadata = assertNotNull(AbstractYameEvent.cachedMetadataFor(ConcurrentProbe::class.java))
            assertSame(metadata, AbstractYameEvent.cachedMetadataFor(ConcurrentProbe(9).javaClass))
            assertEquals(setOf("id"), ConcurrentProbe(9).keys)
        } finally { pool.shutdownNow() }
    }

    data class ConcurrentProbe(val id: Int) : AbstractYameEvent() {
        override val typeName = "test.producer.concurrent-probe"
        override val version = VersionNumber(1, 0)
    }

    @Test fun `packet bytes are independent snapshots through both access paths`() {
        val source = byteArrayOf(1, 2)
        val packet = UnhandledPacket("IP", "unknown", 33, source)
        source[0] = 9
        packet.bytes[0] = 8
        (packet["bytes"] as ByteArray)[0] = 7
        packet.bytes()[0] = 6
        assertContentEquals(byteArrayOf(1, 2), packet.bytes)
        assertEquals(2, packet["byteCount"])
    }
}
