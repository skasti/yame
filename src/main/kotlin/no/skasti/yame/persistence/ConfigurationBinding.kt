package no.skasti.yame.persistence

import no.skasti.yame.config.*
/** File-schema adapter; stores remain independent of the application's configuration shape. */
internal class ConfigurationBinding<T>(
    val key: SettingKey<T>,
    val read: (YameConfiguration) -> T,
    val write: (YameConfiguration, T) -> YameConfiguration,
) {
    fun change(configuration: YameConfiguration): ConfigChange<T> = key to read(configuration)
    fun apply(configuration: YameConfiguration, changes: ConfigChanges): YameConfiguration =
        changes[key]?.let { write(configuration, it.value) } ?: configuration
}
