package no.skasti.yame.ppp.session

import no.skasti.yame.observer.AbstractYameEvent
import no.skasti.yame.observer.VersionNumber
import no.skasti.yame.observer.YameEvent
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Process-unique PPP identity; neither flow keys nor IPCP generations are globally unique. */
data class PppSessionIdentity(val connectionId: Long, val id: Long) {
    companion object {
        private val next = AtomicLong()
        val UNSCOPED = PppSessionIdentity(0, 0)
        fun create(connectionId: Long = 0): PppSessionIdentity = PppSessionIdentity(connectionId, next.incrementAndGet())
    }
}

interface PppObservation : YameEvent { val session: PppSessionIdentity }

data class PppSessionEnded(
    override val session: PppSessionIdentity,
    val occurredAt: Instant = Instant.now(),
) : AbstractYameEvent(), PppObservation {
    override val typeName = "yame.ppp.session.ended"
    override val version = VersionNumber(1, 0, 0)
}

enum class DnsTransport { UDP, TCP }
enum class TransferDirection { TO_HOST, TO_PEER }
enum class TransferKind { TCP, DNS }
enum class TransferState { CONNECTING, OPEN, CLOSED, FAILED }

/** Immutable diagnostic snapshot; bytes are an IPv4 packet or a PPP payload. */
class UnhandledPacket(
    val packetType: String,
    val reason: String,
    val pppProtocol: Int,
    bytes: ByteArray,
    override val session: PppSessionIdentity = PppSessionIdentity.UNSCOPED,
    val occurredAt: Instant = Instant.now(),
) : AbstractYameEvent(), PppObservation {
    override val typeName = "yame.ppp.packet.unhandled"
    override val version = VersionNumber(1, 1, 0)
    private val snapshot = bytes.copyOf()
    val byteCount: Int get() = snapshot.size
    val bytes: ByteArray get() = snapshot.copyOf()
    fun bytes(): ByteArray = bytes
}

data class DnsQuery(
    val key: String,
    val transport: DnsTransport,
    val id: Int?,
    val name: String?,
    val type: String?,
    val upstream: String,
    val bytes: Int,
    override val session: PppSessionIdentity = PppSessionIdentity.UNSCOPED,
    val occurredAt: Instant = Instant.now(),
) : AbstractYameEvent(), PppObservation {
    override val typeName = "yame.ppp.dns.query"
    override val version = VersionNumber(1, 1, 0)
}

data class DnsResponse(
    val key: String,
    val transport: DnsTransport,
    val id: Int?,
    val name: String?,
    val responseCode: Int?,
    val answerCount: Int?,
    val truncated: Boolean,
    val bytes: Int,
    override val session: PppSessionIdentity = PppSessionIdentity.UNSCOPED,
    val occurredAt: Instant = Instant.now(),
) : AbstractYameEvent(), PppObservation {
    override val typeName = "yame.ppp.dns.response"
    override val version = VersionNumber(1, 1, 0)
}

data class DnsFailure(
    val key: String,
    val transport: DnsTransport,
    val message: String,
    override val session: PppSessionIdentity = PppSessionIdentity.UNSCOPED,
    val occurredAt: Instant = Instant.now(),
) : AbstractYameEvent(), PppObservation {
    override val typeName = "yame.ppp.dns.failure"
    override val version = VersionNumber(1, 1, 0)
}

data class TransferStarted(
    val flowId: String,
    val destination: String,
    val via: String,
    val kind: TransferKind,
    override val session: PppSessionIdentity = PppSessionIdentity.UNSCOPED,
    val occurredAt: Instant = Instant.now(),
    val transferIdentity: TransferIdentity? = null,
) : AbstractYameEvent(), PppObservation {
    override val typeName = "yame.ppp.transfer.started"
    override val version = VersionNumber(1, 2, 0)
}

data class TransferStateChanged(
    val flowId: String,
    val state: TransferState,
    val detail: String? = null,
    override val session: PppSessionIdentity = PppSessionIdentity.UNSCOPED,
    val occurredAt: Instant = Instant.now(),
    val transferIdentity: TransferIdentity? = null,
) : AbstractYameEvent(), PppObservation {
    override val typeName = "yame.ppp.transfer.state-changed"
    override val version = VersionNumber(1, 2, 0)
}

data class TransferBytes(
    val flowId: String,
    val direction: TransferDirection,
    val bytes: Int,
    override val session: PppSessionIdentity = PppSessionIdentity.UNSCOPED,
    val occurredAt: Instant = Instant.now(),
    val transferIdentity: TransferIdentity? = null,
) : AbstractYameEvent(), PppObservation {
    override val typeName = "yame.ppp.transfer.bytes"
    override val version = VersionNumber(1, 2, 0)
}
