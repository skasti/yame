package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.config.*

object TransformerSettings : Settings("transformers") {
    val Group = SettingGroup(this, description = "HTML transformations for legacy browsers.")
    val Script = SettingKey<Boolean>("script-remover-enabled", Group, description = "Remove script elements from HTML sent to legacy browsers.", default = { true })
    val Style = SettingKey<Boolean>("style-remover-enabled", Group, description = "Remove stylesheets and inline styles from HTML sent to legacy browsers.", default = { true })
    val Attributes = SettingKey<Boolean>("attribute-cleaner-enabled", Group, description = "Remove HTML attributes unsupported by the legacy compatibility profile.", default = { true })
    val Elements = SettingKey<Boolean>("element-simplifier-enabled", Group, description = "Apply configured HTML element replacement rules.", default = { true })
    fun key(transformer: HtmlTransformer): SettingKey<Boolean> = when (transformer) {
        HtmlTransformer.SCRIPT_REMOVER -> Script
        HtmlTransformer.STYLE_REMOVER -> Style
        HtmlTransformer.ATTRIBUTE_CLEANER -> Attributes
        HtmlTransformer.ELEMENT_SIMPLIFIER -> Elements
    }
}
