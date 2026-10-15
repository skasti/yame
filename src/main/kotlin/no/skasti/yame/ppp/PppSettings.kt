package no.skasti.yame.ppp

import no.skasti.yame.config.SettingGroup
import no.skasti.yame.config.SettingKey

object PppSettings {
    val Group = SettingGroup("ppp")
    val Subnet = SettingKey<no.skasti.yame.ppp.ip.Ipv4Cidr?>("ppp.subnet", Group, { PppIpConfig(it) })
}
