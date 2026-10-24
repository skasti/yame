package no.skasti.yame.serial

import no.skasti.yame.observer.AbstractYameEvent
import no.skasti.yame.observer.VersionNumber
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** Monotonic process identities, independent of a runtime's reconnect counter. */
internal object ConnectionIds {
    private val next = AtomicLong()
    fun next(): Long = next.incrementAndGet()
}

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

data class ConnectionStateChanged(
    val connectionId: Long,
    val state: ConnectionState,
    val occurredAt: Instant = Instant.now(),
) : AbstractYameEvent() {
    override val typeName = "yame.serial.connection.state-changed"
    override val version = VersionNumber(1, 0, 0)
}

class AvailablePortsChanged(ports: List<SerialPortDescriptor>, val occurredAt: Instant = Instant.now()) : AbstractYameEvent() {
    val ports: List<SerialPortDescriptor> = Collections.unmodifiableList(ArrayList(ports))
    override val typeName = "yame.serial.ports.changed"
    override val version = VersionNumber(1, 0, 0)
}
