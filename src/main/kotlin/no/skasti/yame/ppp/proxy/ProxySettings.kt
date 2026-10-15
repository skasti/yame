package no.skasti.yame.ppp.proxy

import no.skasti.yame.config.SettingGroup
import no.skasti.yame.config.SettingKey

object ProxySettings {
    val Group = SettingGroup("ppp.proxy")
    val Enabled = SettingKey<Boolean>("http-https-proxy.enabled", Group)
}
