package no.skasti.yame.ui

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class TuiWorkQueueTest {
    private fun CountDownLatch.awaitReady() = assertTrue(await(2, TimeUnit.SECONDS))

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
