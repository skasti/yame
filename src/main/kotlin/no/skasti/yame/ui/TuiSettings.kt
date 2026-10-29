package no.skasti.yame.ui

import no.skasti.yame.config.SettingGroup
import no.skasti.yame.config.SettingKey
import no.skasti.yame.config.Settings

/** Presentation-owned settings, persisted in the main yame.ini file. */
object TuiSettings : Settings("tui") {
    val Group = SettingGroup(this)
    val SupportedFrameRates = listOf(15, 30, 60, 120)
    val MaxFps = SettingKey<Int>(
        "max-fps",
        Group,
        { require(it in SupportedFrameRates) { "TUI max FPS must be one of ${SupportedFrameRates.joinToString(", ")}" } },
        description = "Maximum terminal redraw rate (15, 30, 60 or 120 FPS). Changes take effect immediately.",
        default = { 60 },
    )
}
