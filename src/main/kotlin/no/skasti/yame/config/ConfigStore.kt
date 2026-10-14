package no.skasti.yame.config

/** One process-owned instance, shared by adapters and components; no presentation dependencies. */
class ConfigStore(
    persistent: List<ConfigChange<*>>,
    overrides: List<ConfigChange<*>> = emptyList(),
    private val persist: (ConfigChanges) -> Unit = {},
    private val validateSnapshot: (ConfigChanges) -> Unit = {},
    private val onSubscriberFailure: (Throwable) -> Unit = { System.err.println("Configuration subscriber failed: ${it.javaClass.simpleName}") },
) {
    private val lock = Any()
    private var saved = persistent.associateByTo(linkedMapOf()) { it.key }
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

    operator fun <T> get(key: SettingKey<T>): T = synchronized(lock) {
        requireNotNull(ConfigChanges(listOfNotNull(effective[key]))[key]) { "Unregistered setting ${key.path}" }.value
    }

    fun snapshot(): ConfigChanges = synchronized(lock) { ConfigChanges(effective.values) }

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
            changes.forEach { require(it.key in saved) { "Unregistered setting ${it.key.path}" }; it.validate() }
            val nextSaved = LinkedHashMap(saved)
            val nextEffective = LinkedHashMap(effective)
            changes.forEach { if (persistent) nextSaved[it.key] = it; nextEffective[it.key] = it }
            validateSnapshot(ConfigChanges(nextSaved.values))
            validateSnapshot(ConfigChanges(nextEffective.values))
            val durableChanges = persistent && changes.any { saved[it.key]?.value != it.value }
            val actual = changes.filter { effective[it.key]?.value != it.value }
            if (durableChanges) persist(ConfigChanges(nextSaved.values))
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
