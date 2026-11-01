package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.config.ConfigStore

enum class HtmlTransformer(val command: String) {
    SCRIPT_REMOVER("script-remover"),
    STYLE_REMOVER("style-remover"),
    ATTRIBUTE_CLEANER("attribute-cleaner"),
    ELEMENT_SIMPLIFIER("element-simplifier"),
    READABILITY("readability"),
}

data class HtmlTransformerSettings(
    val scriptRemover: Boolean = true,
    val styleRemover: Boolean = true,
    val attributeCleaner: Boolean = true,
    val elementSimplifier: Boolean = true,
    val readability: Boolean = false,
) {
    companion object {
        fun fromConfigStore(): HtmlTransformerSettings =
            HtmlTransformerSettings(
                ConfigStore[TransformerSettings.Script],
                ConfigStore[TransformerSettings.Style],
                ConfigStore[TransformerSettings.Attributes],
                ConfigStore[TransformerSettings.Elements] && ConfigStore[ElementSimplifierSettings.Enabled],
                ConfigStore[TransformerSettings.Readability],
            )
    }

    fun enabled(transformer: HtmlTransformer): Boolean = when (transformer) {
        HtmlTransformer.SCRIPT_REMOVER -> scriptRemover
        HtmlTransformer.STYLE_REMOVER -> styleRemover
        HtmlTransformer.ATTRIBUTE_CLEANER -> attributeCleaner
        HtmlTransformer.ELEMENT_SIMPLIFIER -> elementSimplifier
        HtmlTransformer.READABILITY -> readability
    }

    fun withEnabled(transformer: HtmlTransformer, enabled: Boolean): HtmlTransformerSettings = when (transformer) {
        HtmlTransformer.SCRIPT_REMOVER -> copy(scriptRemover = enabled)
        HtmlTransformer.STYLE_REMOVER -> copy(styleRemover = enabled)
        HtmlTransformer.ATTRIBUTE_CLEANER -> copy(attributeCleaner = enabled)
        HtmlTransformer.ELEMENT_SIMPLIFIER -> copy(elementSimplifier = enabled)
        HtmlTransformer.READABILITY -> copy(readability = enabled)
    }

    val profileToken: String get() = HtmlTransformer.entries.joinToString("") { if (enabled(it)) "1" else "0" }
}
