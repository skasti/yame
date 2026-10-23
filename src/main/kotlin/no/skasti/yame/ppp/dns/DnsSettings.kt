package no.skasti.yame.ppp.dns

import no.skasti.yame.config.*

object DnsSettings : Settings("dns") {
    val Group = SettingGroup("ppp.dns", this, "")
    val Upstream = SettingKey<no.skasti.yame.ppp.ip.Ipv4Address>("dns.upstream", Group, { PppDnsConfig(it) })

    override val bindings by lazy { listOf(
        IniBinding(Upstream, "dns.upstream", { PppDnsConfig.DEFAULT_UPSTREAM_SERVER }, no.skasti.yame.ppp.ip.Ipv4Address::parse, { it.toString() }),
    ) }
}
