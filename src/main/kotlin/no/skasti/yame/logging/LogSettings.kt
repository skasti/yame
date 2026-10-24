package no.skasti.yame.logging

import no.skasti.yame.config.*

object LogSettings : Settings("logging") {
    val DEFAULT_LEVEL = YameLogLevel.INFO
    val Group = SettingGroup(this)
    val Levels = YameLogModule.entries.associateWith {
        SettingKey<YameLogLevel>("level-${it.fileName}", Group, default = { DEFAULT_LEVEL })
    }
    val CaptureLimits = PacketCaptureLimitKind.entries.associateWith {
        SettingKey<Int>(it.configKey.substringAfterLast('.'), Group,
            { value -> require(value > 0) }, default = { it.value(PacketCaptureLimits()) })
    }
}
