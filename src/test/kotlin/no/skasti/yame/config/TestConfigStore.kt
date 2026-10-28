package no.skasti.yame.config

/** Isolated lazy defaults, with an optional persistence probe. No feature inventory. */
fun testConfigStore(
    vararg initial: ConfigChange<*>,
    save: (ConfigChanges) -> Unit = {},
    onSubscriberFailure: (Throwable) -> Unit = { throw it },
): ConfigStore {
    val baseline = initial.associateBy { it.key }
    require(baseline.size == initial.size)
    val store = ConfigStore(emptyList(), repository = object : ConfigurationRepository {
        override fun load(definition: Settings) = ConfigurationLoad(
            definition.allSettingKeys.map { key -> baseline[key] ?: defaultChange(key) },
        )
        override fun persist(changes: Collection<ConfigChange<*>>, snapshot: ConfigChanges) = save(snapshot)
    }, onSubscriberFailure = onSubscriberFailure)
    initial.forEach { it.key.group.configuration?.let(store::ensureLoaded) }
    return store
}

private fun <T> defaultChange(key: SettingKey<T>): ConfigChange<T> = key to key.default()
