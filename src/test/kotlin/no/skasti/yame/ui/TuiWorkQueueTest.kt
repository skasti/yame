package no.skasti.yame.ui

import no.skasti.yame.config.ConfigStore
import no.skasti.yame.config.InstalledConfigTest
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class TuiWorkQueueTest : InstalledConfigTest() {
    private fun CountDownLatch.awaitReady() = assertTrue(await(2, TimeUnit.SECONDS))

    @Test fun `TUI queue owns its configured default and live resizing preserves pending slots`() {
        val store = ConfigStore(listOf(TuiSettings.QueueCapacity to 3))
        installConfig(store)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(4)
        val seen = mutableListOf<Int>()
        val queue = TuiWorkQueue()
        try {
            assertEquals(3, queue.configuredCapacity)
            queue.submitRequired("Hold") { entered.countDown(); release.await(); seen += 1; completed.countDown() }
            entered.awaitReady()
            for (id in 2..4) {
                assertTrue(queue.submitRequired("Pending") { seen += id; completed.countDown() })
            }
            store.set(TuiSettings.QueueCapacity to 1)
            assertEquals(1, queue.configuredCapacity)
            assertNull(queue.fatalFailure)
            store.set(TuiSettings.QueueCapacity to 4)
            assertEquals(4, queue.configuredCapacity)
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            queue.read { assertEquals(listOf(1, 2, 3, 4), seen) }
            assertNull(queue.fatalFailure)
        } finally { release.countDown(); queue.close() }
        store.set(TuiSettings.QueueCapacity to 10)
        assertEquals(4, queue.configuredCapacity, "queue must unsubscribe on close")
    }

    @Test fun `shrinking TUI capacity blocks further work until pending slots drain below limit`() {
        val store = ConfigStore(listOf(TuiSettings.QueueCapacity to 3))
        installConfig(store)
        val entered = (1..4).associateWith { CountDownLatch(1) }
        val release = (1..4).associateWith { CountDownLatch(1) }
        val finished = CountDownLatch(5)
        val seen = mutableListOf<Int>()
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val queue = TuiWorkQueue(admissionWait = Duration.ofSeconds(5))
        try {
            for (id in 1..4) {
                assertTrue(queue.submitRequired("Work") {
                    entered.getValue(id).countDown()
                    release.getValue(id).await()
                    seen += id
                    finished.countDown()
                })
                if (id == 1) entered.getValue(1).awaitReady()
            }
            assertEquals(QueueOccupancy(3, 3), queue.occupancy)
            store.set(TuiSettings.QueueCapacity to 1)
            assertEquals(QueueOccupancy(3, 1), queue.occupancy)
            val started = CountDownLatch(1)
            val waiting = pool.submit<Boolean> {
                started.countDown()
                queue.submitRequired("After") { seen += 5; finished.countDown() }
            }
            started.awaitReady()
            assertTrue(queue.awaitAdmissionWait(Duration.ofSeconds(2)))
            assertFailsWith<java.util.concurrent.TimeoutException> { waiting.get(50, TimeUnit.MILLISECONDS) }
            release.getValue(1).countDown(); entered.getValue(2).awaitReady()
            assertEquals(QueueOccupancy(2, 1), queue.occupancy)
            assertFailsWith<java.util.concurrent.TimeoutException> { waiting.get(50, TimeUnit.MILLISECONDS) }
            release.getValue(2).countDown(); entered.getValue(3).awaitReady()
            assertEquals(QueueOccupancy(1, 1), queue.occupancy)
            assertFailsWith<java.util.concurrent.TimeoutException> { waiting.get(50, TimeUnit.MILLISECONDS) }
            release.getValue(3).countDown(); entered.getValue(4).awaitReady()
            assertTrue(waiting.get(5, TimeUnit.SECONDS))
            release.getValue(4).countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            queue.read { assertEquals((1..5).toList(), seen) }
            assertNull(queue.fatalFailure)
        } finally {
            release.values.forEach { it.countDown() }
            pool.shutdownNow()
            queue.close()
        }
        store.set(TuiSettings.QueueCapacity to 8)
        assertEquals(1, queue.configuredCapacity, "closed queue must unsubscribe")
    }

    @Test fun `explicit TUI queue capacity receives live settings`() {
        val store = ConfigStore()
        installConfig(store)
        val queue = TuiWorkQueue(capacity = 2)
        try {
            assertEquals(2, queue.configuredCapacity)
            store.set(TuiSettings.QueueCapacity to 1024)
            assertEquals(1024, queue.configuredCapacity)
        } finally { queue.close() }
    }

    @Test fun `queued initial TUI settings notification preserves override until next change`() {
        val store = ConfigStore(listOf(TuiSettings.QueueCapacity to 5))
        installConfig(store)
        val blocked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val listener = store.onChange[TuiSettings.Group].subscribe {
            if (it[TuiSettings.QueueCapacity]?.value == 6) {
                blocked.countDown()
                resume.await()
            }
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        var queue: TuiWorkQueue? = null
        try {
            val first = pool.submit { store.set(TuiSettings.QueueCapacity to 6) }
            blocked.awaitReady()
            queue = TuiWorkQueue(capacity = 2)
            assertEquals(2, queue.configuredCapacity)
            val next = pool.submit { store.set(TuiSettings.QueueCapacity to 7) }
            resume.countDown()
            first.get(5, TimeUnit.SECONDS)
            next.get(5, TimeUnit.SECONDS)
            assertEquals(7, queue.configuredCapacity)
        } finally {
            resume.countDown()
            queue?.close()
            listener.close()
            pool.shutdownNow()
        }
    }

    @Test fun `queue settings reject nonpositive and excessive capacities`() {
        val store = ConfigStore()
        installConfig(store)
        for (value in listOf(-1, 0, 65_537)) {
            assertFailsWith<IllegalArgumentException> { store.set(TuiSettings.QueueCapacity to value) }
        }
        assertEquals(256, store[TuiSettings.QueueCapacity])
        assertFailsWith<IllegalArgumentException> { TuiWorkQueue(capacity = 0) }
        assertFailsWith<IllegalArgumentException> { TuiWorkQueue(capacity = 65_537) }
    }

    @Test fun `interrupted admission rejects only that attempt and restores the interrupt flag`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val interrupted = AtomicBoolean()
        val rejectedRan = AtomicBoolean()
        val acceptedRan = AtomicBoolean()
        val queue = TuiWorkQueue(capacity = 1, admissionWait = Duration.ofDays(1))
        val publisher = Thread {
            try { queue.submit { rejectedRan.set(true) } }
            catch (failure: Throwable) { error.set(failure); interrupted.set(Thread.currentThread().isInterrupted) }
        }
        try {
            queue.submit { entered.countDown(); release.await() }
            entered.awaitReady()
            queue.submit { acceptedRan.set(true) }
            publisher.start()
            assertTrue(queue.awaitAdmissionWait(Duration.ofSeconds(2)))
            publisher.interrupt()
            publisher.join(2_000)
            assertFalse(publisher.isAlive)
            assertIs<InterruptedException>(error.get())
            assertTrue(interrupted.get())
            assertNull(queue.fatalFailure)
            release.countDown()
            assertTrue(queue.submit {}) // A caller can explicitly retry on the healthy queue.
            queue.close()
            assertTrue(acceptedRan.get())
            assertFalse(rejectedRan.get())
        } finally { release.countDown(); publisher.interrupt(); publisher.join(2_000); queue.close() }
    }

    @Test fun `interrupted live required handoff retains failure even if its caller catches it`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val interrupted = AtomicBoolean()
        val queue = TuiWorkQueue(capacity = 1, admissionWait = Duration.ofDays(1))
        val publisher = Thread {
            try { queue.submitRequired { fail("rejected observation executed") } }
            catch (failure: Throwable) { error.set(failure); interrupted.set(Thread.currentThread().isInterrupted) }
        }
        try {
            queue.submit { entered.countDown(); release.await() }
            entered.awaitReady()
            queue.submit {}
            publisher.start()
            assertTrue(queue.awaitAdmissionWait(Duration.ofSeconds(2)))
            publisher.interrupt()
            publisher.join(2_000)
            assertFalse(publisher.isAlive)
            assertIs<InterruptedException>(error.get())
            assertTrue(interrupted.get())
            assertSame(error.get(), queue.fatalFailure)
        } finally {
            release.countDown(); publisher.interrupt(); publisher.join(2_000)
            assertFailsWith<IllegalStateException> { queue.close() }
        }
    }

    @Test fun `closing admission before interrupting an in flight callback is ordinary shutdown`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val acceptedRan = AtomicBoolean()
        val accepted = AtomicBoolean()
        val error = AtomicReference<Throwable?>()
        val interruptIssued = CompletableFuture<Unit>()
        val interrupted = AtomicBoolean()
        val queue = TuiWorkQueue(capacity = 1, admissionWait = Duration.ofDays(1))
        val publisher = Thread {
            try { accepted.set(queue.submitRequired { fail("closed observation executed") }) }
            catch (failure: Throwable) {
                error.set(failure)
            } finally {
                // join is uninterruptible and retains the flag. Keep the caller alive until
                // the worker has definitely interrupted it, regardless of wake-up ordering.
                interruptIssued.join()
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        try {
            queue.submit {
                entered.countDown(); release.await()
                queue.close() // Seal admission on the worker before interrupting the waiting caller.
                publisher.interrupt()
                interruptIssued.complete(Unit)
            }
            entered.awaitReady()
            queue.submit { acceptedRan.set(true) }
            publisher.start()
            assertTrue(queue.awaitAdmissionWait(Duration.ofSeconds(2)))
            release.countDown()
            publisher.join(2_000)
            assertFalse(publisher.isAlive)
            queue.close()
            assertFalse(accepted.get())
            assertTrue(interrupted.get())
            error.get()?.let { assertIs<InterruptedException>(it) }
            assertNull(queue.fatalFailure)
            assertTrue(acceptedRan.get())
            // Already interrupted late callbacks are also rejected without clearing the flag.
            Thread.currentThread().interrupt()
            try {
                assertFalse(queue.submitRequired { fail("late callback") })
                assertTrue(Thread.currentThread().isInterrupted)
            } finally { Thread.interrupted() }
        } finally {
            release.countDown(); publisher.interrupt(); interruptIssued.complete(Unit)
            publisher.join(2_000); queue.close()
        }
    }

    @Test fun `close uses the remaining budget instead of a fresh drain timeout`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val teardown = CountDownLatch(1)
        val queue = TuiWorkQueue(capacity = 256, teardown = { teardown.countDown() })
        try {
            queue.submit { entered.countDown(); release.await() }
            entered.awaitReady()
            assertFailsWith<IllegalStateException> { queue.closeBefore(System.nanoTime()) }
            assertEquals(1L, teardown.count, "a blocked render cannot be torn down from another thread")
            assertNotNull(queue.fatalFailure)
        } finally {
            release.countDown()
            assertFailsWith<IllegalStateException> { queue.close() }
            teardown.awaitReady()
        }
    }

    @Test fun `blocked render releases admission lock and accepted work is FIFO on one worker`() {
        val rendering = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executed = mutableListOf<Int>()
        val threads = mutableSetOf<Thread>()
        val queue = TuiWorkQueue(capacity = 4)
        try {
            queue.submit { rendering.countDown(); release.await(); threads += Thread.currentThread() }
            rendering.awaitReady()
            repeat(4) { value -> assertTrue(queue.submit { executed += value; threads += Thread.currentThread() }) }
            release.countDown()
            queue.read { assertEquals(listOf(0, 1, 2, 3), executed); assertEquals(1, threads.size) }
        } finally { release.countDown(); queue.close() }
    }

    @Test fun `saturation is fatal even when callback exception is caught`() {
        val rendering = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tornDown = CountDownLatch(1)
        val queue = TuiWorkQueue(capacity = 1, admissionWait = Duration.ZERO, teardown = { tornDown.countDown() })
        try {
            queue.submit { rendering.countDown(); release.await() }
            rendering.awaitReady()
            assertTrue(queue.submit {})
            runCatching { queue.submit {} } // A broker/config subscriber may isolate this exception.
            assertNotNull(queue.fatalFailure)
            assertFalse(queue.submit { fail("failed queue accepted work") })
        } finally {
            release.countDown()
            assertFailsWith<IllegalStateException> { queue.close() }
            tornDown.awaitReady()
        }
    }

    @Test fun `queue occupancy counts pending work slots including batches but not executing work`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queue = TuiWorkQueue(capacity = 4)
        try {
            queue.submitRequired("Running") { entered.countDown(); release.await() }
            entered.awaitReady()
            assertEquals(QueueOccupancy(0, 4), queue.occupancy)
            repeat(100) {
                assertTrue(queue.submitObservation("ResourceLifecycleEvent") {})
            }
            // 100 observations are stored in two adjacent batches, not 100 slots.
            assertEquals(QueueOccupancy(2, 4), queue.occupancy)
            assertTrue(queue.submitRequired("KeyboardInput") {})
            assertEquals(QueueOccupancy(3, 4), queue.occupancy)
        } finally {
            release.countDown()
            queue.close()
        }
        assertEquals(QueueOccupancy(0, 4), queue.occupancy)
    }

    @Test fun `hundreds of resource observations preserve FIFO order without saturating work slots`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val seen = mutableListOf<String>()
        val queue = TuiWorkQueue(capacity = 24, admissionWait = Duration.ZERO)
        try {
            queue.submitRequired(kind = "StartScreen") { entered.countDown(); release.await() }
            entered.awaitReady()
            repeat(480) { i ->
                assertTrue(queue.submitObservation("ResourceLifecycleEvent") { seen += "resource-$i" })
            }
            assertTrue(queue.submitRequired(kind = "KeyboardInput") { seen += "keyboard" })
            repeat(480) { i ->
                assertTrue(queue.submitObservation("ResourceGraphLifecycleEvent") { seen += "graph-$i" })
            }
            // 16 bounded batches plus the keyboard must fit, while 960 independent
            // queue entries would exceed capacity 24. No observations are removed.
            assertNull(queue.fatalFailure)
            release.countDown()
            queue.read {
                assertEquals(
                    (0 until 480).map { "resource-$it" } + "keyboard" +
                        (0 until 480).map { "graph-$it" },
                    seen,
                )
            }
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test fun `optional redraw never poisons the queue when required observations fill it`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val accepted = mutableListOf<String>()
        val queue = TuiWorkQueue(capacity = 1, admissionWait = Duration.ZERO)
        try {
            queue.submitRequired(kind = "LogEntry") {
                entered.countDown()
                release.await()
                accepted += "first"
            }
            entered.awaitReady()
            assertTrue(queue.submitRequired(kind = "ResourceLifecycleEvent") { accepted += "second" })
            assertFalse(queue.offerRedraw { fail("a redraw must not replace required work") })
            assertNull(queue.fatalFailure)
            release.countDown()
            queue.read { assertEquals(listOf("first", "second"), accepted) }
            assertTrue(queue.offerRedraw { accepted += "redraw" })
            queue.close()
            assertEquals(listOf("first", "second", "redraw"), accepted)
        } finally { release.countDown(); queue.close() }
    }

    @Test fun `saturated queue reports pending event types and active work without event contents`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queue = TuiWorkQueue(capacity = 2, admissionWait = Duration.ZERO)
        try {
            queue.submitRequired(kind = "DiagnosticMessage") {
                entered.countDown()
                release.await()
            }
            entered.awaitReady()
            assertTrue(queue.submitRequired(kind = "TransferBytes") {})
            assertTrue(queue.submitRequired(kind = "TransferBytes") {})
            val failure = assertFailsWith<IllegalStateException> {
                queue.submitRequired(kind = "DnsQuery") {}
            }
            val message = failure.message.orEmpty()
            assertContains(message, "TUI queue admission timed out")
            assertContains(message, "capacity=2, pending=2")
            assertContains(message, "executing=DiagnosticMessage")
            assertContains(message, "rejected=DnsQuery")
            assertContains(message, "queuedByKind={TransferBytes=2}")
            assertContains(message, "admittedByKind={TransferBytes=2, DiagnosticMessage=1}")
            assertSame(failure, queue.fatalFailure)
        } finally {
            release.countDown()
            assertFailsWith<IllegalStateException> { queue.close() }
        }
    }

    @Test fun `external admission times out after its bounded capacity wait`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queue = TuiWorkQueue(capacity = 1, admissionWait = Duration.ofMillis(1))
        try {
            queue.submit { entered.countDown(); release.await() }
            entered.awaitReady()
            queue.submit {}
            assertFailsWith<IllegalStateException> { queue.submit {} }
            assertNotNull(queue.fatalFailure)
        } finally {
            release.countDown()
            assertFailsWith<IllegalStateException> { queue.close() }
        }
    }

    @Test fun `worker initiated close drains accepted work before teardown and rejects later callbacks`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sequence = mutableListOf<String>()
        val queueRef = AtomicReference<TuiWorkQueue>()
        val queue = TuiWorkQueue(capacity = 256, teardown = { sequence += "teardown" })
        queueRef.set(queue)
        queue.submit { entered.countDown(); release.await(); queueRef.get().close(); sequence += "close" }
        entered.awaitReady()
        queue.submit { sequence += "accepted" }
        release.countDown()
        queue.close()
        assertEquals(listOf("close", "accepted", "teardown"), sequence)
        assertFalse(queue.submit { fail("after teardown") })
        queue.close()
    }

    @Test fun `worker failure remains observable without further queue progress`() {
        val error = IllegalArgumentException("render failed")
        val tornDown = CountDownLatch(1)
        val queue = TuiWorkQueue(capacity = 256, teardown = { tornDown.countDown() })
        queue.submit { throw error }
        tornDown.awaitReady()
        assertSame(error, queue.fatalFailure)
        assertFailsWith<IllegalStateException> { queue.close() }
    }

    @Test fun `reentrant admission fails immediately instead of waiting on worker capacity`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tornDown = CountDownLatch(1)
        val queue = TuiWorkQueue(capacity = 1, admissionWait = Duration.ofDays(1), teardown = { tornDown.countDown() })
        queue.submit { entered.countDown(); release.await(); queue.submit {} }
        entered.awaitReady()
        queue.submit {}
        release.countDown()
        tornDown.awaitReady()
        assertNotNull(queue.fatalFailure)
        assertFailsWith<IllegalStateException> { queue.close() }
    }
}
