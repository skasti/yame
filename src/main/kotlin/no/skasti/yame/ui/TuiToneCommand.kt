package no.skasti.yame.ui

internal const val TUI_TONE_COMMAND = "/tone"
internal const val TUI_TONE_INVALID_TITLE = "Use /tone on or /tone off"

internal fun parseToneInput(input: String): Boolean? = when (input.trim().lowercase()) {
    "$TUI_TONE_COMMAND on" -> true
    "$TUI_TONE_COMMAND off" -> false
    else -> null
}
