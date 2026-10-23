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
        require("target" !in context.properties("")) {
            "${context.filename}: a rule target requires a named section"
        }
        val rules = linkedMapOf<String, ElementSimplifierRule>()
        for (section in context.unconsumedSections().filter { it != General.id }) {
            require(TAG_NAME.matches(section)) { "${context.filename}: invalid rule section [$section]" }
            val properties = context.properties(section)
            properties.keys.firstOrNull { it != "target" && it != "style" }?.let {
                throw IllegalArgumentException("${context.filename} [$section]: unknown setting '$it'")
            }
            val target = context.claim(section, "target")
                ?: throw IllegalArgumentException("${context.filename} [$section] requires target")
            val style = if ("style" in properties) context.claim(section, "style")?.takeIf(String::isNotEmpty) else null
            try { rules[section] = ElementSimplifierRule(target, style) }
            catch (error: IllegalArgumentException) { throw IllegalArgumentException("${context.filename} [$section]: ${error.message}", error) }
        }
        return ordinary + (Rules to if (context.existed) ElementSimplifierConfiguration(rules) else ElementSimplifierConfiguration.defaults())
    }

    override fun saveToIni(context: IniWriteContext) {
        super.saveToIni(context)
        if (Rules !in context.changedKeys) return
        context.sections().filter { it != General.id && TAG_NAME.matches(it) }.forEach(context::removeSection)
        requireNotNull(context.snapshot[Rules]).value.rules.forEach { (tag, rule) ->
            context.write(tag, "target", rule.target)
            rule.style?.let { context.write(tag, "style", it) }
        }
    }
}
