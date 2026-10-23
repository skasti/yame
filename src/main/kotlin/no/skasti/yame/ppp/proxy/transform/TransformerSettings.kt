package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.config.*

object TransformerSettings : Settings("transformers") {
    val Group = SettingGroup(this)
    val Script = SettingKey<Boolean>("script-remover-enabled", Group, default = { true })
    val Style = SettingKey<Boolean>("style-remover-enabled", Group, default = { true })
    val Attributes = SettingKey<Boolean>("attribute-cleaner-enabled", Group, default = { true })
    val Elements = SettingKey<Boolean>("element-simplifier-enabled", Group, default = { true })
    fun key(transformer: HtmlTransformer): SettingKey<Boolean> = when (transformer) {
        HtmlTransformer.SCRIPT_REMOVER -> Script
        HtmlTransformer.STYLE_REMOVER -> Style
        HtmlTransformer.ATTRIBUTE_CLEANER -> Attributes
        HtmlTransformer.ELEMENT_SIMPLIFIER -> Elements
    }
    val Rules get() = ElementSimplifierSettings.Rules

}
