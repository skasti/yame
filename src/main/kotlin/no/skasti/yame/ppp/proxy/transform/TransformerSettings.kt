package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.config.ConfigurationBinding
import no.skasti.yame.config.SettingGroup
import no.skasti.yame.config.SettingKey

object TransformerSettings {
    val Group = SettingGroup("ppp.proxy.transform")
    val Script = SettingKey<Boolean>("transformers.script-remover.enabled", Group)
    val Style = SettingKey<Boolean>("transformers.style-remover.enabled", Group)
    val Attributes = SettingKey<Boolean>("transformers.attribute-cleaner.enabled", Group)
    val Elements = SettingKey<Boolean>("transformers.element-simplifier.enabled", Group)
    fun key(transformer: no.skasti.yame.config.HtmlTransformer): SettingKey<Boolean> = when (transformer) {
        no.skasti.yame.config.HtmlTransformer.SCRIPT_REMOVER -> Script
        no.skasti.yame.config.HtmlTransformer.STYLE_REMOVER -> Style
        no.skasti.yame.config.HtmlTransformer.ATTRIBUTE_CLEANER -> Attributes
        no.skasti.yame.config.HtmlTransformer.ELEMENT_SIMPLIFIER -> Elements
    }
    val Rules = SettingKey<no.skasti.yame.config.ElementSimplifierConfiguration>("html-transformers.element-rules", Group)
    internal val bindings: List<ConfigurationBinding<*>> = listOf(
        ConfigurationBinding(Rules,
            { c -> c.modemConfig.pppHttpCompatibilityConfig.elementSimplifier },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(pppHttpCompatibilityConfig = c.modemConfig.pppHttpCompatibilityConfig.copy(elementSimplifier = v))) }),
        ConfigurationBinding(Script,
            { c -> c.modemConfig.pppHttpCompatibilityConfig.htmlTransformers.scriptRemover },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(pppHttpCompatibilityConfig = c.modemConfig.pppHttpCompatibilityConfig.copy(htmlTransformers = c.modemConfig.pppHttpCompatibilityConfig.htmlTransformers.copy(scriptRemover = v)))) }),
        ConfigurationBinding(Style,
            { c -> c.modemConfig.pppHttpCompatibilityConfig.htmlTransformers.styleRemover },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(pppHttpCompatibilityConfig = c.modemConfig.pppHttpCompatibilityConfig.copy(htmlTransformers = c.modemConfig.pppHttpCompatibilityConfig.htmlTransformers.copy(styleRemover = v)))) }),
        ConfigurationBinding(Attributes,
            { c -> c.modemConfig.pppHttpCompatibilityConfig.htmlTransformers.attributeCleaner },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(pppHttpCompatibilityConfig = c.modemConfig.pppHttpCompatibilityConfig.copy(htmlTransformers = c.modemConfig.pppHttpCompatibilityConfig.htmlTransformers.copy(attributeCleaner = v)))) }),
        ConfigurationBinding(Elements,
            { c -> c.modemConfig.pppHttpCompatibilityConfig.htmlTransformers.elementSimplifier },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(pppHttpCompatibilityConfig = c.modemConfig.pppHttpCompatibilityConfig.copy(htmlTransformers = c.modemConfig.pppHttpCompatibilityConfig.htmlTransformers.copy(elementSimplifier = v)))) }),
    )
}
