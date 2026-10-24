package no.skasti.yame.observer

import no.skasti.yame.ppp.session.TransferBytes
import no.skasti.yame.ppp.session.TransferDirection
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class BrokerInstallationTest {
    private val timeout = Duration.ofSeconds(5)
    private fun event(id: Int) = TransferBytes(id.toString(), TransferDirection.TO_HOST, id)

    @Test fun `concurrent first access creates exactly one broker and delivers events`() {
        val created = AtomicInteger()
        val installation = BrokerInstallation(createDefault = { created.incrementAndGet(); EventBroker() })
        val pool = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)
        try {
            val calls = (1..12).map { pool.submit<EventBroker> {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                installation.current
            } }
            start.countDown()
            val brokers = calls.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, created.get())
            assertTrue(brokers.all { it === brokers.first() })
            val seen = Collections.synchronizedList(mutableListOf<Int>())
            val subscription = installation.current.subscribe("recorder") { seen += (it as TransferBytes).bytes }
            installation.current.publish(event(42))
            assertEquals(BrokerWaitResult.COMPLETED, installation.current.drain().await(timeout))
            assertEquals(listOf(42), seen)
            subscription.close()
        } finally {
            start.countDown(); pool.shutdownNow()
            val broker = installation.current
            installation.shutdown()
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(timeout))
            assertFailsWith<BrokerClosedException> { installation.current }
        }
    }

    @Test fun `concurrent first publish and subscribe share one broker without replay`() {
        val created = AtomicInteger()
        val installation = BrokerInstallation(createDefault = { created.incrementAndGet(); EventBroker() })
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val publish = pool.submit { start.await(); installation.current.publish(event(1)) }
            val subscribe = pool.submit { start.await(); installation.current.subscribe("late") {} }
            start.countDown()
            publish.get(5, TimeUnit.SECONDS)
            val handle = subscribe.get(5, TimeUnit.SECONDS)
            val broker = installation.current
            assertEquals(1, created.get())
            assertEquals(1L, broker.status.lastAcceptedSequence)
            handle.close()
            installation.shutdown()
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(timeout))
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun `installation wins before first access and can be repeated only after close`() {
        val created = AtomicInteger()
        val installation = BrokerInstallation(createDefault = { created.incrementAndGet(); EventBroker() })
        repeat(2) {
            val broker = EventBroker()
            val owner = installation.install(broker)
            assertSame(broker, installation.current)
            assertEquals(0, created.get())
            assertFailsWith<IllegalStateException> { owner.close() }
            broker.close()
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(timeout))
            owner.close(); owner.close()
            assertFailsWith<BrokerClosedException> { installation.current }
        }
        installation.shutdown()
        val rejected = EventBroker()
        assertFailsWith<IllegalStateException> { installation.install(rejected) }
        assertEquals(BrokerWaitResult.COMPLETED, rejected.awaitClosed(timeout))
    }

    @Test fun `install racing lazy access has one winner and closes rejected broker`() {
        repeat(20) {
            val created = AtomicInteger()
            val installation = BrokerInstallation(createDefault = { created.incrementAndGet(); EventBroker() })
            val candidate = EventBroker()
            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            try {
                val first = pool.submit { start.await(); installation.current }
                val second = pool.submit<AutoCloseable?> {
                    start.await()
                    try { installation.install(candidate) } catch (_: IllegalStateException) { null }
                }
                start.countDown()
                val selected = first.get(5, TimeUnit.SECONDS)
                val owner = second.get(5, TimeUnit.SECONDS)
                assertSame(selected, installation.current)
                if (owner == null) {
                    assertEquals(1, created.get())
                    assertEquals(BrokerWaitResult.COMPLETED, candidate.awaitClosed(timeout))
                    assertTrue(selected !== candidate)
                    installation.shutdown()
                    assertEquals(BrokerWaitResult.COMPLETED, selected.awaitClosed(timeout))
                } else {
                    assertEquals(0, created.get())
                    assertSame(candidate, selected)
                    candidate.close()
                    assertEquals(BrokerWaitResult.COMPLETED, candidate.awaitClosed(timeout))
                    owner.close()
                    installation.shutdown()
                }
            } finally { start.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun `fatal overload remains visible and shutdown never resurrects a broker`() {
        val installation = BrokerInstallation()
        val broker = EventBroker(EventBrokerConfig(capacity = 1, publishTimeout = Duration.ZERO))
        val owner = installation.install(broker)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            broker.subscribe("block") { entered.countDown(); release.await() }
            installation.current.publish(event(1))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            installation.current.publish(event(2))
            assertFailsWith<QueueFullException> { installation.current.publish(event(3)) }
            assertFailsWith<BrokerFailedException> { installation.current.publish(event(4)) }
            installation.shutdown()
            assertFailsWith<BrokerClosedException> { installation.current }
        } finally {
            release.countDown()
            broker.close()
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(timeout))
            owner.close()
        }
    }
}
