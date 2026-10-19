package no.skasti.yame.ppp.dns

import no.skasti.yame.config.SettingGroup
import no.skasti.yame.config.SettingKey

object DnsSettings {
    val Group = SettingGroup("ppp.dns")
    val Upstream = SettingKey<no.skasti.yame.ppp.ip.Ipv4Address>("dns.upstream", Group, { PppDnsConfig(it) })
}
