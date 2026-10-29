package no.skasti.yame.ppp.session

import java.util.concurrent.atomic.AtomicLong

/** One TCP connection lifetime, even when a peer reuses its endpoint tuple. */
data class TransferIdentity(
    val session: PppSessionIdentity,
    val generation: Long,
    val instanceId: Long,
) {
    companion object {
        private val next = AtomicLong()
        fun create(session: PppSessionIdentity, generation: Long): TransferIdentity =
            TransferIdentity(session, generation, next.incrementAndGet())
    }
}
