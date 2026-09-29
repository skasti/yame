package no.skasti.yame.ui

import no.skasti.yame.modem.HayesModemConfig

internal const val TUI_LOGIN_COMMAND = "/login"
internal const val TUI_LOGIN_PREFIX = "$TUI_LOGIN_COMMAND "
internal const val TUI_LOGIN_TITLE = "Terminal login — username:password"
internal const val TUI_LOGIN_INVALID_TITLE = "Use /login username:password"

internal data class TuiLoginCredentials(
    val username: String,
    val password: String,
) {
    override fun toString(): String =
        "TuiLoginCredentials(username=$username, password=<redacted>)"
}

internal fun parseLoginInput(input: String): TuiLoginCredentials? {
    if (!input.startsWith(TUI_LOGIN_PREFIX, ignoreCase = true)) return null

    val credentials = input.substring(TUI_LOGIN_PREFIX.length)
    val separator = credentials.indexOf(':')
    if (separator <= 0 || separator == credentials.lastIndex) return null

    val username = credentials.substring(0, separator)
    val password = credentials.substring(separator + 1)
    if (
        username.isBlank() ||
        password.isEmpty() ||
        username.length > HayesModemConfig.MAX_LOGIN_USERNAME_LENGTH ||
        password.length > HayesModemConfig.MAX_LOGIN_PASSWORD_LENGTH ||
        !username.all { it.code in 0x20..0x7e } ||
        !password.all { it.code in 0x20..0x7e }
    ) {
        return null
    }

    return TuiLoginCredentials(username, password)
}

internal fun loginInputWithinLimits(input: String): Boolean {
    if (!input.startsWith(TUI_LOGIN_PREFIX, ignoreCase = true)) return false

    val credentials = input.substring(TUI_LOGIN_PREFIX.length)
    val separator = credentials.indexOf(':')
    val usernameLength = if (separator < 0) credentials.length else separator
    val passwordLength = if (separator < 0) 0 else credentials.length - separator - 1

    return usernameLength <= HayesModemConfig.MAX_LOGIN_USERNAME_LENGTH &&
        passwordLength <= HayesModemConfig.MAX_LOGIN_PASSWORD_LENGTH
}

internal fun maskLoginPassword(input: String): String {
    if (!input.startsWith(TUI_LOGIN_PREFIX, ignoreCase = true)) return input

    val separator = input.indexOf(':', startIndex = TUI_LOGIN_PREFIX.length)
    if (separator < 0) return input

    val passwordLength = input.length - separator - 1
    return input.take(separator + 1) + "•".repeat(passwordLength)
}
