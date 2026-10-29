package no.skasti.yame.ppp.proxy

import no.skasti.yame.observer.AbstractYameEvent
import no.skasti.yame.observer.VersionNumber
import no.skasti.yame.ppp.session.PppObservation
import no.skasti.yame.ppp.session.PppSessionIdentity
import no.skasti.yame.ppp.session.TransferIdentity
import java.net.URI
import java.time.Instant

enum class TransferResourcePhase { REQUEST, RESPONSE }

/** A complete metadata snapshot for a downstream request, not a per-file byte counter.
 * requestNumber counts client requests, not upstream redirect hops. RESPONSE supersedes
 * REQUEST within that number. occurredAt is captured at resolution/preparation time.
 */
data class TransferResourceChanged(
    val transferIdentity: TransferIdentity,
    val requestNumber: Long,
    val phase: TransferResourcePhase,
    val requestUri: URI,
    val effectiveUri: URI,
    val displayFilename: String?,
    val occurredAt: Instant,
) : AbstractYameEvent(), PppObservation {
    override val session: PppSessionIdentity get() = transferIdentity.session
    override val typeName = "yame.ppp.http.transfer-resource-changed"
    override val version = VersionNumber(1, 0, 0)

    fun supersedes(previous: TransferResourceChanged): Boolean =
        requestNumber > previous.requestNumber ||
            (requestNumber == previous.requestNumber && phase.ordinal > previous.phase.ordinal)
}
