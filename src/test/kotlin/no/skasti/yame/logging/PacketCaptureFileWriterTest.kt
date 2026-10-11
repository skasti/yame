package no.skasti.yame.logging

import no.skasti.yame.observer.YameEvent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PacketCaptureFileWriterTest {
    private val event = YameEvent.UnhandledPacket("ppp-protocol-49187", "PAP unsupported", 49187, byteArrayOf(1, 2))
    private val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    @Test
    fun `temporary and published files are private and only complete fixture becomes visible`() {
        val directory = Files.createTempDirectory("yame-private-capture")
        val posix = Files.getFileStore(directory).supportsFileAttributeView("posix")
        val writer = PacketCaptureFileWriter { path, text ->
            assertTrue(Files.list(directory).use { paths -> paths.noneMatch { it.toString().endsWith(".txt") } })
            if (posix) assertEquals(ownerOnly, Files.getPosixFilePermissions(path))
            Files.writeString(path, text)
        }
        val path = writer.write(directory, event, Instant.now(), event.bytes())
        assertEquals(listOf(path), Files.list(directory).use { it.toList() })
        if (posix) assertEquals(ownerOnly, Files.getPosixFilePermissions(path))
        assertContains(path.readText(), "data=0102\n")
    }

    @Test
    fun `failure after partial write removes temporary file and never publishes a fixture`() {
        val directory = Files.createTempDirectory("yame-failed-capture")
        val writer = PacketCaptureFileWriter { path, _ ->
            Files.writeString(path, "format=yame-unhandled-v1\n")
            throw IOException("simulated disk full or close failure")
        }
        assertFailsWith<IOException> { writer.write(directory, event, Instant.now(), event.bytes()) }
        assertEquals(0, Files.list(directory).use { it.count() }.toInt())
    }
}
