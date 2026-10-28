package no.skasti.yame.persistence

import no.skasti.yame.config.*
import no.skasti.yame.config.ini.IniParser
import no.skasti.yame.serial.SerialSettings
import no.skasti.yame.modem.ModemSettings
import no.skasti.yame.modem.LoginCredentials
import no.skasti.yame.ppp.proxy.transform.ElementSimplifierSettings
import no.skasti.yame.ppp.proxy.transform.ElementSimplifierRule
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import kotlin.test.*

class IniDocumentationTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("yame.ini")

    @Test fun `new scalar entries contain metadata and updates remain stable`() {
        val store = lazyApplicationConfigStore(path)
        store.set(SerialSettings.Baud to 9600)
        val created = Files.readString(path)
        assertContains(created, "[serial]\n# Serial port baud rate in bits per second.\nbaud=9600")
        assertEquals(9600, lazyApplicationConfigStore(path)[SerialSettings.Baud])
        store.set(SerialSettings.Baud to 19200)
        assertEquals(created.replace("baud=9600", "baud=19200"), Files.readString(path))
        val bytes = Files.readAllBytes(path)
        store.set(SerialSettings.Baud to 19200)
        assertContentEquals(bytes, Files.readAllBytes(path))
        assertFailsWith<IllegalArgumentException> { store.set(SerialSettings.Baud to 0) }
        assertContentEquals(bytes, Files.readAllBytes(path))
        assertEquals(19200, store[SerialSettings.Baud])
    }

    @Test fun `existing comments whitespace order and unknown values survive updating undocumented entries`() {
        for (entry in listOf("[serial]\r\nbaud=38400", "serial.baud=38400")) {
            Files.writeString(path, "# user heading\r\n\r\n$entry\r\n# user footer\r\n[other]\r\nunknown= keep me \r\n")
            val store = lazyApplicationConfigStore(path)
            store.set(SerialSettings.Baud to 9600)
            val text = Files.readString(path)
            assertTrue(text.startsWith("# user heading\r\n\r\n"))
            assertContains(text, "# user footer\r\n[other]\r\nunknown= keep me \r\n")
            assertFalse(text.contains(SerialSettings.Baud.description!!))
            store.set(SerialSettings.Baud to 19200)
            assertEquals(text.replace("baud=9600", "baud=19200"), Files.readString(path))
        }
    }

    @Test fun `multiline descriptions are comments and cannot inject INI properties`() {
        val feature = object : Settings("example", description = "File overview\nSecond overview line") {
            val group = SettingGroup(this)
            val Value = SettingKey<Int>("value", group, default = { 7 },
                description = "First line\n\n[not-a-section]\ninjected=99")
        }
        val store = lazyApplicationConfigStore(path)
        store.createFileIfMissing(feature)
        val text = Files.readString(path)
        assertTrue(text.startsWith("# File overview\n# Second overview line\n\n"))
        assertContains(text, "# First line\n#\n# [not-a-section]\n# injected=99\nvalue=7")
        assertEquals(listOf("example"), IniParser.parse(text).sections())
        assertEquals(7, lazyApplicationConfigStore(path)[feature.Value])
        Files.writeString(path, "# custom\n[example]\nvalue=8\n")
        store.createFileIfMissing(feature)
        assertEquals("# custom\n[example]\nvalue=8\n", Files.readString(path))
    }

    @Test fun `structural rules describe their representation once and round trip normally`() {
        val store = lazyApplicationConfigStore(path)
        store.createFileIfMissing(ElementSimplifierSettings)
        val rulesPath = directory.resolve("element-simplifier.ini")
        val text = Files.readString(rulesPath)
        assertTrue(text.startsWith("# HTML element simplification."))
        val description = "# HTML element simplification rules."
        assertEquals(1, text.lineSequence().count { it == description })
        assertContains(text, "# Each section names an HTML tag to transform.\n# target: Replacement HTML element.\n# style: Optional CSS applied to the replacement.")
        assertTrue(text.indexOf(description) < text.indexOf("[main]"))
        val rules = store[ElementSimplifierSettings.Rules]
        assertEquals(ElementSimplifierRule("p", "font-weight:bold;"), rules.rules["summary"])
        store.set(ElementSimplifierSettings.Enabled to false)
        assertEquals(1, Files.readString(rulesPath).lineSequence().count { it == description })
        assertEquals(rules, lazyApplicationConfigStore(path)[ElementSimplifierSettings.Rules])
        assertFalse(lazyApplicationConfigStore(path)[ElementSimplifierSettings.Enabled])
    }

    @Test fun `new entries follow document newline conventions and retain adjacent documentation on readdition`() {
        val feature = object : Settings("optional") {
            val group = SettingGroup(this)
            val Value = SettingKey<String?>("value", group, default = { null }, description = "Optional text.\nKeep this explanation.")
        }
        Files.writeString(path, "# existing\r\n[optional]\r\nother=unchanged\r\n")
        val store = lazyApplicationConfigStore(path)
        store.set(feature.Value to "first")
        val first = Files.readString(path)
        assertContains(first, "other=unchanged\r\n# Optional text.\r\n# Keep this explanation.\r\nvalue=first\r\n")
        store.set(feature.Value to null)
        // User content inserted after the retained explanation must stay in place.
        Files.writeString(path, Files.readString(path) + "# user note\r\nextra=untouched\r\n")
        store.set(feature.Value to "second")
        val second = Files.readString(path)
        assertEquals(1, second.lineSequence().count { it == "# Optional text." })
        assertContains(second, "# user note\r\nextra=untouched\r\nvalue=second\r\n")
        assertEquals("second", lazyApplicationConfigStore(path)[feature.Value])
    }

    @Test fun `documentation size failures roll back the complete transaction`() {
        val large = object : Settings("large") {
            val group = SettingGroup(this)
            val Value = SettingKey<Int>("value", group, default = { 1 }, description = "x".repeat(IniParser.MAX_BYTES))
        }
        val store = lazyApplicationConfigStore(path)
        store.set(SerialSettings.Baud to 9600)
        val before = Files.readAllBytes(path)
        assertFailsWith<IllegalArgumentException> {
            store.set(SerialSettings.Baud to 19200, large.Value to 2)
        }
        assertContentEquals(before, Files.readAllBytes(path))
        assertEquals(9600, store[SerialSettings.Baud])
        assertEquals(1, store[large.Value])
    }

    @Test fun `composite descriptions are emitted once without disclosing values in comments`() {
        val store = lazyApplicationConfigStore(path)
        store.set(ModemSettings.Login to LoginCredentials("user", "secret"))
        val text = Files.readString(path)
        assertEquals(1, text.lineSequence().count { it.startsWith("# Terminal login credentials.") })
        assertTrue(text.lineSequence().filter { it.startsWith('#') }.none { "secret" in it })
        store.set(ModemSettings.Login to LoginCredentials("other", "new-secret"))
        assertEquals(text.replace("=user", "=other").replace("=secret", "=new-secret"), Files.readString(path))
    }
}
