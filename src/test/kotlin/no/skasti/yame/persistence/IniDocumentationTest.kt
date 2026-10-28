package no.skasti.yame.persistence

import no.skasti.yame.config.*
import no.skasti.yame.config.ini.IniParser
import no.skasti.yame.modem.ModemSettings
import no.skasti.yame.modem.LoginCredentials
import no.skasti.yame.ppp.proxy.transform.ElementSimplifierSettings
import no.skasti.yame.ppp.proxy.transform.ElementSimplifierRule
import no.skasti.yame.serial.SerialSettings
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import kotlin.test.*

class IniDocumentationTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("yame.ini")
    private class Example(
        description: String? = "File overview",
        groupDescription: String? = "Section overview",
        keyDescription: String? = "First line\n\n[not-a-section]\ninjected=99",
    ) : Settings("example", description = description) {
        val Group = SettingGroup(this, description = groupDescription)
        val Value = SettingKey<Int>("value", Group, { require(it > 0) }, default = { 7 }, description = keyDescription)
        val Extra = SettingKey<String?>("extra", Group, default = { null }, description = "Optional text.")
    }

    @Test fun `new sections have one marker group first property names and safe multiline continuations`() {
        val feature = Example()
        val store = lazyApplicationConfigStore(path)
        store.createFileIfMissing(feature)
        val text = Files.readString(path)
        assertTrue(text.startsWith("##### @yame file\n# File overview\n\n"))
        assertContains(text, "##### @yame example\n# Section overview\n# value: First line\n#   \n#   [not-a-section]\n#   injected=99\n[example]\nvalue=7\n")
        assertEquals(listOf("example"), IniParser.parse(text).sections())
        assertEquals(7, lazyApplicationConfigStore(path)[feature.Value])
        store.synchronizeDocumentation(feature)
        assertEquals(text, Files.readString(path))
    }

    @Test fun `group description is optional and identical descriptions in separate sections both appear`() {
        val feature = object : Settings("two") {
            val A = SettingGroup("a", this)
            val B = SettingGroup("b", this)
            val First = SettingKey<Int>("first", A, default = { 1 }, description = "Same text")
            val Second = SettingKey<Int>("second", B, default = { 2 }, description = "Same text")
        }
        val store = lazyApplicationConfigStore(path)
        store.createFileIfMissing(feature)
        val text = Files.readString(path)
        assertContains(text, "##### @yame a\n# first: Same text\n[a]\nfirst=1\n\n\n##### @yame b\n# second: Same text\n[b]\nsecond=2")
        store.synchronizeDocumentation(feature)
        assertEquals(text, Files.readString(path))
    }

    @Test fun `metadata refresh replaces marked blocks independently of values and notifications`() {
        val original = Example(keyDescription = "Old description")
        lazyApplicationConfigStore(path).createFileIfMissing(original)
        val feature = Example(keyDescription = "New description")
        val store = lazyApplicationConfigStore(path)
        store.ensureLoaded(feature)
        var events = 0
        store.onChange.subscribe { events++ }.use {
            val before = Files.readString(path)
            store.set(feature.Value to 7) // No-op value writes do not perform IO.
            assertEquals(before, Files.readString(path))
            store.setTransient(feature.Value to 9)
            assertEquals(1, events)
            store.synchronizeDocumentation(feature)
            assertEquals(9, store[feature.Value])
            assertEquals(7, lazyApplicationConfigStore(path)[feature.Value])
            val after = Files.readString(path)
            assertEquals(before.replace("Old description", "New description"), after)
            repeat(3) { store.synchronizeDocumentation(feature) }
            assertEquals(after, Files.readString(path))
            assertEquals(1, events)
        }
    }

    @Test fun `only marked documentation is replaced or removed`() {
        val text = "# personal heading\n##### @yame example\n# old\n; inside is generated too\n  # indented generated comment\n[example]\nvalue=7\n# user footer\n[other]\nunknown= keep me \n"
        Files.writeString(path, text)
        val feature = Example(description = null, groupDescription = null, keyDescription = "Current")
        val store = lazyApplicationConfigStore(path)
        store.synchronizeDocumentation(feature)
        val updated = Files.readString(path)
        assertEquals(text.replace("# old\n; inside is generated too\n  # indented generated comment", "# value: Current"), updated)
        val undocumented = Example(description = null, groupDescription = null, keyDescription = null)
        lazyApplicationConfigStore(path).synchronizeDocumentation(undocumented)
        assertEquals(updated.replace("##### @yame example\n# value: Current\n", ""), Files.readString(path))
    }

    @Test fun `unmarked comments remain user owned even if their text matches metadata`() {
        val before = "# File overview\n# Section overview\n# value: Current\n[example]\nvalue=7\n; trailing\n"
        Files.writeString(path, before)
        val feature = Example(keyDescription = "Current")
        val store = lazyApplicationConfigStore(path)
        store.synchronizeDocumentation(feature)
        val after = Files.readString(path)
        assertContains(after, "# File overview\n# Section overview\n# value: Current\n##### @yame example")
        assertTrue(after.endsWith("[example]\nvalue=7\n; trailing\n"))
        store.synchronizeDocumentation(feature)
        assertEquals(after, Files.readString(path))
    }

    @Test fun `malformed incomplete mismatched and detached markers are preserved`() {
        for (ambiguous in listOf("##### @yame other\n# user", "##### @yame\n# user", " ##### @yame example\n# user", "##### @yame example\n\n# user", "##### @yame example\nunknown=42\n# user")) {
            Files.writeString(path, "$ambiguous\n[example]\nvalue=7\n")
            val feature = Example(description = null, keyDescription = "Current")
            val store = lazyApplicationConfigStore(path)
            store.synchronizeDocumentation(feature)
            val text = Files.readString(path)
            assertTrue(text.startsWith("$ambiguous\n"), text)
            assertContains(text, "##### @yame example\n# Section overview\n# value: Current\n[example]")
            store.synchronizeDocumentation(feature)
            assertEquals(text, Files.readString(path))
        }
    }

    @Test fun `new properties precede trailing whitespace comments and following documentation`() {
        for (newline in listOf("\n", "\r\n")) {
            val before = "# heading\n[example]\nvalue=7\n\n; next section note\n##### @yame other\n# unrelated\n[other]\na=1\n".replace("\n", newline)
            Files.writeString(path, before)
            val feature = Example(description = null, groupDescription = null, keyDescription = null)
            val store = lazyApplicationConfigStore(path)
            store.set(feature.Extra to "added")
            val text = Files.readString(path)
            assertContains(text, "value=7${newline}extra=added${newline}${newline}; next section note${newline}##### @yame other")
            assertContains(text, "# extra: Optional text.${newline}[example]")
            assertEquals(newline == "\r\n", text.contains("\r"))
            store.set(feature.Extra to null)
            assertFalse(Files.readString(path).contains("# extra:"))
            store.set(feature.Extra to "again")
            assertEquals(1, Files.readString(path).lineSequence().count { it == "# extra: Optional text." })
        }
    }

    @Test fun `file blocks are separate and only anchored explicit file blocks are removed`() {
        val original = "##### @yame file\n# old file\n\n# user\n##### @yame example\n# old section\n[example]\nvalue=7\n"
        Files.writeString(path, original)
        val feature = Example(description = "New file", keyDescription = "Current")
        val store = lazyApplicationConfigStore(path)
        store.synchronizeDocumentation(feature)
        assertTrue(Files.readString(path).startsWith("##### @yame file\n# New file\n\n# user\n"))
        val noFile = Example(description = null, keyDescription = "Current")
        lazyApplicationConfigStore(path).synchronizeDocumentation(noFile)
        assertTrue(Files.readString(path).startsWith("# user\n##### @yame example\n"))
        for (text in listOf("##### @yame file\n# actual section\n[file]\nx=1\n", "# user\n##### @yame file\n# unanchored\n\n", "##### @yame file\n##### @yame broken\n# user\n\n")) {
            Files.writeString(path, text + "[example]\nvalue=7\n")
            lazyApplicationConfigStore(path).synchronizeDocumentation(noFile)
            assertTrue(Files.readString(path).startsWith(text))
        }
    }

    @Test fun `structural descriptions are shared once at file level and rules round trip`() {
        val store = lazyApplicationConfigStore(path)
        store.createFileIfMissing(ElementSimplifierSettings)
        val rulesPath = directory.resolve("element-simplifier.ini")
        val text = Files.readString(rulesPath)
        assertTrue(text.startsWith("##### @yame file\n# HTML element simplification."))
        assertEquals(1, text.lineSequence().count { it == "# HTML element simplification rules." })
        assertContains(text, "# target: Replacement HTML element.\n# style: Optional CSS applied to the replacement.")
        assertContains(text, "[main]\ntarget=div\n\n\n[article]\ntarget=div")
        assertFalse(text.contains("@yame rules"))
        val rules = store[ElementSimplifierSettings.Rules]
        assertEquals(ElementSimplifierRule("p", "font-weight:bold;"), rules.rules["summary"])
        store.synchronizeDocumentation(ElementSimplifierSettings)
        assertEquals(text, Files.readString(rulesPath))
        store.set(ElementSimplifierSettings.Enabled to false)
        assertEquals(rules, lazyApplicationConfigStore(path)[ElementSimplifierSettings.Rules])
    }

    @Test fun `composite children use actual property names without leaking values`() {
        val store = lazyApplicationConfigStore(path)
        store.set(ModemSettings.Login to LoginCredentials("user", "secret"))
        val text = Files.readString(path)
        assertContains(text, "# login.username: Terminal login credentials.")
        assertContains(text, "# login.password: Terminal login credentials.")
        assertEquals(1, text.lineSequence().count { it == "##### @yame modem" })
        assertTrue(text.lineSequence().filter { it.startsWith('#') }.none { "secret" in it })
        store.synchronizeDocumentation(ModemSettings)
        assertEquals(text, Files.readString(path))
    }

    @Test fun `supported flat entries remain compatible and synchronization does not normalize values`() {
        val text = "# custom\r\nserial.baud=9600\r\n[unknown]\r\nother= keep \r\n"
        Files.writeString(path, text)
        val store = lazyApplicationConfigStore(path)
        store.synchronizeDocumentation(SerialSettings)
        assertEquals(text, Files.readString(path))
        assertEquals(9600, store[SerialSettings.Baud])
        store.set(SerialSettings.Baud to 19200)
        assertContains(Files.readString(path), "##### @yame serial\r\n# Serial port configuration.\r\n# baud: Serial port baud rate in bits per second.\r\n[serial]\r\nbaud=19200")
    }

    @Test fun `documentation failures roll back value transactions and documentation only writes`() {
        val feature = Example(keyDescription = "x".repeat(IniParser.MAX_BYTES))
        val store = lazyApplicationConfigStore(path)
        store.set(SerialSettings.Baud to 9600)
        val before = Files.readAllBytes(path)
        var events = 0
        store.onChange.subscribe { events++ }.use {
            assertFailsWith<IllegalArgumentException> { store.set(SerialSettings.Baud to 19200, feature.Value to 2) }
            assertContentEquals(before, Files.readAllBytes(path))
            assertEquals(9600, store[SerialSettings.Baud])
            assertEquals(7, store[feature.Value])
            assertEquals(0, events)
        }
        Files.writeString(path, "[example]\nvalue=7\n")
        val old = Files.readString(path)
        assertFailsWith<IllegalArgumentException> { store.synchronizeDocumentation(feature) }
        assertEquals(old, Files.readString(path))
        assertFailsWith<IllegalArgumentException> { store.set(feature.Value to 0) }
        assertEquals(7, store[feature.Value])
    }
    @Test fun `removing a section preserves following documentation and unmarked comments`() {
        val doc = IniParser.parse("##### @yame a\n# generated\n[a]\nx=1\n# personal\n\n##### @yame b\n# next\n[b]\ny=2\n")
        doc.removeSection("a")
        assertEquals("# personal\n\n##### @yame b\n# next\n[b]\ny=2\n", doc.render())
    }

    @Test fun `synchronizing a shared file retains metadata from other loaded owners and skips unchanged replacements`() {
        var replacements = 0
        val repository = IniConfigurationRepository(path) { source, destination ->
            replacements++
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        val store = ConfigStore(repository = repository)
        val feature = Example(description = null)
        store.set(feature.Value to 8, SerialSettings.Baud to 9600)
        val text = Files.readString(path)
        assertContains(text, "##### @yame serial")
        assertContains(text, "##### @yame example")
        store.synchronizeDocumentation(feature)
        assertEquals(text, Files.readString(path))
        assertEquals(1, replacements)
        store.set(SerialSettings.Baud to 19200)
        assertEquals(text.replace("baud=9600", "baud=19200"), Files.readString(path))
        store.synchronizeDocumentation(feature, SerialSettings)
        assertEquals(2, replacements)
    }

    @Test fun `shared file overviews are stable across definition loading order`() {
        class Feature(id: String) : Settings(id, description = "Overview for $id") {
            val Group = SettingGroup(this)
            val Value = SettingKey<Int>("value", Group, default = { 1 })
        }
        val a = Feature("a")
        val b = Feature("b")
        val store = lazyApplicationConfigStore(path)
        store.set(a.Value to 2, b.Value to 2)
        val before = Files.readString(path)
        assertTrue(before.startsWith("##### @yame file\n# Overview for a\n# Overview for b\n\n"))
        lazyApplicationConfigStore(path).synchronizeDocumentation(b, a)
        assertEquals(before, Files.readString(path))
    }

    @Test fun `documentation stages every affected file before replacing any file`() {
        Files.writeString(path, "[example]\nvalue=7\n")
        val secondary = directory.resolve("huge.ini")
        Files.writeString(secondary, "[huge]\nvalue=1\n")
        val huge = object : Settings("huge", file = "huge", description = "x".repeat(IniParser.MAX_BYTES)) {
            val Group = SettingGroup(this)
            val Value = SettingKey<Int>("value", Group, default = { 1 })
        }
        val store = lazyApplicationConfigStore(path)
        assertFailsWith<IllegalArgumentException> { store.synchronizeDocumentation(Example(), huge) }
        assertEquals("[example]\nvalue=7\n", Files.readString(path))
        assertEquals("[huge]\nvalue=1\n", Files.readString(secondary))
    }

}
