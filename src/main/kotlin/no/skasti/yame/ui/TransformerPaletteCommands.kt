package no.skasti.yame.ui

import no.skasti.yame.config.HtmlTransformer

internal fun registerTransformerCommands(store: no.skasti.yame.config.ConfigStore, registry: CommandPalette = CommandPalette.global): List<AutoCloseable> {
    val registrations = mutableListOf<AutoCloseable>()
    try {
        HtmlTransformer.entries.forEach { transformer ->
            val key = no.skasti.yame.ppp.proxy.transform.TransformerSettings.key(transformer)
            val path = "/transformers/${transformer.command}"
            registrations += registry.add("$path/on", { if (store[key]) "current" else "" }) { store.set(key to true) }
            registrations += registry.add("$path/off", { if (!store[key]) "current" else "" }) { store.set(key to false) }
        }
        return registrations
    } catch (error: Exception) {
        registrations.forEach(AutoCloseable::close)
        throw error
    }
}
