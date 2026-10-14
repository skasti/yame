package no.skasti.yame.config

/** Identity-based keys prevent unrelated definitions from impersonating a registered path. */
data class SettingGroup(val name: String)

class SettingKey<T>(
    val path: String,
    val group: SettingGroup,
    private val validateValue: (T) -> Unit = {},
    val sensitive: Boolean = false,
) {
    internal fun validate(value: T) = validateValue(value)
    infix fun to(value: T): ConfigChange<T> = ConfigChange(this, value)
    override fun toString(): String = path
}

class ConfigChange<T> internal constructor(val key: SettingKey<T>, val value: T) {
    internal fun validate() = key.validate(value)
    override fun toString(): String = "${key.path}=<configured>"
}

