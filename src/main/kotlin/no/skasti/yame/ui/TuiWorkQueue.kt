package no.skasti.yame.ui

import no.skasti.yame.lifecycle.FatalFailureSource
import no.skasti.yame.lifecycle.DeadlineCloseable
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
) : DeadlineCloseable, FatalFailureSource {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val admissionWaiting = lock.newCondition()
    private data class PendingWork(val kind: String, val action: () -> Unit)
    private val pending = ArrayDeque<PendingWork>()
    private val admittedByKind = linkedMapOf<String, Long>()
    private val completedByKind = linkedMapOf<String, Long>()
    private var executingKind: String? = null
    private var accepting = true
    private var waitingAdmissions = 0
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
                    if (pending.isEmpty()) null else pending.removeFirst().also {
                        executingKind = it.kind
                        changed.signalAll()
                    }
                } ?: break
                try {
                    next.action()
                } finally {
                    lock.withLock {
                        completedByKind[next.kind] = (completedByKind[next.kind] ?: 0L) + 1L
                        executingKind = null
                    }
                }
            }
        } catch (error: Throwable) {
            fail(error)
        } finally {
            try { teardown() } catch (error: Throwable) { fail(error) }
            stopped.countDown()
        }
    }, "yame-tui-worker").apply { isDaemon = true; start() }

    /** Interruption rejects this attempt without poisoning the queue, as in EventBroker.publish. */
    fun submit(action: () -> Unit): Boolean = submit("Other", failOnInterruption = false, action)

    /**
     * Live callbacks cannot replay a rejected observation/action. Retain that failure even if
     * their caller isolates exceptions. Once admission is closed, rejection is ordinary shutdown.
     */
    fun submitRequired(kind: String = "Other", action: () -> Unit): Boolean =
        submit(kind, failOnInterruption = true, action)

    /** Capture labels only, never event objects or HTTP/packet payloads. Called with lock held. */
    private fun queueSnapshot(rejectedKind: String): String {
        fun formatCounts(counts: Map<String, Long>): String =
            counts.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .joinToString(", ") { "${it.key}=${it.value}" }
        val queuedByKind = pending.groupingBy { it.kind }.eachCount()
            .mapValues { it.value.toLong() }
        return "capacity=$capacity, pending=${pending.size}, " +
            "executing=${executingKind ?: "none"}, rejected=$rejectedKind, " +
            "queuedByKind={${formatCounts(queuedByKind)}}, " +
            "admittedByKind={${formatCounts(admittedByKind)}}, " +
            "completedByKind={${formatCounts(completedByKind)}}"
    }

    /** Closed callbacks are ignored. Capacity exhaustion is terminal and independently observable. */
    private fun submit(kind: String, failOnInterruption: Boolean, action: () -> Unit): Boolean = lock.withLock {
        if (!accepting) return false
        var remaining = admissionWait.toNanos()
        while (pending.size == capacity && accepting) {
            // Synchronous config callbacks can reenter from a command. Never wait on ourselves.
            if (isWorker || remaining <= 0) {
                val error = IllegalStateException("TUI queue admission timed out (${queueSnapshot(kind)})")
                fail(error)
                throw error
            }
            waitingAdmissions++
            admissionWaiting.signalAll()
            remaining = try { changed.awaitNanos(remaining) } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                if (failOnInterruption && accepting) fail(error)
                throw error
            } finally {
                waitingAdmissions--
            }
        }
        if (!accepting) return false
        pending.addLast(PendingWork(kind, action))
        admittedByKind[kind] = (admittedByKind[kind] ?: 0L) + 1L
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

    /** Deterministic admission boundary for tests; never waits for rendering. */
    internal fun awaitAdmissionWait(timeout: Duration): Boolean = lock.withLock {
        var remaining = timeout.toNanos()
        while (waitingAdmissions == 0) {
            if (remaining <= 0) return false
            remaining = admissionWaiting.awaitNanos(remaining)
        }
        true
    }

    override fun close() = closeBefore(System.nanoTime() + drainWait.toNanos())

    override fun closeBefore(deadlineNanos: Long) {
        lock.withLock { accepting = false; changed.signalAll() }
        if (isWorker) return // The worker drains and tears down after the current action returns.
        val drained = try {
            val remaining = (deadlineNanos - System.nanoTime()).coerceAtLeast(0)
            stopped.await(minOf(drainWait.toNanos(), remaining), TimeUnit.NANOSECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        }
        if (!drained) {
            val error = IllegalStateException("TUI worker did not drain")
            fail(error)
            throw error
        }
        fatalFailure?.let { throw IllegalStateException("TUI worker failed", it) }
    }
}
