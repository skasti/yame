package no.skasti.yame.ppp.proxy

import no.skasti.yame.config.*

object ProxySettings : Settings("proxy") {
    val Group = SettingGroup(this, description = "HTTP compatibility proxy.")
    val Enabled = SettingKey<Boolean>("enabled", Group, description = "Enable HTTP compatibility handling on TCP port 80, including modern upstream HTTPS access.", default = { true })
}
