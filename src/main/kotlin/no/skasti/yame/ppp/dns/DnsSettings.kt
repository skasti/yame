package no.skasti.yame.ppp.dns

import no.skasti.yame.config.*

object DnsSettings : Settings("dns") {
    val Group = SettingGroup(this)
    val Upstream = SettingKey<no.skasti.yame.ppp.ip.Ipv4Address>("upstream", Group, { PppDnsConfig(it) },
        description = "Upstream IPv4 DNS server used to resolve queries from the PPP client.", default = { PppDnsConfig.DEFAULT_UPSTREAM_SERVER })
}
