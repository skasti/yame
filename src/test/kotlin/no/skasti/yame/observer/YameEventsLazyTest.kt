package no.skasti.yame.observer

import no.skasti.yame.ppp.session.TransferBytes
import no.skasti.yame.ppp.session.TransferDirection
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Run in its own test JVM: the process facade has exactly one first access. */
class YameEventsLazyTest {
    @Test fun `concurrent facade use shares a real broker and final shutdown cannot restart it`() {
        val pool = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)
        val timeout = Duration.ofSeconds(5)
        try {
            val calls = (1..10).map { pool.submit<EventBroker> {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                YameEvents.current
            } }
            val publication = pool.submit {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                YameEvents.publish(TransferBytes("first", TransferDirection.TO_HOST, 1))
            }
            val subscription = pool.submit {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                YameEvents.subscribe("racing") {}
            }
            start.countDown()
            val broker = calls.map { it.get(5, TimeUnit.SECONDS) }.first()
            assertTrue(calls.all { it.get(5, TimeUnit.SECONDS) === broker })
            assertEquals(1L, publication.get(5, TimeUnit.SECONDS))
            subscription.get(5, TimeUnit.SECONDS).close()
            assertSame(broker, YameEvents.current)
            val seen = Collections.synchronizedList(mutableListOf<Int>())
            val recorder = YameEvents.subscribe("recorder") { seen += (it as TransferBytes).bytes }
            YameEvents.publish(TransferBytes("second", TransferDirection.TO_HOST, 2))
            assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(timeout))
            assertEquals(listOf(2), seen) // no replay of the earlier publication
            recorder.close()
            YameEvents.shutdown()
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(timeout))
            assertFailsWith<BrokerClosedException> {
                YameEvents.publish(TransferBytes("after", TransferDirection.TO_HOST, 3))
            }
            assertFailsWith<BrokerClosedException> { YameEvents.current }
        } finally { start.countDown(); pool.shutdownNow() }
    }
}
