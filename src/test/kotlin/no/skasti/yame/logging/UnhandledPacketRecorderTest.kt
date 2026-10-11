package no.skasti.yame.logging

import no.skasti.yame.observer.YameEvent
import java.nio.file.Files
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UnhandledPacketRecorderTest {
    @Test
    fun `blocked summary writer does not block packet submission and runs off caller thread`() {
        val root = Files.createTempDirectory("yame-capture-summary")
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val submitter = Thread.currentThread()
        val captured = AtomicInteger()
        val recorder = UnhandledPacketRecorder(root)
        try {
            recorder.record(YameEvent.UnhandledPacket("udp", "unsupported", 33, byteArrayOf(1)), onReceived = {
                assertTrue(Thread.currentThread() !== submitter)
                workerEntered.countDown()
                releaseWorker.await(5, TimeUnit.SECONDS)
            }) { captured.incrementAndGet() }
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
            // The first worker is still blocked, yet a second submission returns immediately.
            recorder.record(YameEvent.UnhandledPacket("tcp", "unsupported", 33, byteArrayOf(2))) {
                captured.incrementAndGet()
            }
        } finally {
            releaseWorker.countDown()
            recorder.close()
        }
        assertEquals(2, captured.get())
    }

    @Test
    fun `per type and total limits apply together and live lowering prunes existing captures`() {
        val root = Files.createTempDirectory("yame-capture-limits")
        fun captures() = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList()
        }
        UnhandledPacketRecorder(root, PacketCaptureLimits(maxPerType = 2, maxTotal = 3)).use { recorder ->
            repeat(4) { index ->
                val saved = CountDownLatch(1)
                recorder.record(YameEvent.UnhandledPacket("udp", "udp-$index", 33, byteArrayOf(1))) {
                    saved.countDown()
                }
                assertTrue(saved.await(5, TimeUnit.SECONDS))
                assertTrue(captures().size <= 2)
            }
            assertTrue(captures().none { "reason=udp-0" in it.readText() || "reason=udp-1" in it.readText() })
            repeat(2) { index ->
                val saved = CountDownLatch(1)
                recorder.record(YameEvent.UnhandledPacket("tcp", "tcp-$index", 33, byteArrayOf(1))) {
                    saved.countDown()
                }
                assertTrue(saved.await(5, TimeUnit.SECONDS))
            }
            assertEquals(3, captures().size)
            assertEquals(2, captures().count { it.parent.fileName.toString() == "tcp" })
            recorder.updateLimits(PacketCaptureLimits(maxPerType = 1, maxTotal = 2))
        }
        assertEquals(2, captures().size)
        assertTrue(captures().groupBy { it.parent }.values.all { it.size <= 1 })
        UnhandledPacketRecorder(root, PacketCaptureLimits(maxPerType = 10, maxTotal = 1)).close()
        assertEquals(1, captures().size)
    }

    @Test
    fun `full capture queue drops excess work without blocking the caller`() {
        val root = Files.createTempDirectory("yame-capture-queue")
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val captured = AtomicInteger()
        val event = YameEvent.UnhandledPacket("udp", "unsupported", 33, byteArrayOf(1))
        val recorder = UnhandledPacketRecorder(root)
        val caller = Thread.currentThread()
        val errorOnCaller = AtomicBoolean()
        val originalError = System.err
        System.setErr(PrintStream(object : OutputStream() {
            override fun write(value: Int) {
                if (Thread.currentThread() === caller) errorOnCaller.set(true)
            }
        }))
        try {
            recorder.record(event) {
                workerEntered.countDown()
                releaseWorker.await(5, TimeUnit.SECONDS)
                captured.incrementAndGet()
            }
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
            repeat(70) { recorder.record(event) { captured.incrementAndGet() } }
        } finally {
            releaseWorker.countDown()
            try { recorder.close() } finally { System.setErr(originalError) }
        }
        assertEquals(65, captured.get())
        assertFalse(errorOnCaller.get(), "Queue overflow must not write stderr on the receive thread")
    }

    @Test
    fun `default levels retain summary and original bytes independent of caller mutation`() {
        val root = Files.createTempDirectory("yame-capture")
        val bytes = byteArrayOf(0, 1, 127, -1)
        val event = YameEvent.UnhandledPacket("icmp-type-3", "egress type=3 code=1 not handled", 33, bytes)
        bytes.fill(42)
        event.bytes().fill(43)
        YameLogManager(YameLogManager.defaultLevels(), root).use { it.eventSink(event) }
        val captures = Files.list(root.resolve("unhandled/icmp-type-3")).use { it.toList() }
        assertEquals(1, captures.size)
        val properties = captures.single().readText().lineSequence().filter { it.isNotBlank() }.associate {
            val parts = it.split('=', limit = 2)
            parts[0] to parts[1]
        }
        assertEquals("yame-unhandled-v1", properties["format"])
        assertEquals("33", properties["ppp-protocol"])
        assertEquals("4", properties["length"])
        assertContentEquals(byteArrayOf(0, 1, 127, -1), properties.getValue("data").hexToByteArray())
        assertContains(root.resolve("unhandled.log").readText(), "egress type=3 code=1 not handled")
        assertTrue(!captures.single().fileName.toString().contains(':'))
    }

    @Test
    fun `retention covers all types and previous runs and preserves unrelated files`() {
        val root = Files.createTempDirectory("yame-capture-retention")
        fun record(index: Int, type: String) {
            UnhandledPacketRecorder(root, initialLimits = PacketCaptureLimits(maxTotal = 2)).use {
                it.record(YameEvent.UnhandledPacket(type, "packet-$index", 33, byteArrayOf(index.toByte()))) {}
            }
        }
        record(0, "icmp-type-3")
        val unrelated = root.resolve("unhandled/icmp-type-3/notes.txt")
        Files.writeString(unrelated, "keep me")
        record(1, "udp")
        record(2, "tcp")
        val contents = Files.walk(root.resolve("unhandled")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it != unrelated }.map { it.readText() }.toList()
        }
        assertEquals(2, contents.size)
        assertTrue(contents.none { "reason=packet-0" in it })
        assertEquals("keep me", unrelated.readText())
    }

    @Test
    fun `invalid types and IO failures do not escape to packet handling`() {
        val root = Files.createTempDirectory("yame-capture-failure")
        UnhandledPacketRecorder(root).use {
            it.record(YameEvent.UnhandledPacket("../escape", "unsupported", 33, byteArrayOf(1))) {
                error("must not capture invalid type")
            }
        }
        var captured = false
        val blockedRoot = Files.createTempFile("yame-capture-blocked", ".tmp")
        UnhandledPacketRecorder(blockedRoot).use {
            it.record(YameEvent.UnhandledPacket("udp", "unsupported", 33, byteArrayOf(1))) {
                captured = true
            }
        }
        assertFalse(captured)
        assertTrue(!Files.exists(root.resolve("unhandled")))
    }
}
