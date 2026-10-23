package no.skasti.yame.config

/** Persistence port for the typed store; feature definitions supply their own mapping. */
interface ConfigurationRepository {
    fun fileIdentity(definition: Settings): String
    fun load(definition: Settings): Pair<List<ConfigChange<*>>, Set<Pair<String, String>>>
    fun persist(changes: Collection<ConfigChange<*>>, snapshot: ConfigChanges)
}
