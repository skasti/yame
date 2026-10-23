package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.config.*

/** Dynamic tag sections in the existing element-simplifier.ini. */
object ElementSimplifierSettings : SeparateSettings("element-simplifier") {
    val General = SettingGroup("element-simplifier.general", this)
    val Enabled = SettingKey<Boolean>("enabled", General, default = { true })
    val Group = SettingGroup(this)
    val Rules = SettingKey<ElementSimplifierConfiguration>("rules", Group, structural = true,
        default = { ElementSimplifierConfiguration.defaults() })

    override fun loadFromIni(context: IniReadContext): List<ConfigChange<*>> {
        val ordinary = super.loadFromIni(context)
        require("target" !in context.rootProperties()) {
            "${context.filename}: a rule target requires a named section"
        }
        val rules = linkedMapOf<String, ElementSimplifierRule>()
        val sections = (context.unconsumedSections() + context.flatSectionsFor("target", "style")).distinct()
        for (section in sections.filter { it != General.id }) {
            require(TAG_NAME.matches(section)) { "${context.filename}: invalid rule section [$section]" }
            val rule = context.compose(Rules, section) { attrs ->
                attrs.keys.firstOrNull { it != "target" && it != "style" }?.let {
                    throw IllegalArgumentException("unknown setting '$it'")
                }
                // Rule fields have their own domain normalization. The shared INI
                // parser leaves their source values untouched.
                val target = requireNotNull(attrs["target"]) { "requires target" }.trim()
                val style = attrs["style"]?.trim()?.takeIf(String::isNotEmpty)
                ElementSimplifierRule(target, style)
            }
            if (rule != null) rules[section] = rule
        }
        return ordinary + (Rules to if (context.existed) ElementSimplifierConfiguration(rules) else ElementSimplifierConfiguration.defaults())
    }

    override fun saveToIni(context: IniWriteContext) {
        super.saveToIni(context)
        if (Rules !in context.changedKeys) return
        context.ownedSections(Rules).filter { it != General.id }.forEach { context.removeSection(Rules, it) }
        requireNotNull(context.snapshot[Rules]).value.rules.forEach { (tag, rule) ->
            context.writeAttributes(Rules, tag) { attrs ->
                attrs["target"] = rule.target
                if (rule.style == null) attrs.remove("style") else attrs["style"] = rule.style
            }
        }
    }
}
