package no.skasti.yame.logging

import no.skasti.yame.observer.YameEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID

/** Publishes only complete fixtures, with restrictive permissions from creation onward. */
internal class PacketCaptureFileWriter(
    private val writeText: (Path, String) -> Unit = { path, text -> Files.writeString(path, text) },
) {
    fun write(directory: Path, event: YameEvent.UnhandledPacket, timestamp: Instant, bytes: ByteArray): Path {
        val temporary = if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.createTempFile(directory, ".capture-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createTempFile(directory, ".capture-", ".tmp")
        }
        try {
            val text = buildString {
                appendLine("format=yame-unhandled-v1")
                appendLine("timestamp=$timestamp")
                appendLine("packet-type=${event.packetType}")
                appendLine("reason=${event.reason}")
                appendLine("ppp-protocol=${event.pppProtocol}")
                appendLine("encoding=hex")
                appendLine("length=${bytes.size}")
                appendLine("data=${bytes.toHexString()}")
            }
            writeText(temporary, text)
            val target = directory.resolve("${timestamp.toString().replace(':', '-')}-${UUID.randomUUID()}.txt")
            // If the filesystem cannot publish atomically, leave no purported complete fixture.
            Files.move(temporary, target, ATOMIC_MOVE)
            return target
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
