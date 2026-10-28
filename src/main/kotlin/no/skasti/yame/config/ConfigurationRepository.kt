package no.skasti.yame.config

/** Persistence port for the typed store; feature definitions supply their own mapping. */
interface ConfigurationRepository {
    /** Create a missing feature file without replacing an existing user file. */
    fun createFileIfMissing(definition: Settings)
    fun load(definition: Settings): ConfigurationLoad
    fun persist(changes: Collection<ConfigChange<*>>, snapshot: ConfigChanges)
}

/** Installation runs only after ConfigStore has validated the complete definition. */
class ConfigurationLoad(
    val entries: List<ConfigChange<*>>,
    val install: () -> Unit = {},
)
