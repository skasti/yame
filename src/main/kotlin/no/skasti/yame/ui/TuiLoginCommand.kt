package no.skasti.yame.ui

internal const val LOGIN_ADD_COMMAND = "/login-add"
internal const val LOGIN_ADD_PREFIX = "$LOGIN_ADD_COMMAND "
internal const val LOGIN_ADD_TITLE = "Add terminal login — username:password"
internal const val LOGIN_ADD_INVALID_TITLE = "Use /login-add username:password"

internal data class TuiLoginCredentials(
    val username: String,
    val password: String,
) {
    override fun toString(): String =
        "TuiLoginCredentials(username=$username, password=<redacted>)"
}

internal fun parseLoginAddInput(input: String): TuiLoginCredentials? {
    if (!input.startsWith(LOGIN_ADD_PREFIX, ignoreCase = true)) return null

    val credentials = input.substring(LOGIN_ADD_PREFIX.length)
    val separator = credentials.indexOf(':')
    if (separator <= 0 || separator == credentials.lastIndex) return null

    val username = credentials.substring(0, separator)
    val password = credentials.substring(separator + 1)
    if (
        username.isBlank() ||
        password.isEmpty() ||
        !username.all { it.code in 0x20..0x7e } ||
        !password.all { it.code in 0x20..0x7e }
    ) {
        return null
    }

    return TuiLoginCredentials(username, password)
}

internal fun maskLoginAddPassword(input: String): String {
    if (!input.startsWith(LOGIN_ADD_PREFIX, ignoreCase = true)) return input

    val separator = input.indexOf(':', startIndex = LOGIN_ADD_PREFIX.length)
    if (separator < 0) return input

    val passwordLength = input.length - separator - 1
    return input.take(separator + 1) + "•".repeat(passwordLength)
}
