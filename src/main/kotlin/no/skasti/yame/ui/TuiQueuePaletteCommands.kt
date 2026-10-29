package no.skasti.yame.ui

import no.skasti.yame.config.ConfigStore
import no.skasti.yame.config.SettingKey
import no.skasti.yame.observer.EventBrokerSettings

/** Presentation-only choices; each queue owns its setting reads and subscriptions. */
internal fun registerQueueCapacityCommands(registry: CommandPalette = CommandPalette.global): List<AutoCloseable> {
    val registrations = mutableListOf<AutoCloseable>()
    fun addChoices(path: String, key: SettingKey<Int>, values: List<Int>) {
        values.forEach { value ->
            registrations += registry.add("$path/$value", { if (ConfigStore[key] == value) "current" else "" }) {
                ConfigStore.set(key to value)
            }
        }
    }
    try {
        addChoices("/queues/event-broker", EventBrokerSettings.QueueCapacity,
            listOf(256, 512, 1024, 2048, 4096, 8192))
        addChoices("/queues/tui", TuiSettings.QueueCapacity,
            listOf(64, 128, 256, 512, 1024, 2048))
        return registrations
    } catch (error: Exception) {
        registrations.forEach(AutoCloseable::close)
        throw error
    }
}
