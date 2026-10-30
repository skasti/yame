package no.skasti.yame.ppp.proxy

import no.skasti.yame.config.*

object ProxySettings : Settings("proxy") {
    val Group = SettingGroup(this)
    val Enabled = SettingKey<Boolean>("enabled", Group, description = "Enable HTTP compatibility handling on TCP port 80, including modern upstream HTTPS access.", default = { true })
    val MaxFlows = SettingKey<Int>(
        "max-flows", Group,
        { require(it in 1..64) { "HTTP flow limit must be between 1 and 64" } },
        description = "Maximum simultaneous HTTP compatibility connections (1-64). Changes take effect immediately.",
        default = { 16 },
    )
}
