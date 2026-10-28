package no.skasti.yame.logging

import no.skasti.yame.config.*

object LogSettings : Settings("logging") {
    val DEFAULT_LEVEL = YameLogLevel.INFO
    val Group = SettingGroup(this)
    val Levels = YameLogModule.entries.associateWith {
        SettingKey<YameLogLevel>("level-${it.fileName}", Group, description = "Minimum severity written to the ${it.fileName} log: ERROR, WARN, INFO or DEBUG.", default = { DEFAULT_LEVEL })
    }
    val CaptureLimits = PacketCaptureLimitKind.entries.associateWith {
        SettingKey<Int>(it.configKey.substringAfterLast('.'), Group,
            { value -> require(value > 0) }, description = "Maximum retained packet captures ${if (it == PacketCaptureLimitKind.PER_TYPE) "per packet type" else "across all packet types"}. Must be positive.", default = { it.value(PacketCaptureLimits()) })
    }
}
