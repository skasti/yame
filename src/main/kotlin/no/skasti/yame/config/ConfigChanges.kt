package no.skasti.yame.config

class ConfigChanges internal constructor(changes: Iterable<ConfigChange<*>>) : Iterable<ConfigChange<*>> {
    private val entries = changes.associateByTo(linkedMapOf()) { it.key }
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: SettingKey<T>): ConfigChange<T>? = entries[key] as ConfigChange<T>?
    override fun iterator(): Iterator<ConfigChange<*>> = entries.values.toList().iterator()
    val isEmpty: Boolean get() = entries.isEmpty()
}

