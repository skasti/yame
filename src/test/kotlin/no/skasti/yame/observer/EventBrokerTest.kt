package no.skasti.yame.observer

import no.skasti.yame.ppp.session.UnhandledPacket
import no.skasti.yame.ppp.session.DnsQuery
import no.skasti.yame.ppp.session.DnsResponse
import no.skasti.yame.ppp.session.DnsFailure
import no.skasti.yame.ppp.session.TransferStarted
import no.skasti.yame.ppp.session.TransferStateChanged
import no.skasti.yame.ppp.session.TransferBytes
import no.skasti.yame.ppp.session.DnsTransport
import no.skasti.yame.ppp.session.TransferDirection
import no.skasti.yame.ppp.session.TransferKind
import no.skasti.yame.ppp.session.TransferState
import no.skasti.yame.ppp.proxy.HttpProxyAction
import no.skasti.yame.ppp.proxy.HttpProxyActionKind

import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.*

class EventBrokerTest {
    private val timeout = Duration.ofSeconds(5)
    private fun event(id: Int) = TransferBytes(id.toString(), TransferDirection.TO_HOST, id)
    private fun CountDownLatch.reached() = assertTrue(await(5, TimeUnit.SECONDS), "Latch did not complete")
    private fun EventBroker.finish() {
        close()
        assertEquals(BrokerWaitResult.COMPLETED, awaitClosed(timeout))
    }

    @Test fun `default publish timeout is ten milliseconds`() {
        assertEquals(Duration.ofMillis(10), EventBrokerConfig().publishTimeout)
        assertFailsWith<IllegalArgumentException> { EventBrokerConfig(publishTimeout = Duration.ofNanos(-1)) }
    }

