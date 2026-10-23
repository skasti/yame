package no.skasti.yame.ppp.proxy

import no.skasti.yame.config.*

object ProxySettings : Settings("proxy") {
    val Group = SettingGroup("ppp.proxy", this, "")
    val Enabled = SettingKey<Boolean>("http-https-proxy.enabled", Group)

    override val bindings by lazy { listOf(
        IniBinding(Enabled, "http-https-proxy.enabled", { true }, IniValues::boolean, Boolean::toString),
    ) }
}
