package no.skasti.yame.config

import no.skasti.yame.serial.SerialSettings
import no.skasti.yame.modem.ModemSettings
import no.skasti.yame.ppp.PppSettings
import no.skasti.yame.ppp.dns.DnsSettings
import no.skasti.yame.ppp.proxy.ProxySettings
import no.skasti.yame.ppp.proxy.transform.TransformerSettings
import no.skasti.yame.logging.LogSettings

/** Composition root: assembles feature-owned bindings, preserving yame.ini compatibility. */
fun applicationConfigStore(
    saved: YameConfiguration,
    effective: YameConfiguration = saved,
    save: (YameConfiguration) -> Unit = {},
    onSubscriberFailure: (Throwable) -> Unit = { System.err.println("Configuration subscriber failed: ${it.javaClass.simpleName}") },
): ConfigStore {
    val bindings = applicationBindings
    fun configuration(changes: ConfigChanges): YameConfiguration =
        bindings.fold(saved) { current, binding -> binding.apply(current, changes) }
    return ConfigStore(
        persistent = bindings.map { it.change(saved) },
        overrides = bindings.map { it.change(effective) },
        persist = { save(configuration(it)) },
        validateSnapshot = { configuration(it) },
        onSubscriberFailure = onSubscriberFailure,
    )
}

internal val applicationBindings = SerialSettings.bindings + ModemSettings.bindings +
    PppSettings.bindings + DnsSettings.bindings + ProxySettings.bindings +
    TransformerSettings.bindings + LogSettings.bindings

internal fun applyConfigurationChanges(configuration: YameConfiguration, changes: ConfigChanges): YameConfiguration =
    applicationBindings.fold(configuration) { current, binding -> binding.apply(current, changes) }
