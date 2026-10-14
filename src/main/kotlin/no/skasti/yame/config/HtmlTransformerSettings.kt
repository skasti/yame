package no.skasti.yame.config


enum class HtmlTransformer(val command: String) {
    SCRIPT_REMOVER("script-remover"),
    STYLE_REMOVER("style-remover"),
    ATTRIBUTE_CLEANER("attribute-cleaner"),
    ELEMENT_SIMPLIFIER("element-simplifier"),
}

data class HtmlTransformerSettings(
    val scriptRemover: Boolean = true,
    val styleRemover: Boolean = true,
    val attributeCleaner: Boolean = true,
    val elementSimplifier: Boolean = true,
) {
    fun enabled(transformer: HtmlTransformer): Boolean = when (transformer) {
        HtmlTransformer.SCRIPT_REMOVER -> scriptRemover
        HtmlTransformer.STYLE_REMOVER -> styleRemover
        HtmlTransformer.ATTRIBUTE_CLEANER -> attributeCleaner
        HtmlTransformer.ELEMENT_SIMPLIFIER -> elementSimplifier
    }

    fun withEnabled(transformer: HtmlTransformer, enabled: Boolean): HtmlTransformerSettings = when (transformer) {
        HtmlTransformer.SCRIPT_REMOVER -> copy(scriptRemover = enabled)
        HtmlTransformer.STYLE_REMOVER -> copy(styleRemover = enabled)
        HtmlTransformer.ATTRIBUTE_CLEANER -> copy(attributeCleaner = enabled)
        HtmlTransformer.ELEMENT_SIMPLIFIER -> copy(elementSimplifier = enabled)
    }

    val profileToken: String get() = HtmlTransformer.entries.joinToString("") { if (enabled(it)) "1" else "0" }
}

/** Read-through request snapshot adapter; authoritative state lives exclusively in ConfigStore. */
class HtmlTransformerControl(
    initial: HtmlTransformerSettings,
    val configStore: ConfigStore = applicationConfigStore(YameConfiguration(
        modemConfig = no.skasti.yame.modem.HayesModemConfig(
            pppHttpCompatibilityConfig = no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig(htmlTransformers = initial),
        ),
    )),
) : AutoCloseable {
    fun snapshot(): HtmlTransformerSettings {
        val changes = configStore.snapshot()
        val keys = no.skasti.yame.ppp.proxy.transform.TransformerSettings
        return HtmlTransformerSettings(
            scriptRemover = requireNotNull(changes[keys.Script]).value,
            styleRemover = requireNotNull(changes[keys.Style]).value,
            attributeCleaner = requireNotNull(changes[keys.Attributes]).value,
            elementSimplifier = requireNotNull(changes[keys.Elements]).value,
        )
    }
    fun setEnabled(transformer: HtmlTransformer, enabled: Boolean) {
        configStore.set(no.skasti.yame.ppp.proxy.transform.TransformerSettings.key(transformer) to enabled)
    }
    override fun close() {}
}
