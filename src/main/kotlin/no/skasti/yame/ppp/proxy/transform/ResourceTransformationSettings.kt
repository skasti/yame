package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.config.ConfigStore

/** Immutable transformation policy captured once at the beginning of each HTTP request. */
data class ResourceTransformationSettings(
    val htmlTransformers: HtmlTransformerSettings = HtmlTransformerSettings(),
    val pngCompatibilityMode: PngCompatibilityMode = PngCompatibilityMode.OPTIMIZE,
) {
    companion object {
        fun fromConfigStore(store: ConfigStore = ConfigStore.current): ResourceTransformationSettings =
            ResourceTransformationSettings(
                htmlTransformers = HtmlTransformerSettings.fromConfigStore(store),
                pngCompatibilityMode = PngCompatibilityMode.parse(store[TransformerSettings.PngCompatibility]),
            )
    }

    val profileToken: String
        get() = "html=${htmlTransformers.profileToken}:png=${pngCompatibilityMode.configValue}"
}
