package no.skasti.yame.observer

import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One bounded FIFO and one worker. Consumers must hand off expensive work themselves. */
class EventBroker(val config: EventBrokerConfig = EventBrokerConfig()) : AutoCloseable {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private data class Envelope(val sequence: Long, val event: YameEvent, val bytes: Long)
    private class Registration(
        val name: String,
        val firstSequence: Long,
        val filter: (YameEvent) -> Boolean,
        val handler: (YameEvent) -> Unit,
    )
    private val queue = ArrayDeque<Envelope>()
    private val registrations = mutableListOf<Registration>()
    private var queuedBytes = 0L
    private var accepted = 0L
    private var delivered = 0L
    private var closing = false
    private var terminated = false
    private var idle = false
    private var active: String? = null
    private var fatal: BrokerFailure? = null
    private var failures = 0L
    private var lastFailure: SubscriberFailure? = null
    private val worker = Thread(::dispatch, "yame-events").apply { isDaemon = true; start() }

    data class Status(
        val queuedEvents: Int,
        val queuedBytes: Long,
        val lastAcceptedSequence: Long,
        val lastDeliveredSequence: Long,
        val activeSubscriber: String?,
        val failure: BrokerFailure?,
        val subscriberFailures: Long,
        val lastSubscriberFailure: SubscriberFailure?,
        val closing: Boolean,
        val terminated: Boolean,
        val idle: Boolean,
    )
    val status: Status get() = lock.withLock {
        Status(queue.size, queuedBytes, accepted, delivered, active, fatal,
            failures, lastFailure, closing, terminated, idle)
    }

    /** Never waits for capacity or calls user code. The returned sequence defines FIFO order. */
    fun publish(event: YameEvent): Long = lock.withLock {
        fatal?.let { throw BrokerFailedException(it) }
        if (closing) throw BrokerClosedException()
        val bytes = EventPayloadBudget.bytes(event)
        val reason = when {
            bytes > config.maxEventBytes -> BrokerFailure.Reason.EVENT_TOO_LARGE
            queue.size >= config.capacity || bytes > config.payloadCapacity - queuedBytes -> BrokerFailure.Reason.QUEUE_FULL
            else -> null
        }
        if (reason != null) {
            val failure = BrokerFailure(reason, config.capacity, queue.size, config.payloadCapacity,
                queuedBytes, config.maxEventBytes, bytes, active, accepted)
            fatal = failure
            closing = true
            queue.clear()
            queuedBytes = 0
            changed.signalAll() // fatal supervision never uses the dispatcher or a user callback
            if (reason == BrokerFailure.Reason.QUEUE_FULL) throw QueueFullException(failure)
            throw EventTooLargeException(failure)
        }
        val sequence = ++accepted
        queue.addLast(Envelope(sequence, event, bytes))
        queuedBytes += bytes
        changed.signalAll()
        sequence
    }

    /** Names are diagnostic identifiers, not user content. No replay of previously accepted events. */
    fun subscribe(
        name: String,
        filter: (YameEvent) -> Boolean = { true },
        handler: (YameEvent) -> Unit,
    ): AutoCloseable = lock.withLock {
        require(name.length in 1..80 && name.all { it.isLetterOrDigit() || it in "._-" }) {
            "Subscriber name must be a diagnostic identifier of 1..80 characters"
        }
        fatal?.let { throw BrokerFailedException(it) }
        if (closing) throw BrokerClosedException()
        check(registrations.size < config.maxSubscribers) { "Subscriber capacity exceeded" }
        check(registrations.none { it.name == name }) { "Duplicate subscriber name: $name" }
        val registration = Registration(name, accepted + 1, filter, handler)
        registrations.add(registration)
        AutoCloseable { lock.withLock { registrations.remove(registration) } }
    }

    inline fun <reified T : YameEvent> subscribeTyped(name: String, crossinline handler: (T) -> Unit): AutoCloseable =
        subscribe(name, { it is T }) { handler(it as T) }

    /** Constant-size high-water mark: no retained list of futures/waiters. */
    fun drain(): Drain = lock.withLock { Drain(accepted) }
    inner class Drain internal constructor(val sequence: Long) {
        /** Throws BrokerFailedException on fatal shutdown, including an already-delivered boundary. */
        fun await(timeout: Duration): BrokerWaitResult = lock.withLock {
            waitLocked(timeout, failOnFatal = true) { delivered >= sequence }
        }
    }

    /** Graceful while healthy. Never joins, so callbacks may close their own broker. */
    override fun close() = lock.withLock {
        closing = true
        changed.signalAll()
    }

    fun awaitClosed(timeout: Duration): BrokerWaitResult = lock.withLock {
        waitLocked(timeout, failOnFatal = false) { terminated }
    }

    /** Independent fatal supervision. Null means timeout or healthy worker termination.
     * The owner must inspect its own shutdown intent when this returns null.
     * Never invoke from a subscriber; it would prevent delivery on the sole worker.
     */
    fun awaitFatal(timeout: Duration): BrokerFailure? = lock.withLock {
        check(Thread.currentThread() !== worker) { "Cannot supervise fatal shutdown from the dispatcher" }
        waitLocked(timeout, failOnFatal = false) { fatal != null || terminated }
        fatal
    }

    private fun waitLocked(timeout: Duration, failOnFatal: Boolean, complete: () -> Boolean): BrokerWaitResult {
        require(!timeout.isNegative)
        var remaining = try { timeout.toNanos() } catch (_: ArithmeticException) { Long.MAX_VALUE }
        while (true) {
            if (failOnFatal) fatal?.let { throw BrokerFailedException(it) }
            if (complete()) return BrokerWaitResult.COMPLETED
            if (Thread.currentThread() === worker) return BrokerWaitResult.WOULD_DEADLOCK
            if (remaining <= 0) return BrokerWaitResult.TIMED_OUT
            remaining = changed.awaitNanos(remaining)
        }
    }

    private fun dispatch() {
        try {
            while (true) {
                val (envelope, listeners) = lock.withLock {
                    while (queue.isEmpty() && !closing) {
                        idle = true
                        changed.signalAll()
                        changed.await()
                    }
                    idle = false
                    if (fatal != null || (queue.isEmpty() && closing)) return
                    val next = queue.removeFirst()
                    queuedBytes -= next.bytes
                    next to registrations.filter { it.firstSequence <= next.sequence }
                }
                for (listener in listeners) {
                    lock.withLock {
                        if (fatal != null) return
                        active = listener.name
                    }
                    var phase = SubscriberFailure.Phase.FILTER
                    try {
                        if (listener.filter(envelope.event)) {
                            phase = SubscriberFailure.Phase.HANDLER
                            listener.handler(envelope.event)
                        }
                    } catch (_: Throwable) {
                        lock.withLock {
                            if (failures < Long.MAX_VALUE) failures++
                            lastFailure = SubscriberFailure(listener.name, phase)
                        }
                    } finally {
                        lock.withLock { active = null }
                    }
                }
                lock.withLock {
                    if (fatal == null) delivered = envelope.sequence
                    changed.signalAll()
                }
            }
        } finally {
            lock.withLock {
                active = null
                idle = false
                terminated = true
                registrations.clear()
                changed.signalAll()
            }
        }
    }

    /** Package-local deterministic idle boundary for tests; no polling or sleeps. */
    internal fun awaitIdle(timeout: Duration): BrokerWaitResult = lock.withLock {
        waitLocked(timeout, failOnFatal = true) { idle && queue.isEmpty() }
    }
}
