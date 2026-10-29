package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.config.ConfigStore

/** Immutable transformation policy captured once at the beginning of each HTTP request. */
data class ResourceTransformationSettings(
    val htmlTransformers: HtmlTransformerSettings = HtmlTransformerSettings(),
    val pngCompatibilityMode: PngCompatibilityMode = PngCompatibilityMode.OPTIMIZE,
) {
    companion object {
        fun fromConfigStore(): ResourceTransformationSettings =
            ResourceTransformationSettings(
                htmlTransformers = HtmlTransformerSettings.fromConfigStore(),
                pngCompatibilityMode = PngCompatibilityMode.parse(ConfigStore[TransformerSettings.PngCompatibility]),
            )
    }

    val profileToken: String
        get() = "html=${htmlTransformers.profileToken}:png=${pngCompatibilityMode.configValue}"
}
