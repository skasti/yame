package no.skasti.yame.runtime

import no.skasti.yame.serial.SerialPortDescriptor
import no.skasti.yame.lifecycle.ApplicationFunctions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** Hold only the first injected scan, so command tests do not race automatic connection/monitoring. */
class RuntimeTestPorts(private val source: () -> List<SerialPortDescriptor> = { emptyList() }) {
    private val first = AtomicBoolean(true)
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    fun scan(): List<SerialPortDescriptor> {
        if (first.compareAndSet(true, false)) {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "test did not release initial port scan" }
        }
        return source()
    }
    fun releaseStartup() = release.countDown()
    fun awaitStartup() = assertTrue(entered.await(5, TimeUnit.SECONDS), "runtime did not enter initial scan")
}

internal class RuntimeTestRun(private val runtime: YameRuntime, private val ports: RuntimeTestPorts) : AutoCloseable {
    private val failure = AtomicReference<Throwable>()
    private val worker = thread(name = "test-runtime") {
        try { runtime.run() } catch (error: Throwable) { failure.set(error) }
    }
    init {
        try { ports.awaitStartup() } catch (error: Throwable) {
            ports.releaseStartup()
            runtime.close()
            worker.join(5_000)
            throw AssertionError("Runtime startup failed: ${failure.get()}", error)
        }
    }
    override fun close() {
        // Stop automatic startup before releasing the test's first scan. A
        // concurrent close/quit may already have removed or rejected the command.
        runCatching { runtime.functions.invoke(ApplicationFunctions.Quit) }
        ports.releaseStartup()
        runtime.close()
        worker.join(5_000)
        assertFalse(worker.isAlive, "runtime loop did not stop")
        assertNull(failure.get(), "runtime loop failed")
    }
}
