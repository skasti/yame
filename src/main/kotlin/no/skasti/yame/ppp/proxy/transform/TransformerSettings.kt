package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.config.SettingGroup
import no.skasti.yame.config.SettingKey

object TransformerSettings {
    val Group = SettingGroup("ppp.proxy.transform")
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
    val Rules = SettingKey<ElementSimplifierConfiguration>("html-transformers.element-rules", Group)
}
