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
