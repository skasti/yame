package no.skasti.yame.ppp

import no.skasti.yame.config.*

object PppSettings : Settings("ppp") {
    val Group = SettingGroup(this)
    val DisconnectOnTerminate = SettingKey<Boolean>("disconnect-on-terminate", Group, description = "Drop modem carrier after acknowledging a peer PPP termination request.", default = { true })
    val Subnet = SettingKey<no.skasti.yame.ppp.ip.Ipv4Cidr?>("subnet", Group, { PppIpConfig(it) }, description = "IPv4 CIDR pool for the local and peer PPP addresses. Omit for automatic allocation.", default = { null })
}
