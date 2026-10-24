package no.skasti.yame.persistence

import no.skasti.yame.config.*
import no.skasti.yame.ppp.proxy.transform.ElementSimplifierSettings
import no.skasti.yame.ppp.proxy.transform.TransformerSettings

/** Composition root: assembles the application persistence bindings, preserving yame.ini compatibility. */
fun applicationConfigStore(
    saved: YameConfiguration,
    effective: YameConfiguration = saved,
    save: (YameConfiguration) -> Unit = {},
    onSubscriberFailure: (Throwable) -> Unit = { System.err.println("Configuration subscriber failed: ${it.javaClass.simpleName}") },
): ConfigStore {
    val bindings = YameConfigurationBindings.all
    // The legacy DTO has no separate-file switch. Supply its feature default for
    // callers using this adapter; only the INI-backed store can persist changes to it.
    val ruleFileSwitch = ElementSimplifierSettings.Enabled to ElementSimplifierSettings.Enabled.default()
    fun configuration(changes: ConfigChanges): YameConfiguration =
        bindings.fold(saved) { current, binding -> binding.apply(current, changes) }
    return ConfigStore(
        persistent = bindings.map { it.change(saved) } + ruleFileSwitch,
        overrides = bindings.map { it.change(effective) } + ruleFileSwitch,
        persist = { save(configuration(it)) },
        validateSnapshot = {
            require(it[ElementSimplifierSettings.Enabled]?.value == ruleFileSwitch.value) {
                "The legacy configuration adapter cannot persist the separate element-simplifier enabled setting"
            }
            require(it[ElementSimplifierSettings.Rules]?.value == saved.pppHttpCompatibilityConfig.elementSimplifier) {
                "Element-simplifier rules are startup configuration; edit element-simplifier.ini and restart"
            }
            configuration(it)
        },
        onSubscriberFailure = onSubscriberFailure,
    )
}
