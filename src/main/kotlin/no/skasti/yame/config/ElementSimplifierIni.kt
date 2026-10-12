package no.skasti.yame.config

import java.io.InputStream
import java.util.Locale

internal object ElementSimplifierIni {
    const val MAX_BYTES = 32 * 1024

    val defaultText: String by lazy {
        requireNotNull(javaClass.getResourceAsStream("/element-simplifier.ini")) {
            "Missing bundled element-simplifier.ini"
        }.use(::readBounded)
    }
    val defaults: ElementSimplifierConfiguration by lazy { parse(defaultText) }

    fun readBounded(input: InputStream): String {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES) { "element-simplifier.ini is larger than $MAX_BYTES bytes" }
        return bytes.toString(Charsets.UTF_8)
    }

    fun parse(text: String): ElementSimplifierConfiguration {
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var section: MutableMap<String, String>? = null
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(';') || line.startsWith('#')) return@forEachIndexed
            fun invalid(reason: String): Nothing =
                throw IllegalArgumentException("element-simplifier.ini line ${index + 1}: $reason")
            if (line.startsWith('[') && line.endsWith(']')) {
                val name = line.substring(1, line.lastIndex).trim().lowercase(Locale.ROOT)
                if (!TAG_NAME.matches(name)) invalid("invalid tag section '$name'")
                if (name in sections) invalid("duplicate section [$name]")
                if (sections.size >= 256) invalid("at most 256 sections are supported")
                section = linkedMapOf<String, String>().also { sections[name] = it }
            } else {
                val values = section ?: invalid("setting must follow a tag section")
                val separator = line.indexOf('=')
                if (separator < 1) invalid("expected key=value")
                val key = line.substring(0, separator).trim().lowercase(Locale.ROOT)
                if (key !in setOf("target", "style")) invalid("unknown setting '$key'")
                if (key in values) invalid("duplicate setting '$key'")
                values[key] = line.substring(separator + 1).trim()
            }
        }
        return ElementSimplifierConfiguration(sections.mapValues { (name, settings) ->
            val target = requireNotNull(settings["target"]) { "element-simplifier.ini [$name] requires target" }
            ElementSimplifierRule(target, settings["style"]?.takeIf(String::isNotEmpty))
        })
    }
}
