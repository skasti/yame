package no.skasti.yame.logging

import no.skasti.yame.config.SettingGroup
import no.skasti.yame.config.SettingKey

object LogSettings {
    val Group = SettingGroup("logging")
    val Levels = YameLogModule.entries.associateWith {
        SettingKey<YameLogLevel>("loglevel.${it.fileName}", Group)
    }
    val CaptureLimits = PacketCaptureLimitKind.entries.associateWith {
        SettingKey<Int>(it.configKey, Group, { value -> require(value > 0) })
    }
}
