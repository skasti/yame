package no.skasti.yame.ppp

import no.skasti.yame.config.*

object PppSettings : Settings("ppp") {
    val Group = SettingGroup("ppp", this, "")
    val Subnet = SettingKey<no.skasti.yame.ppp.ip.Ipv4Cidr?>("ppp.subnet", Group, { PppIpConfig(it) })

    override val bindings by lazy { listOf(
        IniBinding(Subnet, "ppp.subnet", { null }, { it.takeIf(String::isNotBlank)?.let(no.skasti.yame.ppp.ip.Ipv4Cidr::parse) }, { it?.toString() }),
    ) }
}
