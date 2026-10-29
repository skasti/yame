package no.skasti.yame.observer

import no.skasti.yame.config.ConfigStore
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One bounded FIFO and one worker. Consumers must hand off expensive work themselves. */
class EventBroker(
    capacity: Int = ConfigStore[EventBrokerSettings.QueueCapacity],
    publishTimeout: Duration = Duration.ofMillis(ConfigStore[EventBrokerSettings.PublishTimeoutMillis].toLong()),
    maxSubscribers: Int = ConfigStore[EventBrokerSettings.MaxSubscribers],
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private data class Envelope(val sequence: Long, val event: YameEvent)
    private class Registration(
        val name: String,
        val firstSequence: Long,
        val filter: (YameEvent) -> Boolean,
        val handler: (YameEvent) -> Unit,
    )
    private val queue = ArrayDeque<Envelope>()
    private val registrations = mutableListOf<Registration>()
    private var accepted = 0L
    private var delivered = 0L
    private var closing = false
    private var terminated = false
    private var idle = false
    private var active: String? = null
    private var fatal: BrokerFailure? = null
    private var failures = 0L
    private var lastFailure: SubscriberFailure? = null
    /** Live broker limits; updated by the broker's own subscription. */
    @Volatile var capacity: Int = capacity
        private set
    @Volatile var publishTimeout: Duration = publishTimeout
        private set
    @Volatile var maxSubscribers: Int = maxSubscribers
        private set

    init {
        (EventBrokerSettings.QueueCapacity to capacity).validate()
        // Duration.toMillis truncates nanos: require an exact, nonnegative
        // millisecond value before applying the SettingKey's range validation.
        require(!publishTimeout.isNegative) { "Publish timeout must not be negative" }
        val millis = publishTimeout.toMillis()
        require(publishTimeout == Duration.ofMillis(millis)) { "Publish timeout must use whole milliseconds" }
        require(millis == millis.toInt().toLong()) { "Publish timeout cannot be represented as Int milliseconds" }
        (EventBrokerSettings.PublishTimeoutMillis to millis.toInt()).validate()
        (EventBrokerSettings.MaxSubscribers to maxSubscribers).validate()
    }

    // Explicit constructor values are initial values; subsequent ConfigStore
    // changes always reach the owning broker. Standalone tests with no installed
    // process store can still provide explicit values.
    private val settingsSubscription = runCatching { ConfigStore.onChange[EventBrokerSettings.Group] }.getOrNull()?.let { events ->
        // The initial notification closes the gap between these reads and
        // subscription registration. Ignore unchanged initial values so an
        // explicitly supplied constructor override is not silently replaced.
        val observedCapacity = ConfigStore[EventBrokerSettings.QueueCapacity]
        val observedTimeout = ConfigStore[EventBrokerSettings.PublishTimeoutMillis]
        val observedSubscribers = ConfigStore[EventBrokerSettings.MaxSubscribers]
        var firstNotification = true
        events.subscribe(initial = true) { changes ->
            lock.withLock {
                val initial = firstNotification
                firstNotification = false
                changes[EventBrokerSettings.QueueCapacity]?.let {
                    if (!initial || it.value != observedCapacity) this@EventBroker.capacity = it.value
                }
                changes[EventBrokerSettings.PublishTimeoutMillis]?.let {
                    if (!initial || it.value != observedTimeout) {
                        this@EventBroker.publishTimeout = Duration.ofMillis(it.value.toLong())
                    }
                }
                changes[EventBrokerSettings.MaxSubscribers]?.let {
                    if (!initial || it.value != observedSubscribers) this@EventBroker.maxSubscribers = it.value
                }
                changed.signalAll()
            }
        }
    }

    private val worker = Thread(::dispatch, "yame-events").apply { isDaemon = true; start() }

    data class Status(
        val queuedEvents: Int,
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
        Status(queue.size, accepted, delivered, active, fatal,
            failures, lastFailure, closing, terminated, idle)
    }

    /** Waits at most publishTimeout for queue space; never invokes subscribers. */
    fun publish(event: YameEvent): Long = lock.withLock {
        var remaining = try { publishTimeout.toNanos() } catch (_: ArithmeticException) { Long.MAX_VALUE }
        while (true) {
            fatal?.let { throw BrokerFailedException(it) }
            if (closing) throw BrokerClosedException()
            if (queue.size < capacity) break
            if (remaining <= 0) {
                val failure = BrokerFailure(BrokerFailure.Reason.QUEUE_FULL, capacity, queue.size, active, accepted)
                fatal = failure
                closing = true
                queue.clear()
                changed.signalAll() // fatal supervision never uses the dispatcher or a user callback
                throw QueueFullException(failure)
            }
            // Condition.awaitNanos releases the lock; interruption rejects this attempt
            // without poisoning the broker and preserves the thread's interrupt status.
            try { remaining = changed.awaitNanos(remaining) }
            catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
        }
        val sequence = ++accepted
        queue.addLast(Envelope(sequence, event))
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
        check(registrations.size < maxSubscribers) { "Subscriber capacity exceeded" }
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
    override fun close() {
        settingsSubscription?.close()
        lock.withLock {
            closing = true
            changed.signalAll()
        }
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
                    changed.signalAll() // publishers wait for a pending slot, not delivery
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
            settingsSubscription?.close()
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
