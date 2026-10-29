package no.skasti.yame.ui

import no.skasti.yame.config.SettingGroup
import no.skasti.yame.config.SettingKey
import no.skasti.yame.config.Settings

/** Presentation-owned settings, persisted in the main yame.ini file. */
object TuiSettings : Settings("tui") {
    const val DefaultQueueCapacity = 256
    val Group = SettingGroup(this)
    val SupportedFrameRates = listOf(15, 30, 60, 120)
    val QueueCapacity = SettingKey<Int>(
        "queue-capacity",
        Group,
        { require(it in 1..65_536) { "TUI queue capacity must be between 1 and 65536 slots" } },
        description = "Maximum pending TUI work slots; adjacent resource events can share a slot. Changes take effect immediately without dropping existing work.",
        default = { DefaultQueueCapacity },
    )

    val MaxFps = SettingKey<Int>(
        "max-fps",
        Group,
        { require(it in SupportedFrameRates) { "TUI max FPS must be one of ${SupportedFrameRates.joinToString(", ")}" } },
        description = "Maximum terminal redraw rate (15, 30, 60 or 120 FPS). Changes take effect immediately.",
        default = { 60 },
    )
}
