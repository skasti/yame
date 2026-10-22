package no.skasti.yame.config

object IniValues {
    fun boolean(value: String): Boolean = when (value.lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("expected true or false")
    }
    fun positiveInt(value: String): Int = value.toIntOrNull()?.takeIf { it > 0 }
        ?: throw IllegalArgumentException("expected a positive integer")
}
