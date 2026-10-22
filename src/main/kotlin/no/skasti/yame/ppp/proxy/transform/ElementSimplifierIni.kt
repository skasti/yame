package no.skasti.yame.ppp.proxy.transform

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
        val document = no.skasti.yame.config.ini.IniParser.parse(text, "element-simplifier.ini")
        val changes = no.skasti.yame.config.ConfigChanges(ElementSimplifierSettings.loadFromIni(
            no.skasti.yame.config.IniReadContext("element-simplifier.ini", document, true),
        ))
        return requireNotNull(changes[ElementSimplifierSettings.Rules]).value
    }
}
