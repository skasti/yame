package no.skasti.yame.ui

import no.skasti.yame.lifecycle.FatalFailureSource
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** FIFO is successful admission order. No admission lock is held while work or terminal IO runs. */
internal class TuiWorkQueue(
    private val capacity: Int = 256,
    private val admissionWait: Duration = Duration.ofMillis(50),
    private val drainWait: Duration = Duration.ofSeconds(3),
    private val teardown: () -> Unit = {},
) : AutoCloseable, FatalFailureSource {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val pending = ArrayDeque<() -> Unit>()
    private var accepting = true
    private val stopped = CountDownLatch(1)
    private val fatal = AtomicReference<Throwable?>()
    override val fatalFailure: Throwable? get() = fatal.get()
    val isWorker: Boolean get() = Thread.currentThread() === worker

    init {
        require(capacity > 0)
        require(!admissionWait.isNegative && !drainWait.isNegative)
    }

    private val worker = Thread({
        try {
            while (true) {
                val next = lock.withLock {
                    while (pending.isEmpty() && accepting) changed.await()
                    if (pending.isEmpty()) null else pending.removeFirst().also { changed.signalAll() }
                } ?: break
                next()
            }
        } catch (error: Throwable) {
            fail(error)
        } finally {
            try { teardown() } catch (error: Throwable) { fail(error) }
            stopped.countDown()
        }
    }, "yame-tui-worker").apply { isDaemon = true; start() }

    /** Closed callbacks are ignored. Capacity exhaustion is terminal and independently observable. */
    fun submit(action: () -> Unit): Boolean = lock.withLock {
        if (!accepting) return false
        var remaining = admissionWait.toNanos()
        while (pending.size == capacity && accepting) {
            // Synchronous config callbacks can reenter from a command. Never wait on ourselves.
            if (isWorker || remaining <= 0) {
                val error = IllegalStateException("TUI queue admission timed out (capacity $capacity)")
                fail(error)
                throw error
            }
            remaining = try { changed.awaitNanos(remaining) } catch (error: InterruptedException) {
                fail(error)
                Thread.currentThread().interrupt()
                throw error
            }
        }
        if (!accepting) return false
        pending.addLast(action)
        changed.signalAll()
        true
    }

    /** Explicit serialized inspection; no observer monitor or arbitrary cross-thread state read. */
    fun <T> read(action: () -> T): T {
        if (isWorker || stopped.count == 0L) return action()
        val result = CompletableFuture<T>()
        check(submit { try { result.complete(action()) } catch (error: Throwable) { result.completeExceptionally(error) } }) {
            "TUI queue is closed"
        }
        return result.get(drainWait.toNanos(), TimeUnit.NANOSECONDS)
    }

    fun fail(error: Throwable) {
        fatal.compareAndSet(null, error)
        lock.withLock { accepting = false; changed.signalAll() }
    }

    override fun close() {
        lock.withLock { accepting = false; changed.signalAll() }
        if (isWorker) return // The worker drains and tears down after the current action returns.
        if (!stopped.await(drainWait.toNanos(), TimeUnit.NANOSECONDS)) {
            val error = IllegalStateException("TUI worker did not drain")
            fail(error)
            throw error
        }
        fatalFailure?.let { throw IllegalStateException("TUI worker failed", it) }
    }
}
