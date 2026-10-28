package no.skasti.yame.ui

import no.skasti.yame.config.ConfigStore
import no.skasti.yame.ppp.proxy.transform.HtmlTransformer

internal fun registerTransformerCommands(registry: CommandPalette = CommandPalette.global): List<AutoCloseable> {
    val registrations = mutableListOf<AutoCloseable>()
    try {
        HtmlTransformer.entries.forEach { transformer ->
            val key = no.skasti.yame.ppp.proxy.transform.TransformerSettings.key(transformer)
            val path = "/transformers/${transformer.command}"
            registrations += registry.add("$path/on", { if (ConfigStore[key]) "current" else "" }) { ConfigStore.set(key to true) }
            registrations += registry.add("$path/off", { if (!ConfigStore[key]) "current" else "" }) { ConfigStore.set(key to false) }
        }
        return registrations
    } catch (error: Exception) {
        registrations.forEach(AutoCloseable::close)
        throw error
    }
}
