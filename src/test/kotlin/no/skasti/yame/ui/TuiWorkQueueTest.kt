package no.skasti.yame.ui

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class TuiWorkQueueTest {
    private fun CountDownLatch.awaitReady() = assertTrue(await(2, TimeUnit.SECONDS))

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
        val queue = TuiWorkQueue(teardown = { teardown.countDown() })
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
        val queue = TuiWorkQueue(teardown = { sequence += "teardown" })
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
        val queue = TuiWorkQueue(teardown = { tornDown.countDown() })
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
