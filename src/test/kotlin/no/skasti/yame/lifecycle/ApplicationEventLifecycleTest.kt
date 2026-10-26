package no.skasti.yame.lifecycle

import no.skasti.yame.logging.DiagnosticMessage
import no.skasti.yame.observer.*
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ApplicationEventLifecycleTest {
    @Test fun `concurrent shutdown closes each owner once and rejects newly created components safely`() {
        val broker = EventBroker()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val producersClosed = java.util.concurrent.atomic.AtomicInteger()
        val consumersClosed = java.util.concurrent.atomic.AtomicInteger()
        val rejectedClosed = java.util.concurrent.atomic.AtomicInteger()
        val owner = ApplicationEventLifecycle(broker, sealFacade = {},
            report = { fail(it) }, terminate = { fail("unexpected exit $it") })
        owner.producer(AutoCloseable {
            producersClosed.incrementAndGet()
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        })
        owner.consumer(AutoCloseable { consumersClosed.incrementAndGet() })
        val failures = CopyOnWriteArrayList<Throwable>()
        val closers = (1..4).map {
            Thread { try { owner.close() } catch (error: Throwable) { failures += error } }.apply { start() }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { owner.consumer(AutoCloseable { rejectedClosed.incrementAndGet() }) }
            assertFailsWith<IllegalStateException> { owner.producer(AutoCloseable { rejectedClosed.incrementAndGet() }) }
        } finally {
            release.countDown()
            closers.forEach { it.join(3_000) }
        }
        assertTrue(closers.none { it.isAlive })
        assertTrue(failures.isEmpty(), failures.toString())
        owner.close()
        assertEquals(1, producersClosed.get())
        assertEquals(1, consumersClosed.get())
        assertEquals(2, rejectedClosed.get(), "components rejected during shutdown must be closed")
        assertTrue(broker.status.terminated)
    }

    @Test fun `normal shutdown stops producers before draining and releases consumers last`() {
        val broker = EventBroker()
        val order = CopyOnWriteArrayList<String>()
        val owner = ApplicationEventLifecycle(broker, sealFacade = { order += "seal" },
            report = { order += it }, terminate = { order += "exit-$it" })
        val subscription = broker.subscribe("console") { order += (it as DiagnosticMessage).message }
        owner.consumer(AutoCloseable { subscription.close(); order += "consumer" })
        owner.producer(AutoCloseable { order += "stop"; broker.publish(DiagnosticMessage("final")) })
        owner.close()
        assertEquals(listOf("stop", "final", "consumer", "seal"), order.toList())
        assertTrue(broker.status.terminated)
    }

    @Test fun `caught full queue still triggers independent fatal supervision while TUI is blocked`() {
        val broker = EventBroker(EventBrokerConfig(capacity = 1))
        assertEquals(Duration.ofMillis(10), broker.config.publishTimeout)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val reports = CopyOnWriteArrayList<String>()
        val exits = LinkedBlockingQueue<Int>()
        val owner = ApplicationEventLifecycle(broker, timeout = Duration.ofMillis(200),
            sealFacade = {}, report = reports::add, terminate = { exits.offer(it) })
        broker.subscribe("blocked-tui") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        owner.producer(AutoCloseable { stopped.countDown() })
        try {
            broker.publish(DiagnosticMessage("render"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // Publication on a different thread returns even though rendering has not returned.
            val publisher = Thread { broker.publish(DiagnosticMessage("pending")) }.apply { start() }
            publisher.join(1_000)
            assertFalse(publisher.isAlive)
            assertEquals(1, broker.status.queuedEvents)
            assertFailsWith<QueueFullException> { broker.publish(DiagnosticMessage("overflow")) }
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            assertEquals(1, exits.poll(2, TimeUnit.SECONDS))
            assertTrue(reports.any { it.contains("fatal event broker failure") })
            assertFalse(broker.status.terminated, "fatal exit cannot require the blocked dispatcher")
        } finally {
            release.countDown()
            broker.close()
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(Duration.ofSeconds(5)))
        }
    }

    @Test fun `failed subscriber does not prevent later diagnostics or graceful shutdown`() {
        val broker = EventBroker()
        val seen = CopyOnWriteArrayList<String>()
        val owner = ApplicationEventLifecycle(broker, sealFacade = {}, terminate = { fail("unexpected exit $it") })
        broker.subscribe("failed-console") { error("broken console") }
        broker.subscribe("healthy") { seen += (it as DiagnosticMessage).message }
        broker.publish(DiagnosticMessage("message"))
        owner.close()
        assertEquals(listOf("message"), seen.toList())
        assertEquals(1, broker.status.subscriberFailures)
    }

    @Test fun `failed producer preserves reverse cleanup order and prevents premature facade sealing`() {
        val broker = EventBroker()
        val order = CopyOnWriteArrayList<String>()
        val exits = mutableListOf<Int>()
        val owner = ApplicationEventLifecycle(broker, sealFacade = { order += "seal" },
            report = {}, terminate = exits::add)
        owner.producer(AutoCloseable { order += "producer-first" })
        owner.producer(AutoCloseable { order += "producer-failed"; error("producer failed") })
        owner.producer(AutoCloseable { order += "producer-last" })
        owner.consumer(AutoCloseable { order += "consumer-first" })
        owner.consumer(AutoCloseable { order += "consumer-last" })

        owner.close()

        assertEquals(listOf("producer-last", "producer-failed", "producer-first", "consumer-last", "consumer-first"), order.toList())
        assertTrue(broker.status.terminated)
        assertEquals(listOf(1), exits)
    }

    @Test fun `failed consumer does not prevent remaining consumers or safe facade sealing`() {
        val broker = EventBroker()
        val order = CopyOnWriteArrayList<String>()
        val exits = mutableListOf<Int>()
        val owner = ApplicationEventLifecycle(broker, sealFacade = { order += "seal" },
            report = {}, terminate = exits::add)
        owner.producer(AutoCloseable { order += "producer" })
        owner.consumer(AutoCloseable { order += "consumer-first" })
        owner.consumer(AutoCloseable { order += "consumer-failed"; error("consumer failed") })
        owner.consumer(AutoCloseable { order += "consumer-last" })

        owner.close()

        assertEquals(listOf("producer", "consumer-last", "consumer-failed", "consumer-first", "seal"), order.toList())
        assertEquals(listOf(1), exits)
        owner.close()
        assertEquals(listOf(1, 1), exits, "cleanup failure remains latched after repeated close")
    }

    @Test fun `throwing reporter cannot interrupt cleanup or conceal its failures`() {
        val broker = EventBroker()
        val order = CopyOnWriteArrayList<String>()
        val reports = java.util.concurrent.atomic.AtomicInteger()
        val exits = mutableListOf<Int>()
        val owner = ApplicationEventLifecycle(broker, sealFacade = { order += "seal" },
            report = { reports.incrementAndGet(); error("report failed") }, terminate = exits::add)
        owner.producer(AutoCloseable { order += "producer-first" })
        owner.producer(AutoCloseable { order += "producer-failed"; error("original producer failure") })
        owner.consumer(AutoCloseable { order += "consumer-first" })
        owner.consumer(AutoCloseable { order += "consumer-failed"; error("original consumer failure") })

        owner.close()

        assertEquals(listOf("producer-failed", "producer-first", "consumer-failed", "consumer-first"), order.toList())
        assertTrue(reports.get() >= 2, "a reporter exception must not disable subsequent diagnostic attempts")
        assertTrue(broker.status.terminated)
        assertEquals(listOf(1), exits)
    }

    @Test fun `broker close and wait exceptions still allow cleanup when termination is confirmed`() {
        for (failureAt in listOf("close", "wait")) {
            val broker = EventBroker()
            val order = CopyOnWriteArrayList<String>()
            val exits = mutableListOf<Int>()
            val owner = ApplicationEventLifecycle(broker, sealFacade = { order += "seal" },
                report = {}, terminate = exits::add,
                closeBroker = {
                    order += "broker-close"
                    broker.close()
                    if (failureAt == "close") error("close failed after stopping admission")
                },
                awaitBrokerClosed = { timeout ->
                    order += "broker-wait"
                    val result = broker.awaitClosed(timeout)
                    if (failureAt == "wait") {
                        check(result == BrokerWaitResult.COMPLETED)
                        error("wait failed after termination")
                    }
                    result
                })
            owner.producer(AutoCloseable { order += "producer" })
            owner.consumer(AutoCloseable { order += "consumer" })

            owner.close()

            assertEquals(listOf("producer", "broker-close", "broker-wait", "consumer", "seal"), order.toList(), failureAt)
            assertTrue(broker.status.terminated, failureAt)
            assertEquals(listOf(1), exits, failureAt)
        }
    }

    @Test fun `unconfirmed broker termination skips consumer resources and facade sealing`() {
        val broker = EventBroker()
        val order = CopyOnWriteArrayList<String>()
        val reports = CopyOnWriteArrayList<String>()
        val exits = mutableListOf<Int>()
        val owner = ApplicationEventLifecycle(broker, sealFacade = { order += "seal" },
            report = reports::add, terminate = exits::add,
            closeBroker = { error("close failed before stopping admission") },
            awaitBrokerClosed = { BrokerWaitResult.TIMED_OUT })
        owner.producer(AutoCloseable { order += "producer" })
        owner.consumer(AutoCloseable { order += "consumer" })
        try {
            owner.close()
            assertEquals(listOf("producer"), order.toList())
            assertFalse(broker.status.closing)
            assertEquals(listOf(1), exits)
            assertTrue(reports.any { it.contains("consumer shutdown skipped") })
            assertTrue(reports.any { it.contains("facade shutdown skipped") })
        } finally {
            broker.close()
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(Duration.ofSeconds(5)))
        }
    }

    @Test fun `facade sealing failure preserves cleanup failure status`() {
        val broker = EventBroker()
        val order = CopyOnWriteArrayList<String>()
        val exits = mutableListOf<Int>()
        val owner = ApplicationEventLifecycle(broker, sealFacade = { order += "seal"; error("seal failed") },
            report = { error("report failed") }, terminate = exits::add)
        owner.producer(AutoCloseable { order += "producer" })
        owner.consumer(AutoCloseable { order += "consumer" })

        owner.close()

        assertEquals(listOf("producer", "consumer", "seal"), order.toList())
        assertTrue(broker.status.terminated)
        assertEquals(listOf(1), exits)
    }

    @Test fun `blocked reporting and a full diagnostic backlog cannot delay remaining cleanup`() {
        val broker = EventBroker()
        val reportEntered = CountDownLatch(1)
        val releaseReport = CountDownLatch(1)
        val closedConsumers = java.util.concurrent.atomic.AtomicInteger()
        val exits = mutableListOf<Int>()
        val owner = ApplicationEventLifecycle(broker, timeout = Duration.ofMillis(200), sealFacade = {},
            report = { reportEntered.countDown(); releaseReport.await(5, TimeUnit.SECONDS) }, terminate = exits::add)
        // Exceed the bounded diagnostic backlog while its sole writer is blocked.
        repeat(40) { owner.consumer(AutoCloseable { closedConsumers.incrementAndGet(); error("consumer failed") }) }
        try {
            owner.close()
            assertTrue(reportEntered.await(2, TimeUnit.SECONDS))
            assertEquals(40, closedConsumers.get())
            assertTrue(broker.status.terminated)
            assertEquals(listOf(1), exits)
        } finally { releaseReport.countDown() }
    }

    @Test fun `blocked producer cannot prevent deadline enforcement or cause premature sealing`() {
        val broker = EventBroker()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sealed = CountDownLatch(1)
        val closedConsumer = CountDownLatch(1)
        val cleanupThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val exits = mutableListOf<Int>()
        val owner = ApplicationEventLifecycle(broker, timeout = Duration.ofMillis(200),
            sealFacade = { sealed.countDown() }, report = {}, terminate = exits::add)
        owner.producer(AutoCloseable {
            cleanupThread.set(Thread.currentThread())
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        })
        owner.consumer(AutoCloseable { closedConsumer.countDown() })
        try {
            owner.close()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1), exits)
            assertEquals(1L, closedConsumer.count)
            assertEquals(1L, sealed.count)
            assertFalse(broker.status.closing)
        } finally {
            release.countDown()
            // The injected terminate callback returns; let its cleanup thread finish.
            cleanupThread.get()?.join(5_000)
            assertFalse(cleanupThread.get()?.isAlive == true)
            assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(Duration.ofSeconds(5)))
        }
    }

    @Test fun `blocked cleanup or diagnostic reporting cannot prevent bounded fatal exit`() {
        val broker = EventBroker(EventBrokerConfig(capacity = 1, publishTimeout = Duration.ZERO))
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val exits = LinkedBlockingQueue<Int>()
        ApplicationEventLifecycle(broker, timeout = Duration.ofMillis(100), sealFacade = {},
            report = { release.await(5, TimeUnit.SECONDS) }, terminate = { exits.offer(it) })
        broker.subscribe("blocked") { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        try {
            broker.publish(DiagnosticMessage("first"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            broker.publish(DiagnosticMessage("pending"))
            runCatching { broker.publish(DiagnosticMessage("overflow")) }
            assertEquals(1, exits.poll(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            broker.close()
            broker.awaitClosed(Duration.ofSeconds(5))
        }
    }
    @Test fun `production fatal exit terminates a JVM nonzero with a permanently blocked dispatcher`() {
        val classpath = listOf(FatalBrokerProcess::class.java, ApplicationEventLifecycle::class.java, Unit::class.java)
            .map { java.nio.file.Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(java.io.File.pathSeparator)
        val process = ProcessBuilder(
            java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, FatalBrokerProcess::class.java.name,
        ).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(8, TimeUnit.SECONDS), "fatal JVM did not terminate")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(1, process.exitValue(), output)
            assertTrue(output.contains("fatal event broker failure"), output)
        } finally { process.destroyForcibly() }
    }

}


/** Separate process: use production halt, not a test replacement for process termination. */
object FatalBrokerProcess {
    @JvmStatic fun main(args: Array<String>) {
        val broker = EventBroker(EventBrokerConfig(capacity = 1))
        ApplicationEventLifecycle(broker, timeout = Duration.ofMillis(200), sealFacade = {})
        val entered = CountDownLatch(1)
        broker.subscribe("permanently-blocked") { entered.countDown(); CountDownLatch(1).await() }
        broker.publish(DiagnosticMessage("first"))
        check(entered.await(5, TimeUnit.SECONDS))
        broker.publish(DiagnosticMessage("pending"))
        runCatching { broker.publish(DiagnosticMessage("overflow")) }
        CountDownLatch(1).await()
    }
}
