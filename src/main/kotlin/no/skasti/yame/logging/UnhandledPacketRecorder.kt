package no.skasti.yame.logging

import no.skasti.yame.ppp.session.UnhandledPacket

import no.skasti.yame.observer.YameEvent
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounded diagnostic IO, isolated from the serial/PPP receive thread. */
internal class UnhandledPacketRecorder(
    logRoot: Path,
    initialLimits: PacketCaptureLimits = PacketCaptureLimits(),
) : AutoCloseable {
    @Volatile
    var limits: PacketCaptureLimits = initialLimits
        private set

    private val fileWriter = PacketCaptureFileWriter()
    private val root = logRoot.resolve("unhandled")
    private val failureReported = AtomicBoolean()
    private val pendingFailure = AtomicReference<String?>()
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64),
        { task -> Thread(task, "yame-packet-captures").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    init {
        schedulePruning()
    }

    fun record(
        event: UnhandledPacket,
        onReceived: () -> Unit = {},
        onCaptured: (String) -> Unit,
    ) {
        val bytes = event.bytes()
        if (bytes.size > 65535 || !event.packetType.matches(Regex("[a-z0-9-]{1,64}"))) {
            deferFailure("invalid packet capture metadata or size")
            return
        }
        val timestamp = Instant.now()
        try {
            executor.execute {
                reportPendingFailure()
                try {
                    onReceived()
                    Files.createDirectories(root)
                    check(!Files.isSymbolicLink(root)) { "capture root must not be a symbolic link" }
                    val directory = root.resolve(event.packetType)
                    Files.createDirectories(directory)
                    check(!Files.isSymbolicLink(directory)) { "capture directory must not be a symbolic link" }
                    prune(event.packetType)
                    val path = fileWriter.write(directory, event, timestamp, bytes)
                    onCaptured(path.toString())
                } catch (error: Exception) {
                    reportFailure(error.message ?: error.javaClass.simpleName)
                }
            }
        } catch (_: RejectedExecutionException) {
            deferFailure("capture queue full or recorder closed; packet capture dropped")
        }
    }

    fun updateLimits(value: PacketCaptureLimits) {
        limits = value
        schedulePruning()
    }

    private fun schedulePruning() {
        try {
            executor.execute {
                reportPendingFailure()
                runCatching { prune() }.onFailure { reportFailure(it.message ?: "retention failure") }
            }
        } catch (_: RejectedExecutionException) {
            // Queued captures also apply the latest limits before writing.
            deferFailure("capture queue full or recorder closed; retention deferred")
        }
    }

    private fun prune(incomingType: String? = null) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        check(!Files.isSymbolicLink(root)) { "capture root must not be a symbolic link" }
        val current = limits
        // Do not follow links or remove unrelated files. Only this recorder's format is retained.
        val captures = Files.walk(root, 2).use { paths ->
            paths.filter { path ->
                Files.isRegularFile(path, NOFOLLOW_LINKS) && path.parent?.parent == root &&
                    path.fileName.toString().matches(CAPTURE_NAME)
            }.sorted { left, right ->
                Files.getLastModifiedTime(left, NOFOLLOW_LINKS)
                    .compareTo(Files.getLastModifiedTime(right, NOFOLLOW_LINKS))
            }.toList()
        }
        val removed = mutableSetOf<Path>()
        captures.groupBy { it.parent.fileName.toString() }.forEach { (type, files) ->
            val reserved = if (type == incomingType) 1 else 0
            files.take((files.size - current.maxPerType + reserved).coerceAtLeast(0)).forEach {
                Files.delete(it)
                removed.add(it)
            }
        }
        val remaining = captures.filterNot { it in removed }
        val reserved = if (incomingType == null) 0 else 1
        remaining.take((remaining.size - current.maxTotal + reserved).coerceAtLeast(0)).forEach(Files::delete)
    }

    private fun deferFailure(message: String) {
        if (failureReported.get()) return
        pendingFailure.compareAndSet(null, message)
        try {
            executor.execute { reportPendingFailure() }
        } catch (_: RejectedExecutionException) {
            // Already queued work reports this; rejection must never write to stderr on the caller.
        }
    }

    private fun reportPendingFailure() {
        pendingFailure.getAndSet(null)?.let(::reportFailure)
    }

    private fun reportFailure(message: String) {
        if (failureReported.compareAndSet(false, true)) {
            System.err.println("YAME unhandled packet capture failed: $message (further failures suppressed)")
        }
    }

    override fun close() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private val CAPTURE_NAME = Regex("\\d{4}-\\d{2}-\\d{2}T[0-9.-]+Z-[0-9a-f-]{36}\\.txt")
    }
}