    @Test fun `reentrant publication is queued after all current handlers`() {
        val broker = EventBroker()
        val seen = mutableListOf<String>()
        val published = CountDownLatch(1)
        try {
            broker.subscribe("bad-filter", { error("secret") }) {}
            broker.subscribe("bad-handler") { error("secret") }
            broker.subscribeTyped<TransferBytes>("first") {
                seen.add("a${it.bytes}")
                if (it.bytes == 1) { broker.publish(event(2)); published.countDown() }
            }
            broker.subscribeTyped<TransferBytes>("second") { seen.add("b${it.bytes}") }
            broker.subscribe("excluded", { false }) { fail("filtered") }
            broker.publish(event(1))
            published.reached()
            assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(timeout))
            assertEquals(listOf("a1", "b1", "a2", "b2"), seen)
            assertEquals(4L, broker.status.subscriberFailures)
            assertEquals(SubscriberFailure("bad-handler", SubscriberFailure.Phase.HANDLER), broker.status.lastSubscriberFailure)
        } finally { broker.finish() }
    }

    @Test fun `concurrent producers are delivered in successful admission order`() {
        val broker = EventBroker(EventBrokerConfig(capacity = 1000))
        val pool = Executors.newFixedThreadPool(4)
        val admitted = Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
        val seen = mutableListOf<Int>()
        val gate = CyclicBarrier(4)
        try {
            broker.subscribeTyped<TransferBytes>("record") { seen.add(it.bytes) }
            val producers = (0..3).map { p -> pool.submit {
                gate.await(5, TimeUnit.SECONDS)
                repeat(100) { i -> val id = p * 100 + i; admitted.add(broker.publish(event(id)) to id) }
            } }
            producers.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(timeout))
            assertEquals(admitted.sortedBy { it.first }.map { it.second }, seen)
            assertEquals((1L..400L).toList(), admitted.map { it.first }.sorted())
        } finally { pool.shutdownNow(); broker.finish() }
    }

    @Test fun `registration skips old queued events and removal permits only existing snapshot`() {
        val broker = EventBroker()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = mutableListOf<Int>()
        val fresh = mutableListOf<Int>()
        try {
            broker.subscribe("block") { entered.countDown(); release.reached() }
            val handle = broker.subscribeTyped<TransferBytes>("old") { old.add(it.bytes) }
            broker.publish(event(1)); entered.reached()
            broker.publish(event(2))
            handle.close(); handle.close()
            broker.subscribeTyped<TransferBytes>("new") { fresh.add(it.bytes) }
            broker.publish(event(3)); release.countDown()
            assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(timeout))
            assertEquals(listOf(1), old)
            assertEquals(listOf(3), fresh)
        } finally { release.countDown(); broker.finish() }
    }

    @Test fun `count saturation signals fatal independently of blocked worker and caught exception`() {
        val broker = EventBroker(EventBrokerConfig(capacity = 1, publishTimeout = Duration.ZERO))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val supervisor = Executors.newSingleThreadExecutor()
        try {
            broker.subscribe("blocked-ui") { entered.countDown(); release.reached() }
            broker.publish(event(1)); entered.reached()
            val fatal = supervisor.submit<BrokerFailure?> { broker.awaitFatal(timeout) }
            broker.publish(event(2))
            val boundary = broker.drain()
            val thrown = assertFailsWith<QueueFullException> { broker.publish(event(3)) }
            assertEquals(1, thrown.failure.capacity)
            assertEquals(1, thrown.failure.queuedEvents)
            assertEquals("blocked-ui", thrown.failure.activeSubscriber)
            assertEquals(2L, thrown.failure.lastAcceptedSequence)
            assertEquals(thrown.failure, fatal.get(5, TimeUnit.SECONDS))
            assertEquals(thrown.failure, assertFailsWith<BrokerFailedException> { broker.publish(event(4)) }.failure)
            assertFailsWith<BrokerFailedException> { boundary.await(Duration.ZERO) }
            assertEquals(0, broker.status.queuedEvents)
            broker.close()
            assertEquals(BrokerWaitResult.TIMED_OUT, broker.awaitClosed(Duration.ZERO))
        } finally { release.countDown(); supervisor.shutdownNow(); broker.finish() }
    }

    @Test fun `pending slot is freed when worker dequeues regardless of payload size`() {
        val broker = EventBroker(EventBrokerConfig(capacity = 1))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            broker.subscribe("block") { entered.countDown(); release.reached() }
            val large = UnhandledPacket("IP", "unknown", 33, ByteArray(1_000_000))
            broker.publish(large); entered.reached()
            assertEquals(0, broker.status.queuedEvents)
            broker.publish(event(2))
            assertEquals(1, broker.status.queuedEvents)
            assertNull(broker.status.failure)
        } finally { release.countDown(); broker.finish() }
    }

    @Test fun `full queue waits for space then admits in sequence`() {
        val broker = EventBroker(EventBrokerConfig(capacity = 1, publishTimeout = Duration.ofSeconds(2)))
        val pool = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val seen = mutableListOf<Int>()
        try {
            broker.subscribeTyped<TransferBytes>("block") {
                if (it.bytes == 1) { entered.countDown(); release.reached() }
                seen.add(it.bytes)
            }
            broker.publish(event(1)); entered.reached()
            broker.publish(event(2))
            val waiting = pool.submit<Long> { started.countDown(); broker.publish(event(3)) }
            started.reached()
            assertFailsWith<TimeoutException> { waiting.get(50, TimeUnit.MILLISECONDS) }
            release.countDown()
            assertEquals(3L, waiting.get(5, TimeUnit.SECONDS))
            assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(timeout))
            assertEquals(listOf(1, 2, 3), seen)
        } finally { release.countDown(); pool.shutdownNow(); broker.finish() }
    }

    @Test fun `full queue times out and signals fatal after bounded wait`() {
        val broker = EventBroker(EventBrokerConfig(capacity = 1, publishTimeout = Duration.ofMillis(40)))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            broker.subscribe("block") { entered.countDown(); release.reached() }
            broker.publish(event(1)); entered.reached(); broker.publish(event(2))
            val failure = assertFailsWith<QueueFullException> { broker.publish(event(3)) }.failure
            assertEquals(2L, failure.lastAcceptedSequence)
            assertSame(failure, broker.awaitFatal(Duration.ZERO))
        } finally { release.countDown(); broker.finish() }
    }

    @Test fun `closing broker wakes publishers waiting for capacity`() {
        val broker = EventBroker(EventBrokerConfig(capacity = 1, publishTimeout = Duration.ofSeconds(5)))
        val pool = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val started = CountDownLatch(1)
        try {
            broker.subscribe("block") { entered.countDown(); release.reached() }
            broker.publish(event(1)); entered.reached(); broker.publish(event(2))
            val waiting = pool.submit<Exception?> {
                started.countDown()
                try { broker.publish(event(3)); null } catch (e: BrokerClosedException) { e }
            }
            started.reached()
            assertFailsWith<TimeoutException> { waiting.get(50, TimeUnit.MILLISECONDS) }
            broker.close()
            assertIs<BrokerClosedException>(waiting.get(5, TimeUnit.SECONDS))
            assertNull(broker.status.failure)
        } finally { release.countDown(); pool.shutdownNow(); broker.finish() }
    }

    @Test fun `fatal overload wakes other waiting publishers`() {
        val broker = EventBroker(EventBrokerConfig(capacity = 1, publishTimeout = Duration.ofMillis(300)))
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val started = CountDownLatch(2)
        try {
            broker.subscribe("block") { entered.countDown(); release.reached() }
            broker.publish(event(1)); entered.reached(); broker.publish(event(2))
            val waiting = (1..2).map { pool.submit<Exception> {
                started.countDown()
                try { broker.publish(event(3)); error("Unexpected acceptance") }
                catch (e: QueueFullException) { e }
                catch (e: BrokerFailedException) { e }
            } }
            started.reached()
            val failures = waiting.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, failures.count { it is QueueFullException })
            assertEquals(1, failures.count { it is BrokerFailedException })
            assertSame(broker.status.failure, broker.awaitFatal(Duration.ZERO))
        } finally { release.countDown(); pool.shutdownNow(); broker.finish() }
    }

    @Test fun `drain captures a fixed boundary and does not wait for later callbacks`() {
        val broker = EventBroker()
        val first = CountDownLatch(1); val releaseFirst = CountDownLatch(1)
        val second = CountDownLatch(1); val releaseSecond = CountDownLatch(1)
        try {
            broker.subscribeTyped<TransferBytes>("block") {
                if (it.bytes == 1) { first.countDown(); releaseFirst.reached() }
                else { second.countDown(); releaseSecond.reached() }
            }
            broker.publish(event(1)); first.reached()
            val boundary = broker.drain()
            broker.publish(event(2)); releaseFirst.countDown(); second.reached()
            assertEquals(BrokerWaitResult.COMPLETED, boundary.await(Duration.ZERO))
            assertEquals(BrokerWaitResult.TIMED_OUT, broker.drain().await(Duration.ZERO))
        } finally { releaseFirst.countDown(); releaseSecond.countDown(); broker.finish() }
    }

    @Test fun `worker close and await cannot self deadlock`() {
        val broker = EventBroker()
        val results = mutableListOf<BrokerWaitResult>()
        try {
            broker.subscribe("closer") {
                broker.close()
                results.add(broker.awaitClosed(timeout))
                results.add(broker.drain().await(timeout))
            }
            broker.publish(event(1))
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(timeout))
            assertEquals(listOf(BrokerWaitResult.WOULD_DEADLOCK, BrokerWaitResult.WOULD_DEADLOCK), results)
            assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(Duration.ZERO))
            assertFailsWith<BrokerClosedException> { broker.publish(event(2)) }
        } finally { broker.finish() }
    }

    @Test fun `idle worker wakes for events and healthy close`() {
        val broker = EventBroker()
        try {
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitIdle(timeout))
            broker.publish(event(1))
            assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(timeout))
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitIdle(timeout))
            broker.finish()
            assertNull(broker.awaitFatal(Duration.ZERO))
        } finally { broker.finish() }
    }

    @Test fun `publication racing close is either delivered or explicitly rejected`() {
        repeat(30) {
            val broker = EventBroker()
            val pool = Executors.newFixedThreadPool(2)
            val gate = CyclicBarrier(2)
            val seen = mutableListOf<Int>()
            try {
                broker.subscribeTyped<TransferBytes>("record") { seen.add(it.bytes) }
                val publish = pool.submit<Boolean> {
                    gate.await(5, TimeUnit.SECONDS)
                    try { broker.publish(event(1)); true } catch (_: BrokerClosedException) { false }
                }
                val close = pool.submit { gate.await(5, TimeUnit.SECONDS); broker.close() }
                val accepted = publish.get(5, TimeUnit.SECONDS); close.get(5, TimeUnit.SECONDS)
                broker.finish()
                assertEquals(if (accepted) listOf(1) else emptyList(), seen)
            } finally { pool.shutdownNow(); broker.finish() }
        }
    }

    @Test fun `registration limits and isolated facade installation`() {
        val first = EventBroker(EventBrokerConfig(maxSubscribers = 1)); val second = EventBroker()
        val installation = BrokerInstallation()
        try {
            assertFailsWith<IllegalStateException> { installation.current }
            val owner = installation.install(first)
            assertSame(first, installation.current)
            assertFailsWith<IllegalStateException> { installation.install(second) }
            val subscription = first.subscribe("one") {}
            assertFailsWith<IllegalStateException> { first.subscribe("two") {} }
            assertNull(first.status.failure)
            subscription.close(); first.subscribe("two") {}
            owner.close(); owner.close()
            val next = installation.install(second)
            owner.close(); assertSame(second, installation.current)
            next.close()
            assertFalse(first.status.closing)
        } finally { first.finish(); second.finish() }
    }
    @Test fun `concurrent saturation latches exactly one failure`() {
        val broker = EventBroker(EventBrokerConfig(capacity = 1, publishTimeout = Duration.ZERO))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4); val gate = CyclicBarrier(4)
        try {
            broker.subscribe("block") { entered.countDown(); release.reached() }
            broker.publish(event(1)); entered.reached(); broker.publish(event(2))
            val attempts = (1..4).map { pool.submit<Exception> {
                gate.await(5, TimeUnit.SECONDS)
                try { broker.publish(event(3)); error("Unexpected acceptance") }
                catch (failure: QueueFullException) { failure }
                catch (failure: BrokerFailedException) { failure }
            } }.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, attempts.count { it is QueueFullException })
            assertEquals(3, attempts.count { it is BrokerFailedException })
            attempts.forEach {
                val failure = when (it) {
                    is QueueFullException -> it.failure
                    is BrokerFailedException -> it.failure
                    else -> error("Unexpected exception")
                }
                assertSame(broker.status.failure, failure)
            }
        } finally { release.countDown(); pool.shutdownNow(); broker.finish() }
    }

    @Test fun `brokers have independent consumers and lifecycle`() {
        val a = EventBroker(); val b = EventBroker()
        val seenA = mutableListOf<Int>(); val seenB = mutableListOf<Int>()
        try {
            a.subscribeTyped<TransferBytes>("same-name") { seenA.add(it.bytes) }
            b.subscribeTyped<TransferBytes>("same-name") { seenB.add(it.bytes) }
            a.publish(event(1)); a.finish()
            b.publish(event(2)); b.drain().await(timeout)
            assertEquals(listOf(1), seenA); assertEquals(listOf(2), seenB)
            assertFalse(b.status.closing)
        } finally { a.finish(); b.finish() }
    }

    @Test fun `reentrant overflow remains fatal despite handler exception isolation`() {
        val broker = EventBroker(EventBrokerConfig(capacity = 1, publishTimeout = Duration.ZERO))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            broker.subscribe("reentrant") {
                entered.countDown(); release.reached()
                broker.publish(event(3))
            }
            broker.publish(event(1)); entered.reached()
            broker.publish(event(2)); release.countDown()
            val failure = assertNotNull(broker.awaitFatal(timeout))
            assertEquals("reentrant", failure.activeSubscriber)
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(timeout))
            assertEquals(1L, broker.status.subscriberFailures)
            assertEquals(0L, broker.status.lastDeliveredSequence)
        } finally { release.countDown(); broker.finish() }
    }

}
