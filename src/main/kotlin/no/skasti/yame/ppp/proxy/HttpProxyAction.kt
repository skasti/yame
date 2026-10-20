package no.skasti.yame.ppp.proxy

import no.skasti.yame.observer.AbstractYameEvent
import no.skasti.yame.observer.VersionNumber

enum class HttpProxyActionKind { ROUTED, REQUEST, REDIRECT, RESPONSE, ERROR }

data class HttpProxyAction(
    val flowId: String,
    val kind: HttpProxyActionKind,
    val message: String,
) : AbstractYameEvent() {
    override val typeName = "yame.ppp.http-proxy.action"
    override val version = VersionNumber(1, 0, 0)
}
