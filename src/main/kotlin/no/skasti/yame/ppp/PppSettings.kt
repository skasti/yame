package no.skasti.yame.ppp

import no.skasti.yame.config.*

object PppSettings : Settings("ppp") {
    val Group = SettingGroup(this)
    val Subnet = SettingKey<no.skasti.yame.ppp.ip.Ipv4Cidr?>("subnet", Group, { PppIpConfig(it) }, description = "IPv4 CIDR pool for the local and peer PPP addresses. Omit for automatic allocation.", default = { null })
}
