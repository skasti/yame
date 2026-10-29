package no.skasti.yame.observer

import no.skasti.yame.config.ConfigStore
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

    @Test fun `default broker owns configured queue capacity and applies live changes without losing events`() {
        val store = ConfigStore(listOf(EventBrokerSettings.QueueCapacity to 3))
        ConfigStore.install(store).use {
            val broker = EventBroker()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val seen = mutableListOf<Int>()
            try {
                assertEquals(3, broker.capacity)
                broker.subscribeTyped<TransferBytes>("hold") {
                    if (it.bytes == 1) { entered.countDown(); release.reached() }
                    seen += it.bytes
                }
                broker.publish(event(1)); entered.reached()
                (2..4).forEach { broker.publish(event(it)) }
                assertEquals(3, broker.status.queuedEvents)

                // Shrinking below the number of already queued events does not
                // discard them or retroactively fail the broker.
                store.set(EventBrokerSettings.QueueCapacity to 1)
                assertEquals(1, broker.capacity)
                assertEquals(3, broker.status.queuedEvents)
                assertNull(broker.status.failure)
                store.set(EventBrokerSettings.QueueCapacity to 4)
                assertEquals(4, broker.capacity)
                broker.publish(event(5))
                assertEquals(4, broker.status.queuedEvents)

                release.countDown()
                assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(timeout))
                assertEquals((1..5).toList(), seen)
                assertEquals(0, broker.status.queuedEvents)
            } finally { release.countDown(); broker.finish() }
            // Closing the broker also removes its configuration subscription.
            store.set(EventBrokerSettings.QueueCapacity to 6)
            assertEquals(4, broker.capacity)
        }
    }

    @Test fun `shrinking broker capacity blocks new admission until pending events drain below limit`() {
        val store = ConfigStore(listOf(EventBrokerSettings.QueueCapacity to 3))
        ConfigStore.install(store).use {
            val broker = EventBroker(publishTimeout = Duration.ofSeconds(5))
            val entered = (1..4).associateWith { CountDownLatch(1) }
            val release = (1..4).associateWith { CountDownLatch(1) }
            val seen = Collections.synchronizedList(mutableListOf<Int>())
            val pool = Executors.newSingleThreadExecutor()
            try {
                broker.subscribeTyped<TransferBytes>("hold") {
                    seen += it.bytes
                    if (it.bytes in 1..4) {
                        entered.getValue(it.bytes).countDown()
                        release.getValue(it.bytes).await()
                    }
                }
                broker.publish(event(1)); entered.getValue(1).reached()
                (2..4).forEach { broker.publish(event(it)) }
                store.set(EventBrokerSettings.QueueCapacity to 1)
                assertEquals(3, broker.status.queuedEvents)
                assertEquals(1, broker.capacity)
                val started = CountDownLatch(1)
                val waiting = pool.submit<Long> {
                    started.countDown()
                    broker.publish(event(5))
                }
                started.reached()
                assertFailsWith<TimeoutException> { waiting.get(50, TimeUnit.MILLISECONDS) }
                release.getValue(1).countDown(); entered.getValue(2).reached()
                assertEquals(2, broker.status.queuedEvents)
                assertFailsWith<TimeoutException> { waiting.get(50, TimeUnit.MILLISECONDS) }
                release.getValue(2).countDown(); entered.getValue(3).reached()
                assertEquals(1, broker.status.queuedEvents)
                assertFailsWith<TimeoutException> { waiting.get(50, TimeUnit.MILLISECONDS) }
                release.getValue(3).countDown(); entered.getValue(4).reached()
                assertEquals(5L, waiting.get(5, TimeUnit.SECONDS))
                release.getValue(4).countDown()
                assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(timeout))
                assertEquals((1..5).toList(), seen)
                assertNull(broker.status.failure)
            } finally {
                release.values.forEach { it.countDown() }
                pool.shutdownNow()
                broker.finish()
            }
            store.set(EventBrokerSettings.QueueCapacity to 6)
            assertEquals(1, broker.capacity, "closed broker must not observe new settings")
        }
    }

    @Test fun `explicit broker constructor values are initial values before live settings updates`() {
        val store = ConfigStore(listOf(EventBrokerSettings.QueueCapacity to 4096))
        ConfigStore.install(store).use {
            val broker = EventBroker(capacity = 2, publishTimeout = Duration.ofMillis(25), maxSubscribers = 3)
            try {
                assertEquals(2, broker.capacity, "initial subscription must preserve explicit capacity")
                assertEquals(Duration.ofMillis(25), broker.publishTimeout)
                assertEquals(3, broker.maxSubscribers)
                store.set(EventBrokerSettings.QueueCapacity to 1024,
                    EventBrokerSettings.PublishTimeoutMillis to 40,
                    EventBrokerSettings.MaxSubscribers to 7)
                assertEquals(1024, broker.capacity)
                assertEquals(Duration.ofMillis(40), broker.publishTimeout)
                assertEquals(7, broker.maxSubscribers)
            } finally { broker.finish() }
        }
    }

    @Test fun `delayed initial settings notification preserves overrides and delivers later updates`() {
        val store = ConfigStore(listOf(EventBrokerSettings.QueueCapacity to 5))
        ConfigStore.install(store).use {
            val blocked = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val subscriber = store.onChange[EventBrokerSettings.Group].subscribe {
                if (it[EventBrokerSettings.QueueCapacity]?.value == 6) {
                    blocked.countDown()
                    resume.await()
                }
            }
            val pool = Executors.newFixedThreadPool(2)
            var broker: EventBroker? = null
            try {
                val first = pool.submit { store.set(EventBrokerSettings.QueueCapacity to 6) }
                blocked.reached()
                // The initial=true snapshot is queued behind the blocked delivery.
                // Its unchanged stored value (6) must not overwrite this override.
                broker = EventBroker(capacity = 2)
                assertEquals(2, broker.capacity)
                val next = pool.submit { store.set(EventBrokerSettings.QueueCapacity to 7) }
                resume.countDown()
                first.get(5, TimeUnit.SECONDS)
                next.get(5, TimeUnit.SECONDS)
                assertEquals(7, broker.capacity)
            } finally {
                resume.countDown()
                broker?.finish()
                subscriber.close()
                pool.shutdownNow()
            }
        }
    }

    @Test fun `default publish timeout is ten milliseconds`() {
        val store = ConfigStore()
        ConfigStore.install(store).use {
            val broker = EventBroker()
            try {
                assertEquals(Duration.ofMillis(10), broker.publishTimeout)
                assertEquals(64, broker.maxSubscribers)
                store.set(EventBrokerSettings.PublishTimeoutMillis to 50,
                    EventBrokerSettings.MaxSubscribers to 80)
                assertEquals(Duration.ofMillis(50), broker.publishTimeout)
                assertEquals(80, broker.maxSubscribers)
                for (invalid in listOf(-1, 60_001)) {
                    assertFailsWith<IllegalArgumentException> { store.set(EventBrokerSettings.PublishTimeoutMillis to invalid) }
                }
                assertFailsWith<IllegalArgumentException> { store.set(EventBrokerSettings.MaxSubscribers to 0) }
            } finally { broker.finish() }
        }
    }

    @Test fun `explicit broker values use feature owned setting validation`() {
        assertFailsWith<IllegalArgumentException> { testEventBroker(capacity = 0) }
        assertFailsWith<IllegalArgumentException> { testEventBroker(maxSubscribers = 0) }
        assertFailsWith<IllegalArgumentException> {
            testEventBroker(publishTimeout = Duration.ofMillis(60_001))
        }
    }

    @Test fun `publish timeout requires exactly representable nonnegative whole milliseconds`() {
        for (invalid in listOf(
            Duration.ofNanos(-1),
            Duration.ofMillis(-1),
            Duration.ofNanos(1),
            Duration.ofMillis(1).plusNanos(1),
            Duration.ofMillis(60_001),
            Duration.ofMillis(Int.MAX_VALUE.toLong() + 1),
        )) {
            assertFailsWith<IllegalArgumentException>("invalid timeout: $invalid") {
                testEventBroker(publishTimeout = invalid)
            }
        }
        for (valid in listOf(Duration.ZERO, Duration.ofMillis(1), Duration.ofMillis(60_000))) {
            testEventBroker(publishTimeout = valid).use { broker ->
                assertEquals(valid, broker.publishTimeout)
                assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(timeout))
            }
        }
    }

    @Test fun `reentrant publication is queued after all current handlers`() {
        val broker = testEventBroker()
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
        val broker = testEventBroker(capacity = 1000)
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
        val broker = testEventBroker()
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
        val broker = testEventBroker(capacity = 1, publishTimeout = Duration.ZERO)
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
        val broker = testEventBroker(capacity = 1)
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
        val broker = testEventBroker(capacity = 1, publishTimeout = Duration.ofSeconds(2))
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
        val broker = testEventBroker(capacity = 1, publishTimeout = Duration.ofMillis(40))
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
        val broker = testEventBroker(capacity = 1, publishTimeout = Duration.ofSeconds(5))
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
        val broker = testEventBroker(capacity = 1, publishTimeout = Duration.ofMillis(300))
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
        val broker = testEventBroker()
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
        val broker = testEventBroker()
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
        val broker = testEventBroker()
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
            val broker = testEventBroker()
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
        val first = testEventBroker(maxSubscribers = 1); val second = testEventBroker()
        val installation = BrokerInstallation()
        try {
            val owner = installation.install(first)
            assertSame(first, installation.current)
            assertFailsWith<IllegalStateException> { installation.install(second) }
            assertEquals(BrokerWaitResult.COMPLETED, second.awaitClosed(timeout))
            val subscription = first.subscribe("one") {}
            assertFailsWith<IllegalStateException> { first.subscribe("two") {} }
            assertNull(first.status.failure)
            subscription.close(); first.subscribe("two") {}
            assertFailsWith<IllegalStateException> { owner.close() }
            first.finish()
            owner.close(); owner.close()
            assertFailsWith<BrokerClosedException> { installation.current }
            val nextBroker = testEventBroker()
            val next = installation.install(nextBroker)
            owner.close(); assertSame(nextBroker, installation.current)
            nextBroker.finish()
            next.close()
        } finally { first.finish(); second.finish() }
    }
    @Test fun `concurrent saturation latches exactly one failure`() {
        val broker = testEventBroker(capacity = 1, publishTimeout = Duration.ZERO)
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
        val a = testEventBroker(); val b = testEventBroker()
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
        val broker = testEventBroker(capacity = 1, publishTimeout = Duration.ZERO)
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
