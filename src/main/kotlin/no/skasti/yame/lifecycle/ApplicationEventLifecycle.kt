package no.skasti.yame.lifecycle

import no.skasti.yame.observer.EventBroker
import no.skasti.yame.observer.BrokerWaitResult
import no.skasti.yame.observer.YameEvents
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Process owner: supervision never needs dispatcher progress or a final event. */
internal class ApplicationEventLifecycle(
    private val broker: EventBroker = YameEvents.current,
    private val timeout: Duration = Duration.ofSeconds(5),
    private val sealFacade: () -> Unit = YameEvents::shutdown,
    private val report: (String) -> Unit = System.err::println,
    private val terminate: (Int) -> Unit = { Runtime.getRuntime().halt(it) },
) : AutoCloseable {
    private val producers = CopyOnWriteArrayList<AutoCloseable>()
    private val consumers = CopyOnWriteArrayList<AutoCloseable>()
    private val registrationLock = Any()
    private val closing = AtomicBoolean()
    private val fatalReported = AtomicBoolean()
    private val fatalReportComplete = CountDownLatch(1)
    @Volatile private var deadlineNanos = Long.MAX_VALUE
    private val failed = AtomicBoolean()
    private val completed = CountDownLatch(1)
    private val supervisor = Thread({
        while (completed.count != 0L) {
            val failure = broker.awaitFatal(Duration.ofMillis(100))
            if (failure != null) {
                failed.set(true)
                finish("YAME fatal event broker failure: $failure")
                return@Thread
            }
            if (broker.status.terminated) return@Thread
        }
    }, "yame-event-supervisor").apply { isDaemon = true; start() }

    fun producer(value: AutoCloseable) = synchronized(registrationLock) { check(!closing.get()); producers += value }
    fun consumer(value: AutoCloseable) = synchronized(registrationLock) { check(!closing.get()); consumers += value }

    override fun close() = finish(null)

    private fun finish(fatalMessage: String?) {
        if (fatalMessage != null && fatalReported.compareAndSet(false, true)) {
            Thread({
                try { report(fatalMessage) } finally { fatalReportComplete.countDown() }
            }, "yame-event-fatal-report").apply { isDaemon = true; start() }
        }
        val startCleanup = synchronized(registrationLock) {
            closing.compareAndSet(false, true).also { first ->
                if (first) deadlineNanos = System.nanoTime() + timeout.toNanos()
            }
        }
        if (startCleanup) {
            // Both cleanup and diagnostics may block in arbitrary IO. The supervising
            // caller retains the deadline and can halt without those threads or hooks.
            Thread({
                try {
                    producers.reversed().forEach { producer ->
                        try { producer.close() } catch (error: Throwable) {
                            failed.set(true)
                            report("YAME producer shutdown failed: ${error.message}")
                        }
                    }
                    broker.close()
                    if (broker.awaitClosed(timeout) != BrokerWaitResult.COMPLETED) {
                        error("event dispatcher did not finish")
                    }
                    consumers.reversed().forEach { it.close() }
                    sealFacade()
                } catch (error: Throwable) {
                    failed.set(true)
                    runCatching { report("YAME incomplete event shutdown: ${error.message}") }
                } finally {
                    completed.countDown()
                }
            }, "yame-event-cleanup").apply { isDaemon = true; start() }
        }
        fun remaining() = (deadlineNanos - System.nanoTime()).coerceAtLeast(0)
        val done = try {
            val cleanedUp = completed.await(remaining(), TimeUnit.NANOSECONDS)
            if (fatalReported.get()) fatalReportComplete.await(remaining(), TimeUnit.NANOSECONDS)
            cleanedUp
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (!done || failed.get() || broker.status.failure != null) {
            // Do not let a blocked stderr/file writer or shutdown hook prevent failure exit.
            terminate(1)
        }
    }
}
