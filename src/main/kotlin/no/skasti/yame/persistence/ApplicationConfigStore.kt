package no.skasti.yame.persistence

import no.skasti.yame.config.*
import no.skasti.yame.ppp.proxy.transform.TransformerSettings

/** Composition root: assembles the application persistence bindings, preserving yame.ini compatibility. */
fun applicationConfigStore(
    saved: YameConfiguration,
    effective: YameConfiguration = saved,
    save: (YameConfiguration) -> Unit = {},
    onSubscriberFailure: (Throwable) -> Unit = { System.err.println("Configuration subscriber failed: ${it.javaClass.simpleName}") },
): ConfigStore {
    val bindings = YameConfigurationBindings.all
    fun configuration(changes: ConfigChanges): YameConfiguration =
        bindings.fold(saved) { current, binding -> binding.apply(current, changes) }
    return ConfigStore(
        persistent = bindings.map { it.change(saved) },
        overrides = bindings.map { it.change(effective) },
        persist = { save(configuration(it)) },
        validateSnapshot = {
            require(it[TransformerSettings.Rules]?.value == saved.pppHttpCompatibilityConfig.elementSimplifier) {
                "Element-simplifier rules are startup configuration; edit element-simplifier.ini and restart"
            }
            configuration(it)
        },
        onSubscriberFailure = onSubscriberFailure,
    )
}
