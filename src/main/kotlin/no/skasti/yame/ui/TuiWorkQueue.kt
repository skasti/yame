package no.skasti.yame.ui

import no.skasti.yame.config.ConfigStore
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
    capacity: Int = ConfigStore[TuiSettings.QueueCapacity],
    private val admissionWait: Duration = Duration.ofMillis(50),
    private val drainWait: Duration = Duration.ofSeconds(3),
    private val teardown: () -> Unit = {},
) : DeadlineCloseable, FatalFailureSource {

    private val lock = ReentrantLock()
    private var capacity = capacity
    private val changed = lock.newCondition()
    private val admissionWaiting = lock.newCondition()
    private data class WorkAction(val kind: String, val action: () -> Unit)
    private class PendingWork(val actions: MutableList<WorkAction>, val observationBatch: Boolean)
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
    /** Lock-protected live capacity for diagnostics and tests. */
    internal val configuredCapacity: Int get() = lock.withLock { capacity }
    /** Occupancy uses the live capacity, including temporary over-capacity after shrink. */
    val occupancy: QueueOccupancy get() = lock.withLock {
        QueueOccupancy(queued = pending.size, capacity = capacity)
    }

    init {
        (TuiSettings.QueueCapacity to capacity).validate()
        require(!admissionWait.isNegative && !drainWait.isNegative)
    }

    // Direct lock-protected updates avoid inserting configuration events into
    // the queue being resized, including when it is saturated. Already accepted
    // slots are never removed on shrink.
    // An explicit initial capacity does not opt out of later configuration changes.
    // Isolated tests may construct a queue without installing a process ConfigStore.
    private val capacitySubscription = runCatching { ConfigStore.current }.getOrNull()?.let { store ->
        val observedCapacity = store[TuiSettings.QueueCapacity]
        var firstNotification = true
        store.onChange[TuiSettings.Group].subscribe(initial = true) { changes ->
            changes[TuiSettings.QueueCapacity]?.let { change ->
                lock.withLock {
                    val initial = firstNotification
                    firstNotification = false
                    if (!initial || change.value != observedCapacity) this@TuiWorkQueue.capacity = change.value
                    changed.signalAll()
                }
            }
        }
    }

    private val worker = Thread({
        try {
            while (true) {
                val next = lock.withLock {
                    while (pending.isEmpty() && accepting) changed.await()
                    if (pending.isEmpty()) null else pending.removeFirst().also {
                        changed.signalAll()
                    }
                } ?: break
                for (item in next.actions) {
                    lock.withLock { executingKind = item.kind }
                    try {
                        item.action()
                    } finally {
                        lock.withLock {
                            completedByKind[item.kind] = (completedByKind[item.kind] ?: 0L) + 1L
                            executingKind = null
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            fail(error)
        } finally {
            capacitySubscription?.close()
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

    /**
     * Combine only *adjacent* broker observations into small FIFO batches. Each
     * action still executes, in original order, and an intervening config/keyboard
     * command breaks the batch. This bounds admission work when one HTML page
     * discovers hundreds of resources in one synchronous parser pass.
     */
    fun submitObservation(kind: String, action: () -> Unit): Boolean =
        submit(kind, failOnInterruption = true, action = action, observationBatch = true)

    /**
     * Optional redraws may be retried if the FIFO is saturated. Unlike producer
     * observations, skipping admission here does not discard any state transition.
     */
    fun offerRedraw(action: () -> Unit): Boolean = lock.withLock {
        if (!accepting || pending.size >= capacity) return false
        val kind = "FrameRefresh"
        pending.addLast(PendingWork(mutableListOf(WorkAction(kind, action)), observationBatch = false))
        admittedByKind[kind] = (admittedByKind[kind] ?: 0L) + 1L
        changed.signalAll()
        true
    }

    /** Capture labels only, never event objects or HTTP/packet payloads. Called with lock held. */
    private fun queueSnapshot(rejectedKind: String): String {
        fun formatCounts(counts: Map<String, Long>): String =
            counts.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .joinToString(", ") { "${it.key}=${it.value}" }
        val queuedByKind = pending.flatMap { it.actions }.groupingBy { it.kind }.eachCount()
            .mapValues { it.value.toLong() }
        return "capacity=$capacity, pending=${pending.size}, queuedActions=${queuedByKind.values.sum()}, " +
            "executing=${executingKind ?: "none"}, rejected=$rejectedKind, " +
            "queuedByKind={${formatCounts(queuedByKind)}}, " +
            "admittedByKind={${formatCounts(admittedByKind)}}, " +
            "completedByKind={${formatCounts(completedByKind)}}"
    }

    /** Closed callbacks are ignored. Capacity exhaustion is terminal and independently observable. */
    private fun submit(
        kind: String,
        failOnInterruption: Boolean,
        action: () -> Unit,
        observationBatch: Boolean = false,
    ): Boolean = lock.withLock {
        if (!accepting) return false
        fun appendToTail(): Boolean {
            if (!observationBatch) return false
            val tail = pending.lastOrNull() ?: return false
            if (!tail.observationBatch || tail.actions.size >= MAX_OBSERVATIONS_PER_BATCH) return false
            tail.actions.add(WorkAction(kind, action))
            admittedByKind[kind] = (admittedByKind[kind] ?: 0L) + 1L
            return true
        }
        if (appendToTail()) return true
        var remaining = admissionWait.toNanos()
        while (pending.size >= capacity && accepting) {
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
        if (appendToTail()) return true
        pending.addLast(PendingWork(mutableListOf(WorkAction(kind, action)), observationBatch))
        admittedByKind[kind] = (admittedByKind[kind] ?: 0L) + 1L
        changed.signalAll()
        true
    }

    private companion object {
        const val MAX_OBSERVATIONS_PER_BATCH = 64
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
        capacitySubscription?.close()
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
