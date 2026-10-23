package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.config.*

object TransformerSettings : Settings("transformers") {
    val Group = SettingGroup("ppp.proxy.transform", this, "")
    val Script = SettingKey<Boolean>("transformers.script-remover.enabled", Group)
    val Style = SettingKey<Boolean>("transformers.style-remover.enabled", Group)
    val Attributes = SettingKey<Boolean>("transformers.attribute-cleaner.enabled", Group)
    val Elements = SettingKey<Boolean>("transformers.element-simplifier.enabled", Group)
    fun key(transformer: HtmlTransformer): SettingKey<Boolean> = when (transformer) {
        HtmlTransformer.SCRIPT_REMOVER -> Script
        HtmlTransformer.STYLE_REMOVER -> Style
        HtmlTransformer.ATTRIBUTE_CLEANER -> Attributes
        HtmlTransformer.ELEMENT_SIMPLIFIER -> Elements
    }
    val Rules get() = ElementSimplifierSettings.Rules

    override val bindings by lazy { listOf(
        IniBinding(Script, "transformers.script-remover.enabled", { true }, IniValues::boolean, Boolean::toString),
        IniBinding(Style, "transformers.style-remover.enabled", { true }, IniValues::boolean, Boolean::toString),
        IniBinding(Attributes, "transformers.attribute-cleaner.enabled", { true }, IniValues::boolean, Boolean::toString),
        IniBinding(Elements, "transformers.element-simplifier.enabled", { true }, IniValues::boolean, Boolean::toString),
    ) }
}
