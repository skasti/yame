package no.skasti.yame.ui

import no.skasti.yame.config.ConfigStore
import no.skasti.yame.ppp.PppSettings

internal fun registerPppCommands(registry: CommandPalette = CommandPalette.global): List<AutoCloseable> {
    val registrations = mutableListOf<AutoCloseable>()
    try {
        val key = PppSettings.DisconnectOnTerminate
        val path = "/ppp-disconnect-on-terminate"
        registrations += registry.add("$path/on", { if (ConfigStore[key]) "current" else "" }) { ConfigStore.set(key to true) }
        registrations += registry.add("$path/off", { if (!ConfigStore[key]) "current" else "" }) { ConfigStore.set(key to false) }
        return registrations
    } catch (error: Exception) {
        registrations.forEach(AutoCloseable::close)
        throw error
    }
}
