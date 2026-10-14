package no.skasti.yame.ppp.proxy

import no.skasti.yame.config.ConfigurationBinding
import no.skasti.yame.config.SettingGroup
import no.skasti.yame.config.SettingKey

object ProxySettings {
    val Group = SettingGroup("ppp.proxy")
    val Enabled = SettingKey<Boolean>("http-https-proxy.enabled", Group)
    internal val bindings: List<ConfigurationBinding<*>> = listOf(
        ConfigurationBinding(Enabled,
            { c -> c.modemConfig.pppHttpCompatibilityConfig.enabled },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(pppHttpCompatibilityConfig = c.modemConfig.pppHttpCompatibilityConfig.copy(enabled = v))) }),
    )
}
