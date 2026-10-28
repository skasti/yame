package no.skasti.yame.logging

import no.skasti.yame.config.ConfigStore
import java.util.Collections

/** Immutable initial logging values; runtime subscriptions remain owned by the runtime. */
class LoggingConfig(
    levels: Map<YameLogModule, YameLogLevel> = LogSettings.Levels.mapValues { (_, key) -> key.default() },
    val captureLimits: PacketCaptureLimits = PacketCaptureLimits(),
) {
    val levels: Map<YameLogModule, YameLogLevel> = Collections.unmodifiableMap(LinkedHashMap(levels))
    companion object {
        fun fromConfigStore(store: ConfigStore = ConfigStore.current) = LoggingConfig(
            LogSettings.Levels.mapValues { (_, key) -> store[key] },
            PacketCaptureLimits(
                store[LogSettings.CaptureLimits.getValue(PacketCaptureLimitKind.PER_TYPE)],
                store[LogSettings.CaptureLimits.getValue(PacketCaptureLimitKind.TOTAL)],
            ),
        )
    }
}
