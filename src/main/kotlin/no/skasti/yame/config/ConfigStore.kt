package no.skasti.yame.config

/** One process-owned instance, shared by adapters and components; no presentation dependencies. */
class ConfigStore(
    persistent: List<ConfigChange<*>> = emptyList(),
    overrides: List<ConfigChange<*>> = emptyList(),
    private val persist: (ConfigChanges) -> Unit = {},
    private val repository: ConfigurationRepository? = null,
    private val validateSnapshot: (ConfigChanges) -> Unit = {},
    private val onSubscriberFailure: (Throwable) -> Unit = { System.err.println("Configuration subscriber failed: ${it.javaClass.simpleName}") },
) {
    companion object {
        /** Installed explicitly by the application before using process-default factories. */
        @Volatile private var installed: ConfigStore? = null
        val current: ConfigStore get() = checkNotNull(installed) { "ConfigStore has not been installed" }

        /** Reject a second process owner; closing the handle releases only this installation. */
        @Synchronized
        fun install(store: ConfigStore): AutoCloseable {
            check(installed == null) { "ConfigStore is already installed" }
            installed = store
            var active = true
            return AutoCloseable { synchronized(this) {
                if (active) { installed = null; active = false }
            } }
        }
    }

    private val lock = Any()
    private var saved = persistent.associateByTo(linkedMapOf()) { it.key }
    private val loaded = linkedMapOf<String, Settings>()
    private var effective = LinkedHashMap(saved)
    private val listeners = mutableListOf<Listener>()
    private val pending = ArrayDeque<Delivery>()
    private var dispatching = false
    private class Listener(val group: SettingGroup?, val handler: (ConfigChanges) -> Unit) {
        @Volatile var active = true
    }
    private class Delivery(val changes: ConfigChanges, val listeners: List<Listener>)

    init {
        require(saved.size == persistent.size && persistent.map { it.key.path }.distinct().size == persistent.size) {
            "Duplicate setting definition"
        }
        persistent.forEach { it.validate() }
        overrides.forEach { require(it.key in saved); it.validate(); effective[it.key] = it }
        validateSnapshot(ConfigChanges(saved.values))
        validateSnapshot(ConfigChanges(effective.values))
    }

    /** A failed load leaves the store untouched; a later request retries from disk. */
    fun ensureLoaded(definition: Settings) {
        synchronized(lock) {
            loaded[definition.id]?.let {
                require(it === definition) { "Conflicting definition identity ${definition.id}" }
                return
            }
            val loadedDefinition = repository?.load(definition) ?: ConfigurationLoad(
                definition.allSettingKeys.map { key -> saved[key] ?: defaultChange(key) },
            )
            val entries = loadedDefinition.entries
            require(entries.map { it.key.path }.distinct().size == entries.size) { "Duplicate setting path in ${definition.id}" }
            require(entries.map { it.key }.toSet() == definition.allSettingKeys.toSet()) {
                "${definition.id} did not load exactly its declared setting keys"
            }
            entries.forEach { entry ->
                require(entry.key.group.configuration === definition) { "${entry.key.path} belongs to another definition" }
                require(saved.keys.none { it.path == entry.key.path && (repository != null || it !== entry.key) }) { "Duplicate setting path ${entry.key.path}" }
                entry.validate()
            }
            val candidate = LinkedHashMap(saved)
            entries.forEach { candidate.putIfAbsent(it.key, it) }
            validateSnapshot(ConfigChanges(candidate.values))
            loadedDefinition.install()
            saved = candidate
            effective = LinkedHashMap(effective).apply { entries.forEach { putIfAbsent(it.key, it) } }
            loaded[definition.id] = definition
        }
    }

    private fun <T> defaultChange(key: SettingKey<T>): ConfigChange<T> = key to key.default()

    /** Initialize a feature file without loading/registering it or publishing changes. */
    fun createFileIfMissing(definition: Settings) = synchronized(lock) {
        repository?.createFileIfMissing(definition)
        Unit
    }

    /** First-run persistence of requested feature baselines, excluding transient overrides. */
    fun saveLoadedValues() = synchronized(lock) {
        val values = ConfigChanges(saved.values)
        if (repository == null) persist(values) else repository.persist(saved.values, values)
    }

    /** The requested key identifies its owner; no application-wide definition list is needed. */
    operator fun <T> get(key: SettingKey<T>): T = synchronized(lock) {
        if (key !in effective) key.group.configuration?.let(::ensureLoaded)
        requireNotNull(ConfigChanges(listOfNotNull(effective[key]))[key]) { "Unregistered setting ${key.path}" }.value
    }

    /** Save before publishing. A failed save rejects the entire transaction. Duplicate keys are rejected. */
    fun set(vararg changes: ConfigChange<*>) {
        commit(changes, persistent = true)
    }

    /** Runtime discovery/overrides use the same events but never enter the saved baseline. */
    fun setTransient(vararg changes: ConfigChange<*>) {
        commit(changes, persistent = false)
    }

    private fun commit(changes: Array<out ConfigChange<*>>, persistent: Boolean) {
        synchronized(lock) {
            require(changes.map { it.key.path }.distinct().size == changes.size) { "Duplicate setting in batch" }
            changes.forEach { if (it.key !in saved) it.key.group.configuration?.let(::ensureLoaded) }
            changes.forEach { require(it.key in saved) { "Unregistered setting ${it.key.path}" }; it.validate() }
            val nextSaved = LinkedHashMap(saved)
            val nextEffective = LinkedHashMap(effective)
            changes.forEach { if (persistent) nextSaved[it.key] = it; nextEffective[it.key] = it }
            validateSnapshot(ConfigChanges(nextSaved.values))
            validateSnapshot(ConfigChanges(nextEffective.values))
            val durableChanges = persistent && changes.any { saved[it.key]?.value != it.value }
            val actual = changes.filter { effective[it.key]?.value != it.value }
            if (durableChanges) {
                val snapshot = ConfigChanges(nextSaved.values)
                if (repository == null) persist(snapshot) else {
                    repository.persist(changes.filter { saved[it.key]?.value != it.value }, snapshot)
                }
            }
            saved = nextSaved
            effective = nextEffective
            if (actual.isNotEmpty()) pending.addLast(Delivery(ConfigChanges(actual), listeners.toList()))
        }
        drain()
    }

    val onChange = ChangeEvents()
    inner class ChangeEvents {
        operator fun get(group: SettingGroup): GroupEvents = GroupEvents(group)
        fun subscribe(initial: Boolean = false, handler: (ConfigChanges) -> Unit): AutoCloseable =
            subscribe(null, initial, handler)
    }
    inner class GroupEvents internal constructor(private val group: SettingGroup) {
        fun subscribe(initial: Boolean = false, handler: (ConfigChanges) -> Unit): AutoCloseable =
            subscribe(group, initial, handler)
        operator fun plusAssign(handler: (ConfigChanges) -> Unit) { subscribe(handler = handler) }
        operator fun minusAssign(handler: (ConfigChanges) -> Unit) {
            synchronized(lock) {
                listeners.filter { it.group == group && it.handler == handler }.forEach { it.active = false }
                listeners.removeAll { !it.active }
            }
        }
    }

    private fun subscribe(group: SettingGroup?, initial: Boolean, handler: (ConfigChanges) -> Unit): AutoCloseable {
        val listener = Listener(group, handler)
        synchronized(lock) {
            listeners.add(listener)
            if (initial) pending.addLast(Delivery(ConfigChanges(effective.values), listOf(listener)))
        }
        drain()
        return AutoCloseable { synchronized(lock) { listener.active = false; listeners.remove(listener) } }
    }

    /** Serialized, reentrant-safe delivery outside the commit lock. Failures cannot undo a saved commit. */
    private fun drain() {
        synchronized(lock) { if (dispatching) return; dispatching = true }
        while (true) {
            val delivery = synchronized(lock) {
                if (pending.isEmpty()) { dispatching = false; return }
                pending.removeFirst()
            }
            val grouped = delivery.changes.groupBy { it.key.group }.mapValues { ConfigChanges(it.value) }
            delivery.listeners.forEach { listener ->
                val changes = if (listener.group == null) delivery.changes else grouped[listener.group]
                if (listener.active && changes != null && !changes.isEmpty) {
                    try { listener.handler(changes) } catch (error: Throwable) {
                        runCatching { onSubscriberFailure(error) }
                    }
                }
            }
        }
    }
}
