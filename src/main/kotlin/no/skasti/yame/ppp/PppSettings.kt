package no.skasti.yame.ppp

import no.skasti.yame.config.ConfigurationBinding
import no.skasti.yame.config.SettingGroup
import no.skasti.yame.config.SettingKey

object PppSettings {
    val Group = SettingGroup("ppp")
    val Subnet = SettingKey<no.skasti.yame.ppp.ip.Ipv4Cidr?>("ppp.subnet", Group)
    internal val bindings: List<ConfigurationBinding<*>> = listOf(
        ConfigurationBinding(Subnet,
            { c -> c.modemConfig.pppIpConfig.configuredSubnet },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(pppIpConfig = c.modemConfig.pppIpConfig.copy(configuredSubnet = v))) }),
    )
}
