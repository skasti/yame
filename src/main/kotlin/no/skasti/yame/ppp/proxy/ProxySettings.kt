package no.skasti.yame.ppp.proxy

import no.skasti.yame.config.*

object ProxySettings : Settings("proxy") {
    val Group = SettingGroup(this)
    val Enabled = SettingKey<Boolean>("enabled", Group, default = { true })
}
