package no.skasti.yame.lifecycle

import no.skasti.yame.observer.EventBroker
import no.skasti.yame.observer.BrokerWaitResult
import no.skasti.yame.observer.YameEvents
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Process owner: supervision never needs dispatcher progress or a final event. */
internal class ApplicationEventLifecycle(
    private val broker: EventBroker = YameEvents.current,
    private val timeout: Duration = Duration.ofSeconds(5),
    private val sealFacade: () -> Unit = YameEvents::shutdown,
    private val report: (String) -> Unit = System.err::println,
    private val terminate: (Int) -> Unit = { Runtime.getRuntime().halt(it) },
    // Narrow shutdown-operation seams allow failure tests without changing EventBroker.
    private val closeBroker: () -> Unit = broker::close,
    private val awaitBrokerClosed: (Duration) -> BrokerWaitResult = broker::awaitClosed,
) : AutoCloseable {
    private val producers = CopyOnWriteArrayList<AutoCloseable>()
    private val consumers = CopyOnWriteArrayList<AutoCloseable>()
    private val registrationLock = Any()
    private val closing = AtomicBoolean()
    private val fatalReported = AtomicBoolean()
    // One writer and a bounded best-effort backlog; reporting never runs on cleanup.
    private val diagnostics = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
        { task -> Thread(task, "yame-event-diagnostics").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    @Volatile private var deadlineNanos = Long.MAX_VALUE
    private val failed = AtomicBoolean()
    private val completed = CountDownLatch(1)
    private val supervisor = Thread({
        while (completed.count != 0L) {
            val brokerFailure = broker.awaitFatal(Duration.ofMillis(100))
            val failure = brokerFailure
                ?: consumers.filterIsInstance<FatalFailureSource>().firstNotNullOfOrNull { it.fatalFailure }
            if (failure != null) {
                failed.set(true)
                finish(if (brokerFailure != null) "YAME fatal event broker failure: $failure"
                    else "YAME fatal presentation failure: $failure")
                return@Thread
            }
            if (broker.status.terminated) return@Thread
        }
    }, "yame-event-supervisor").apply { isDaemon = true; start() }

    fun producer(value: AutoCloseable) = register(value, producers)
    fun consumer(value: AutoCloseable) = register(value, consumers)

    private fun register(value: AutoCloseable, owners: MutableList<AutoCloseable>) {
        val accepted = synchronized(registrationLock) {
            if (closing.get()) false else { owners += value; true }
        }
        if (!accepted) {
            // Shutdown may win while a factory is constructing a component.
            // The rejected component never entered the cleanup lists.
            val error = IllegalStateException("Application is shutting down")
            try { value.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    override fun close() = finish(null)

    private fun finish(fatalMessage: String?) {
        if (fatalMessage != null && fatalReported.compareAndSet(false, true)) {
            reportFailure(fatalMessage)
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
                    var producersQuiesced = true
                    producers.reversed().forEach { producer ->
                        if (!attempt("producer shutdown", producer::close)) producersQuiesced = false
                    }
                    attempt("broker close", closeBroker)
                    attempt("broker drain") {
                        check(awaitBrokerClosed(Duration.ofNanos(remainingNanos())) == BrokerWaitResult.COMPLETED) {
                            "event dispatcher did not finish"
                        }
                    }
                    // A failed wait does not prove the dispatcher is still active (or stopped).
                    // Inspect actual termination before releasing resources its callbacks use.
                    val brokerStopped = broker.status.terminated
                    if (brokerStopped) {
                        consumers.reversed().forEach { consumer ->
                            attempt("consumer shutdown") {
                                if (consumer is DeadlineCloseable) consumer.closeBefore(deadlineNanos)
                                else consumer.close()
                            }
                        }
                    } else {
                        reportFailure("YAME consumer shutdown skipped: event dispatcher has not terminated")
                    }
                    if (producersQuiesced && brokerStopped) {
                        attempt("facade shutdown", sealFacade)
                    } else {
                        reportFailure("YAME facade shutdown skipped: producer or broker shutdown is incomplete")
                    }
                } catch (error: Throwable) {
                    reportFailure("YAME incomplete event shutdown: ${error.message}")
                } finally {
                    completed.countDown()
                }
            }, "yame-event-cleanup").apply { isDaemon = true; start() }
        }
        val done = try {
            val cleanedUp = completed.await(remainingNanos(), TimeUnit.NANOSECONDS)
            diagnostics.shutdown()
            val reported = diagnostics.awaitTermination(remainingNanos(), TimeUnit.NANOSECONDS)
            cleanedUp && reported
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        finally { diagnostics.shutdown() }
        if (!done || failed.get() || broker.status.failure != null) {
            // Do not let a blocked stderr/file writer or shutdown hook prevent failure exit.
            terminate(1)
        }
    }

    private fun remainingNanos() = (deadlineNanos - System.nanoTime()).coerceAtLeast(0)

    private fun attempt(operation: String, action: () -> Unit): Boolean = try {
        action()
        true
    } catch (error: Throwable) {
        reportFailure("YAME $operation failed: ${error.message}")
        false
    }

    private fun reportFailure(message: String) {
        failed.set(true) // Diagnostic rejection, exceptions or blocking cannot conceal this.
        runCatching { diagnostics.execute { runCatching { report(message) } } }
    }
}
