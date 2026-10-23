package no.skasti.yame.config

/** Persistence port for the typed store; feature definitions supply their own mapping. */
interface ConfigurationRepository {
    fun load(definition: Settings): ConfigurationLoad
    fun persist(changes: Collection<ConfigChange<*>>, snapshot: ConfigChanges)
}

/** Installation runs only after ConfigStore has validated the complete definition. */
class ConfigurationLoad(
    val entries: List<ConfigChange<*>>,
    val install: () -> Unit = {},
)
