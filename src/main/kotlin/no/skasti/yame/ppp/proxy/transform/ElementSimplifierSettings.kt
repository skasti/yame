package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.config.*

/** Dynamic tag sections in the existing element-simplifier.ini. */
object ElementSimplifierSettings : SeparateConfigBase("element-simplifier") {
    val Group = SettingGroup("ppp.proxy.transform.rules", this, "general")
    val Rules = SettingKey<ElementSimplifierConfiguration>("html-transformers.element-rules", Group)
    override val bindings: List<IniBinding<*>> = emptyList()

    override fun loadFromIni(context: IniReadContext): List<ConfigChange<*>> {
        require(context.properties("").isEmpty()) { "${context.filename}: rule settings must follow a tag section" }
        val rules = linkedMapOf<String, ElementSimplifierRule>()
        for (section in context.unconsumedSections()) {
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
        return listOf(Rules to if (context.existed) ElementSimplifierConfiguration(rules) else ElementSimplifierConfiguration.defaults())
    }

    override fun saveToIni(context: IniWriteContext) {
        if (Rules !in context.changedKeys) return
        context.sections().filter(TAG_NAME::matches).forEach(context::removeSection)
        requireNotNull(context.snapshot[Rules]).value.rules.forEach { (tag, rule) ->
            context.write(tag, "target", rule.target)
            rule.style?.let { context.write(tag, "style", it) }
        }
    }
}
