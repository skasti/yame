package no.skasti.serialmodem.ppp

import java.io.Closeable
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

interface IcmpEchoProxy : Closeable {
    fun echo(
        destination: Ipv4Address,
        timeoutMillis: Long,
        callback: (Result<Boolean>) -> Unit,
    )

    override fun close() = Unit
}

class SystemPingIcmpEchoProxy : IcmpEchoProxy {
    private val executor = ThreadPoolExecutor(
        MAX_CONCURRENT_PINGS,
        MAX_CONCURRENT_PINGS,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_QUEUED_PINGS),
        { task ->
            Thread(task, "icmp-echo-proxy").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    @Volatile
    private var closed = false

    override fun echo(
        destination: Ipv4Address,
        timeoutMillis: Long,
        callback: (Result<Boolean>) -> Unit,
    ) {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (closed) return

        try {
            executor.execute {
                val result = runCatching {
                    runSystemPing(destination, timeoutMillis)
                }
                if (!closed) {
                    callback(result)
                }
            }
        } catch (_: RejectedExecutionException) {
            if (!closed) {
                callback(
                    Result.failure(
                        IllegalStateException("ICMP echo proxy is busy"),
                    ),
                )
            }
        }
    }

    private fun runSystemPing(
        destination: Ipv4Address,
        timeoutMillis: Long,
    ): Boolean {
        val command =
            if (isWindows()) {
                listOf(
                    "ping",
                    "-n",
                    "1",
                    "-w",
                    timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toString(),
                    destination.toString(),
                )
            } else {
                listOf(
                    "ping",
                    "-n",
                    "-c",
                    "1",
                    destination.toString(),
                )
            }

        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        return try {
            val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw e
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "")
            .lowercase(Locale.ROOT)
            .startsWith("windows")

    override fun close() {
        closed = true
        executor.shutdownNow()
    }

    companion object {
        private const val MAX_CONCURRENT_PINGS = 4
        private const val MAX_QUEUED_PINGS = 16
    }
}
