package no.skasti.yame.ui

import no.skasti.yame.config.ConfigStore
import no.skasti.yame.ppp.proxy.ProxySettings

/** HTTP proxy controls are owned by the presentation and released on shutdown. */
internal fun registerProxyCommands(registry: CommandPalette = CommandPalette.global): List<AutoCloseable> {
    val registrations = mutableListOf<AutoCloseable>()
    try {
        val key = ProxySettings.MaxFlows
        listOf(4, 8, 16, 32, 64).forEach { limit ->
            registrations += registry.add(
                "/http-max-flows/$limit",
                { if (ConfigStore[key] == limit) "current" else "" },
            ) { ConfigStore.set(key to limit) }
        }
        return registrations
    } catch (error: Exception) {
        registrations.forEach(AutoCloseable::close)
        throw error
    }
}
