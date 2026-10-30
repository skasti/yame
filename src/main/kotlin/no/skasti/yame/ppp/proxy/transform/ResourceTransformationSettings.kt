package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.config.ConfigChanges
import no.skasti.yame.config.ConfigStore

/** Immutable transformation policy held as a runtime snapshot. */
data class ResourceTransformationSettings(
    val htmlTransformers: HtmlTransformerSettings = HtmlTransformerSettings(),
    val pngCompatibilityMode: PngCompatibilityMode = PngCompatibilityMode.OPTIMIZE,
) {
    companion object {
        /** Reads the installed configuration once; production keeps and updates a runtime snapshot. */
        fun fromConfigStore(): ResourceTransformationSettings =
            ResourceTransformationSettings(
                htmlTransformers = HtmlTransformerSettings.fromConfigStore(),
                pngCompatibilityMode = PngCompatibilityMode.parse(ConfigStore[TransformerSettings.PngCompatibility]),
            )
    }

    val profileToken: String
        get() = "html=${htmlTransformers.profileToken}:png=${pngCompatibilityMode.configValue}"
}

/**
 * Mutable source for immutable per-request settings snapshots.
 *
 * YameRuntime updates this from ConfigStore change events. HTTP requests only read [current],
 * so they do not repeatedly query ConfigStore and each request retains one stable value.
 */
internal class ResourceTransformationSettingsState {
    private val lock = Any()

    private var scriptRemoverEnabled = ConfigStore[TransformerSettings.Script]
    private var styleRemoverEnabled = ConfigStore[TransformerSettings.Style]
    private var attributeCleanerEnabled = ConfigStore[TransformerSettings.Attributes]
    private var elementSimplifierSwitchEnabled = ConfigStore[TransformerSettings.Elements]
    private var elementSimplifierRulesEnabled = ConfigStore[ElementSimplifierSettings.Enabled]
    private var pngCompatibilityMode =
        PngCompatibilityMode.parse(ConfigStore[TransformerSettings.PngCompatibility])

    @Volatile
    private var snapshot = createSnapshot()

    val current: ResourceTransformationSettings
        get() = snapshot

    /** Apply one committed change batch and publish one immutable replacement. */
    fun update(changes: ConfigChanges) = synchronized(lock) {
        var changed = false
        changes[TransformerSettings.Script]?.let { scriptRemoverEnabled = it.value; changed = true }
        changes[TransformerSettings.Style]?.let { styleRemoverEnabled = it.value; changed = true }
        changes[TransformerSettings.Attributes]?.let { attributeCleanerEnabled = it.value; changed = true }
        changes[TransformerSettings.Elements]?.let { elementSimplifierSwitchEnabled = it.value; changed = true }
        changes[ElementSimplifierSettings.Enabled]?.let { elementSimplifierRulesEnabled = it.value; changed = true }
        changes[TransformerSettings.PngCompatibility]?.let {
            pngCompatibilityMode = PngCompatibilityMode.parse(it.value)
            changed = true
        }
        if (changed) snapshot = createSnapshot()
    }

    private fun createSnapshot() = ResourceTransformationSettings(
        htmlTransformers = HtmlTransformerSettings(
            scriptRemover = scriptRemoverEnabled,
            styleRemover = styleRemoverEnabled,
            attributeCleaner = attributeCleanerEnabled,
            elementSimplifier = elementSimplifierSwitchEnabled && elementSimplifierRulesEnabled,
        ),
        pngCompatibilityMode = pngCompatibilityMode,
    )
}
