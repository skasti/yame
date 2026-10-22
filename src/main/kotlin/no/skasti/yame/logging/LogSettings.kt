package no.skasti.yame.logging

import no.skasti.yame.config.*

object LogSettings : ConfigBase("logging") {
    val DEFAULT_LEVEL = YameLogLevel.INFO
    val Group = SettingGroup("logging", this, "")
    val Levels = YameLogModule.entries.associateWith {
        SettingKey<YameLogLevel>("loglevel.${it.fileName}", Group)
    }
    val CaptureLimits = PacketCaptureLimitKind.entries.associateWith {
        SettingKey<Int>(it.configKey, Group, { value -> require(value > 0) })
    }

    override val bindings: List<IniBinding<*>> by lazy {
        Levels.map { (module, key) -> IniBinding(key, "loglevel.${module.fileName}", { DEFAULT_LEVEL },
            YameLogLevel::parse, { it.name.lowercase() }) } +
        CaptureLimits.map { (kind, key) -> IniBinding(key, kind.configKey, { kind.value(PacketCaptureLimits()) },
            kind::parse, Int::toString) }
    }
}
