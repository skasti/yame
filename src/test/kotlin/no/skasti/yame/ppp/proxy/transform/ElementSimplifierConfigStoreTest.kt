package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.persistence.lazyApplicationConfigStore
import no.skasti.yame.config.ConfigStore

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ElementSimplifierConfigStoreTest {
    @TempDir
    lateinit var directory: Path

    private fun store() = lazyApplicationConfigStore(directory.resolve("yame.ini"))
    private fun rules() = ElementSimplifierConfiguration.fromConfigStore(store())
    private fun readRules(text: String): ElementSimplifierConfiguration {
        Files.writeString(directory.resolve("element-simplifier.ini"), text)
        return rules()
    }

    @Test
    fun `shared store initializes bundled defaults without loading or overwriting user rules`() {
        val path = directory.resolve("element-simplifier.ini")
        val store = store()
        var notifications = 0
        store.onChange.subscribe { notifications++ }
        ConfigStore.install(store).use {
            ConfigStore.current.createFileIfMissing(ElementSimplifierSettings)
            var loaded = false
            store.onChange[ElementSimplifierSettings.Group].subscribe(initial = true) { loaded = true }.close()
            assertFalse(loaded)
            assertEquals(ElementSimplifierConfiguration.defaults(), ElementSimplifierConfiguration.fromConfigStore())
        }
        assertEquals(0, notifications)
        assertEquals(ElementSimplifierIni.defaultText, Files.readString(path))
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(path))
        }
        val custom = "[summary]\ntarget=h3\nstyle=font-weight:normal;\n"
        Files.writeString(path, custom)
        store.createFileIfMissing(ElementSimplifierSettings)
        assertEquals(custom, Files.readString(path))
        assertEquals(ElementSimplifierRule("h3", "font-weight:normal;"), rules().rules["summary"])
        // Empty and malformed files must also remain untouched by initialization.
        for (existing in listOf("", "[summary]\ntarget=<invalid>\n")) {
            Files.writeString(path, existing)
            store.createFileIfMissing(ElementSimplifierSettings)
            assertEquals(existing, Files.readString(path))
        }
    }

    @Test fun `missing rule reads use defaults without creating files`() {
        assertEquals(ElementSimplifierConfiguration.defaults(), rules())
        assertFalse(Files.exists(directory.resolve("element-simplifier.ini")))
    }

    @Test fun `shared store persists edits and switch across restart without leaking transient values`() {
        val store = store()
        store.createFileIfMissing(ElementSimplifierSettings)
        val saved = store[ElementSimplifierSettings.Rules]
        val transient = ElementSimplifierConfiguration(mapOf("summary" to ElementSimplifierRule("h3")))
        store.setTransient(ElementSimplifierSettings.Rules to transient)
        store.set(ElementSimplifierSettings.Enabled to false)
        assertEquals(saved, rules())
        assertFalse(store()[ElementSimplifierSettings.Enabled])
        store.set(ElementSimplifierSettings.Rules to transient)
        assertEquals(transient, rules())
        assertFalse(store()[ElementSimplifierSettings.Enabled])
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
        val rules = readRules(text).rules
        assertEquals(setOf("summary"), rules.keys)
        assertEquals("p", rules.getValue("summary").target)
        assertEquals("font-weight:bold; color:#333; background:url('/icon.gif?v=1');", rules.getValue("summary").style)
        assertTrue(readRules("; no rules").rules.isEmpty())
        assertTrue(readRules("[bad tag]\ntarget=p\n").rules.isEmpty())
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
        assertEquals(expected, readRules(text).rules.keys.toList())
        val path = directory.resolve("element-simplifier.ini")
        Files.writeString(path, text)
        assertEquals(expected, rules().rules.keys.toList())
        val runtime = no.skasti.yame.persistence.lazyApplicationConfigStore(directory.resolve("yame.ini"))
        assertEquals(expected, runtime[ElementSimplifierSettings.Rules].rules.keys.toList())
        assertEquals(text, Files.readString(path))
        runtime.set(ElementSimplifierSettings.Rules to runtime[ElementSimplifierSettings.Rules].let { current ->
            ElementSimplifierConfiguration(current.rules + ("time" to ElementSimplifierRule("div")))
        })
        assertEquals(expected, rules().rules.keys.toList())
        assertEquals("div", rules().rules.getValue("general").target)
        Files.writeString(path, "")
        assertTrue(rules().rules.isEmpty())
        assertTrue(no.skasti.yame.persistence.lazyApplicationConfigStore(directory.resolve("yame.ini"))
            [ElementSimplifierSettings.Rules].rules.isEmpty())
    }

    @Test
    fun `changing yame ini leaves sibling rule comments and values untouched`() {
        val rules = "; user rule\n[general]\n# keep\ntarget=div\nstyle=color:#333;\n"
        val path = directory.resolve("element-simplifier.ini")
        Files.writeString(path, rules)
        val runtime = no.skasti.yame.persistence.lazyApplicationConfigStore(directory.resolve("yame.ini"))
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
            assertFailsWith<IllegalArgumentException>(text) { readRules(text) }
        }
        assertFailsWith<IllegalArgumentException> {
            readRules((0..256).joinToString("\n") { "[tag-$it]\ntarget=div" })
        }
    }

    @Test
    fun `file size and regular file checks bound configuration reads`() {
        val path = directory.resolve("element-simplifier.ini")
        Files.write(path, ByteArray(ElementSimplifierIni.MAX_BYTES + 1))
        assertFailsWith<IllegalArgumentException> { rules() }
        Files.delete(path)
        Files.createDirectory(path)
        assertFailsWith<IllegalArgumentException> { rules() }
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
        val elementPath = directory.resolve("element-simplifier.ini")
        Files.writeString(elementPath, "[summary]\ntarget=h3\nstyle=font-weight:bold;\n")
        val store = lazyApplicationConfigStore(configPath)
        val configuration = ElementSimplifierConfiguration.fromConfigStore(store)
        assertEquals(mapOf("summary" to ElementSimplifierRule("h3", "font-weight:bold;")),
            configuration.rules)
        store[no.skasti.yame.serial.SerialSettings.Baud]
        store.set(no.skasti.yame.serial.SerialSettings.Baud to 9600)
        assertEquals(configuration, ElementSimplifierConfiguration.fromConfigStore(lazyApplicationConfigStore(configPath)))
        assertEquals("[summary]\ntarget=h3\nstyle=font-weight:bold;\n", Files.readString(elementPath))
    }
}
