package no.skasti.yame.ppp

import no.skasti.yame.config.*

object PppSettings : Settings("ppp") {
    val Group = SettingGroup(this)
    val Subnet = SettingKey<no.skasti.yame.ppp.ip.Ipv4Cidr?>("subnet", Group, { PppIpConfig(it) }, default = { null })
}
