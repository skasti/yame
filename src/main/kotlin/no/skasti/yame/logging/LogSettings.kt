package no.skasti.yame.logging

import no.skasti.yame.config.ConfigurationBinding
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
    internal val bindings: List<ConfigurationBinding<*>> = Levels.map { (module, key) ->
        ConfigurationBinding(key,
            { c -> c.logLevels.getValue(module) },
            { c, v -> c.copy(logLevels = c.logLevels + (module to v)) },
        )
    } + CaptureLimits.map { (kind, key) ->
        ConfigurationBinding(key,
            { c -> kind.value(c.captureLimits) },
            { c, v -> c.copy(captureLimits = kind.apply(c.captureLimits, v)) },
        )
    }
}
