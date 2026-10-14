package no.skasti.yame.ppp.dns

import no.skasti.yame.config.ConfigurationBinding
import no.skasti.yame.config.SettingGroup
import no.skasti.yame.config.SettingKey

object DnsSettings {
    val Group = SettingGroup("ppp.dns")
    val Upstream = SettingKey<no.skasti.yame.ppp.ip.Ipv4Address>("dns.upstream", Group)
    internal val bindings: List<ConfigurationBinding<*>> = listOf(
        ConfigurationBinding(Upstream,
            { c -> c.modemConfig.pppDnsConfig.upstreamServer },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(pppDnsConfig = c.modemConfig.pppDnsConfig.copy(upstreamServer = v))) }),
    )
}
