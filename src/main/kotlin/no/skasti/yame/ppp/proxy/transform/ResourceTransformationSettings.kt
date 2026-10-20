package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.config.ConfigChanges
import no.skasti.yame.config.ConfigStore

data class ResourceTransformationSettings(
    val htmlTransformers: HtmlTransformerSettings = HtmlTransformerSettings(),
    val pngCompatibilityMode: PngCompatibilityMode = PngCompatibilityMode.OPTIMIZE,
) {
    companion object {
        fun fromConfigStore(): ResourceTransformationSettings =
            fromSnapshot(ConfigStore.current.snapshot())

        internal fun fromSnapshot(changes: ConfigChanges): ResourceTransformationSettings =
            ResourceTransformationSettings(
                htmlTransformers = HtmlTransformerSettings.fromSnapshot(changes),
                pngCompatibilityMode = requireNotNull(changes[PngImageSettings.CompatibilityMode]).value,
            )
    }

    val profileToken: String
        get() = "html=${htmlTransformers.profileToken}:png=${pngCompatibilityMode.configValue}"
}
