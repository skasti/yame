package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.persistence.iniConfigStore

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ElementSimplifierConfigurationStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `missing file uses bundled defaults and creation never overwrites user rules`() {
        val path = directory.resolve(ElementSimplifierConfigurationStore.FILE_NAME)
        val store = ElementSimplifierConfigurationStore(path)
        assertEquals(ElementSimplifierRule("p", "font-weight:bold;"), store.load().rules["summary"])
        assertFalse(Files.exists(path))
        store.createDefaultIfMissing()
        assertEquals(ElementSimplifierConfiguration.defaults(), store.load())
        val custom = "[summary]\ntarget=h3\nstyle=font-weight:normal;\n"
        Files.writeString(path, custom)
        store.createDefaultIfMissing()
        assertEquals(custom, Files.readString(path))
        assertEquals(ElementSimplifierRule("h3", "font-weight:normal;"), store.load().rules["summary"])
    }

    @Test
    fun `case insensitive sections preserve CSS semicolons equals and hash characters`() {
        val text = """
            ; Full-line comment
            # Another comment
            [ SUMMARY ]
            TARGET = P
            style=font-weight:bold; color:#333; background:url('/icon.gif?v=1');
        """.trimIndent()
        val rules = ElementSimplifierIni.parse(text).rules
        assertEquals(setOf("summary"), rules.keys)
        assertEquals("p", rules.getValue("summary").target)
        assertEquals("font-weight:bold; color:#333; background:url('/icon.gif?v=1');", rules.getValue("summary").style)
        assertTrue(ElementSimplifierIni.parse("; no rules").rules.isEmpty())
        assertTrue(ElementSimplifierIni.parse("[bad tag]\ntarget=p\n").rules.isEmpty())
    }

    @Test
    fun `existing general tag section and section order remain valid`() {
        val text = """
            ; existing mapping
            [SUMMARY]
            target=p

            [general]
            target=div
            style=color:#333; width:1px;

            [time]
            target=span
        """.trimIndent() + "\n"
        val expected = listOf("summary", "general", "time")
        assertEquals(expected, ElementSimplifierIni.parse(text).rules.keys.toList())
        val path = directory.resolve("element-simplifier.ini")
        Files.writeString(path, text)
        assertEquals(expected, ElementSimplifierConfigurationStore(path).load().rules.keys.toList())
        val runtime = no.skasti.yame.persistence.iniConfigStore(directory.resolve("yame.ini"))
        assertEquals(expected, runtime[ElementSimplifierSettings.Rules].rules.keys.toList())
        assertEquals(text, Files.readString(path))
        runtime.set(ElementSimplifierSettings.Rules to runtime[ElementSimplifierSettings.Rules].let { current ->
            ElementSimplifierConfiguration(current.rules + ("time" to ElementSimplifierRule("div")))
        })
        assertEquals(expected, ElementSimplifierConfigurationStore(path).load().rules.keys.toList())
        assertEquals("div", ElementSimplifierConfigurationStore(path).load().rules.getValue("general").target)
        assertTrue(ElementSimplifierIni.parse("").rules.isEmpty())
        Files.writeString(path, "")
        assertTrue(ElementSimplifierConfigurationStore(path).load().rules.isEmpty())
        assertTrue(no.skasti.yame.persistence.iniConfigStore(directory.resolve("yame.ini"))
            [ElementSimplifierSettings.Rules].rules.isEmpty())
    }

    @Test
    fun `changing yame ini leaves sibling rule comments and values untouched`() {
        val rules = "; user rule\n[general]\n# keep\ntarget=div\nstyle=color:#333;\n"
        val path = directory.resolve("element-simplifier.ini")
        Files.writeString(path, rules)
        val runtime = no.skasti.yame.persistence.iniConfigStore(directory.resolve("yame.ini"))
        assertEquals("color:#333;", runtime[ElementSimplifierSettings.Rules].rules.getValue("general").style)
        runtime[no.skasti.yame.serial.SerialSettings.Baud]
        runtime.set(no.skasti.yame.serial.SerialSettings.Baud to 57_600)
        assertEquals(rules, Files.readString(path))
    }

    @Test
    fun `invalid ambiguous or incomplete rules are rejected`() {
        for (text in listOf(
            "target=p", "[summary]\nstyle=font-weight:bold;", "[summary]\ntarget=<p>",
            "[summary]\ntarget=script", "[summary]\ntarget=img", "[summary]\ntarget=p\nunknown=yes",
            "[summary]\ntarget=p\ntarget=div", "[summary]\ntarget=p\n[SUMMARY]\ntarget=span",
            "[summary]\ntarget=p\nstyle=${"x".repeat(1025)}",
        )) {
            assertFailsWith<IllegalArgumentException>(text) { ElementSimplifierIni.parse(text) }
        }
        assertFailsWith<IllegalArgumentException> {
            ElementSimplifierIni.parse((0..256).joinToString("\n") { "[tag-$it]\ntarget=div" })
        }
    }

    @Test
    fun `file size and regular file checks bound configuration reads`() {
        val path = directory.resolve("element-simplifier.ini")
        Files.write(path, ByteArray(ElementSimplifierIni.MAX_BYTES + 1))
        assertFailsWith<IllegalArgumentException> { ElementSimplifierConfigurationStore(path).load() }
        assertFailsWith<IllegalArgumentException> { ElementSimplifierConfigurationStore(directory).load() }
    }

    @Test
    fun `rule configuration owns an immutable normalized snapshot`() {
        val source = mutableMapOf("SUMMARY" to ElementSimplifierRule("P"))
        val configuration = ElementSimplifierConfiguration(source)
        source.clear()
        assertEquals(mapOf("summary" to ElementSimplifierRule("p")), configuration.rules)
        assertFailsWith<UnsupportedOperationException> {
            (configuration.rules as MutableMap<String, ElementSimplifierRule>).clear()
        }
    }

    @Test
    fun `main configuration loads sibling rules even before yame ini exists`() {
        val configPath = directory.resolve("custom-yame.ini")
        val elementPath = directory.resolve(ElementSimplifierConfigurationStore.FILE_NAME)
        Files.writeString(elementPath, "[summary]\ntarget=h3\nstyle=font-weight:bold;\n")
        val store = iniConfigStore(configPath)
        val configuration = ElementSimplifierConfiguration.fromConfigStore(store)
        assertEquals(mapOf("summary" to ElementSimplifierRule("h3", "font-weight:bold;")), configuration.rules)
        assertEquals(configuration, ElementSimplifierConfiguration.fromConfigStore(iniConfigStore(configPath)))
        assertEquals("[summary]\ntarget=h3\nstyle=font-weight:bold;\n", Files.readString(elementPath))
    }
}
