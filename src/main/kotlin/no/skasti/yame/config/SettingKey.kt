package no.skasti.yame.config

import kotlin.reflect.KType
import kotlin.reflect.typeOf

private val SETTING_NAME_SEGMENT = Regex("[a-z0-9][a-z0-9_-]*")

/** The group ID is both the logical namespace and its canonical INI section. */
data class SettingGroup(val id: String, val configuration: Settings? = null) {
    constructor(configuration: Settings) : this(configuration.id, configuration)

    init {
        require(id.split('.').all { it.matches(SETTING_NAME_SEGMENT) }) {
            "Invalid setting group ID '$id'; use lowercase letters, digits, hyphens, underscores and dots between segments"
        }
    }
}

/** Identity-based typed key; its external path is derived from its owner and relative name. */
class SettingKey<T> @PublishedApi internal constructor(
    val name: String,
    val group: SettingGroup,
    val type: KType,
    private val validateValue: (T) -> Unit,
    val sensitive: Boolean,
    internal val default: () -> T,
    val structural: Boolean,
    val description: String? = null,
) {
    val path: String = "${group.id}.$name"

    init {
        require(name.matches(SETTING_NAME_SEGMENT)) {
            "Invalid setting name '$name'; use lowercase letters, digits, hyphens and underscores"
        }
    }

    internal fun validate(value: T) = validateValue(value)
    infix fun to(value: T): ConfigChange<T> = ConfigChange(this, value)
    override fun toString(): String = path
}

inline fun <reified T> SettingKey(
    name: String,
    group: SettingGroup,
    noinline validateValue: (T) -> Unit = {},
    sensitive: Boolean = false,
    noinline default: () -> T = { error("No default for ${group.id}.$name") },
    structural: Boolean = false,
    description: String? = null,
): SettingKey<T> = SettingKey(name, group, typeOf<T>(), validateValue, sensitive, default, structural, description)

class ConfigChange<T> internal constructor(val key: SettingKey<T>, val value: T) {
    internal fun validate() = key.validate(value)
    override fun toString(): String = "${key.path}=<configured>"
}

