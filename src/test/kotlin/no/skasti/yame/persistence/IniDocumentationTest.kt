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

    private class Feature(id: String, description: String? = "Overview for $id", groupDescription: String? = "Section for $id") :
        Settings(id, description = description) {
        val Group = SettingGroup(this, description = groupDescription)
        val Value = SettingKey<Int>("value", Group, default = { 1 }, description = "Value for $id")
        var loads = 0
            private set
        override fun loadFromIni(context: IniReadContext): List<ConfigChange<*>> {
            loads++
            return super.loadFromIni(context)
        }
    }

    private class MultipleGroups(description: String? = "Multiple groups") : Settings("multi", description = description) {
        val First = SettingGroup("first", this, description = "First group")
        val Second = SettingGroup("second", this, description = "Second group")
        val A = SettingKey<Int>("a", First, default = { 1 }, description = "First value")
        val B = SettingKey<Int>("b", Second, default = { 2 }, description = "Second value")
    }

    @Test fun `new sections have one marker group first property names and safe multiline continuations`() {
        val feature = Example()
        val store = lazyApplicationConfigStore(path)
        store.createFileIfMissing(feature)
        val text = Files.readString(path)
        assertContains(text, "##### @yame example settings:\n# File overview\n\n##### @yame example\n")
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
        assertContains(after, "# File overview\n# Section overview\n# value: Current\n##### @yame example settings:\n# File overview\n\n##### @yame example\n")
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

    @Test fun `legacy file and unknown settings blocks are conservatively preserved`() {
        for (text in listOf(
            "##### @yame file\n# mixed overview for several owners\n\n",
            "##### @yame file\n# actual section\n[file]\nx=1\n",
            "# user\n##### @yame file\n# unanchored\n\n",
            "##### @yame file\n##### @yame broken\n# user\n\n",
            "##### @yame unknown settings:\n# not ours\n\n",
            " ##### @yame example settings:\n# malformed\n\n",
            "##### @yame example settings\n# incomplete marker\n\n",
            "##### @yame example settings:\n# incomplete block\nunknown=42\n",
        )) {
            Files.writeString(path, text + "[example]\nvalue=7\n")
            val store = lazyApplicationConfigStore(path)
            val feature = Example()
            store.synchronizeDocumentation(feature)
            val updated = Files.readString(path)
            assertTrue(updated.startsWith(text), updated)
            assertContains(updated, "##### @yame example settings:\n# File overview\n\n##### @yame example\n")
            store.synchronizeDocumentation(feature)
            assertEquals(updated, Files.readString(path))
        }
    }

    @Test fun `structural descriptions belong to their definition once and rules round trip`() {
        val store = lazyApplicationConfigStore(path)
        store.createFileIfMissing(ElementSimplifierSettings)
        val rulesPath = directory.resolve("element-simplifier.ini")
        val text = Files.readString(rulesPath)
        assertTrue(text.startsWith("##### @yame element-simplifier settings:\n# HTML element simplification."))
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
        assertContains(Files.readString(path), "##### @yame serial\r\n# baud: Serial port baud rate in bits per second.\r\n[serial]\r\nbaud=19200")
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
        assertContains(before, "##### @yame a settings:\n# Overview for a\n\n[a]\nvalue=2")
        assertContains(before, "##### @yame b settings:\n# Overview for b\n\n[b]\nvalue=2")
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

    @Test fun `lazy owners sharing a file update only their independent blocks after restart`() {
        val oldA = Feature("a", "Old A overview")
        val oldB = Feature("b", "Old B overview", "Old B section")
        lazyApplicationConfigStore(path).set(oldA.Value to 2, oldB.Value to 2)
        val before = Files.readString(path)
        val bBefore = before.substring(before.indexOf("##### @yame b settings:"))
        val a = Feature("a", "New A overview")
        val b = Feature("b", "New B overview", "New B section")
        val restarted = lazyApplicationConfigStore(path)
        restarted.synchronizeDocumentation(a)
        assertEquals(before.replace("Old A overview", "New A overview"), Files.readString(path))
        assertEquals(1, a.loads)
        assertEquals(0, b.loads)
        restarted.set(a.Value to 3)
        val afterA = Files.readString(path)
        assertEquals(bBefore, afterA.substring(afterA.indexOf("##### @yame b settings:")))
        assertEquals(0, b.loads)
        assertEquals(2, restarted[b.Value])
        restarted.synchronizeDocumentation(b)
        assertEquals(afterA.replace("Old B overview", "New B overview").replace("Old B section", "New B section"), Files.readString(path))
        val stable = Files.readString(path)
        repeat(3) { restarted.synchronizeDocumentation(a); restarted.synchronizeDocumentation(b) }
        assertEquals(stable, Files.readString(path))
        assertEquals(3, restarted[a.Value])
        assertEquals(2, restarted[b.Value])
    }

    @Test fun `settings overview follows document order across interleaved groups and remains independent`() {
        val original = "# heading\n[second]\nb=2\n\n# foreign note\n[foreign]\nvalue=4\n\n[first]\na=1\n"
        Files.writeString(path, original)
        val multi = MultipleGroups()
        val foreign = Feature("foreign")
        val store = lazyApplicationConfigStore(path)
        store.synchronizeDocumentation(multi, foreign)
        val text = Files.readString(path)
        assertContains(text, "# heading\n##### @yame multi settings:\n# Multiple groups\n\n##### @yame second\n# Second group\n# b: Second value\n[second]")
        assertContains(text, "# foreign note\n##### @yame foreign settings:")
        assertEquals(1, text.lineSequence().count { it == "##### @yame multi settings:" })
        assertEquals(listOf("second", "foreign", "first"), IniParser.parse(text).sections())
        val revised = MultipleGroups("Revised overview")
        lazyApplicationConfigStore(path).synchronizeDocumentation(revised)
        assertEquals(text.replace("Multiple groups", "Revised overview"), Files.readString(path))
    }

    @Test fun `settings descriptions update remove and relocate without moving user comments`() {
        for (newline in listOf("\n", "\r\n")) {
            val original = "# stay at top\n[second]\nb=2\n\n; stay between sections\n##### @yame multi settings:\n# Outdated overview\n\n##### @yame first\n# Outdated section\n[first]\na=1\n"
                .replace("\n", newline)
            Files.writeString(path, original)
            val multi = MultipleGroups()
            val store = lazyApplicationConfigStore(path)
            store.synchronizeDocumentation(multi)
            val updated = Files.readString(path)
            assertTrue(updated.startsWith("# stay at top${newline}##### @yame multi settings:"))
            assertContains(updated, "; stay between sections${newline}##### @yame first")
            assertEquals(listOf("second", "first"), IniParser.parse(updated).sections())
            assertEquals(1, updated.lineSequence().count { it == "##### @yame multi settings:" })
            assertEquals(newline == "\r\n", updated.contains("\r"))
            store.synchronizeDocumentation(multi)
            assertEquals(updated, Files.readString(path))
            val noOverview = MultipleGroups(null)
            lazyApplicationConfigStore(path).synchronizeDocumentation(noOverview)
            assertEquals(updated.replace("##### @yame multi settings:${newline}# Multiple groups${newline}${newline}", ""), Files.readString(path))
        }
    }

    @Test fun `consecutive definition and section markers remain independently replaceable`() {
        val original = "##### @yame example settings:\n# Old overview\n##### @yame example\n# Old section\n[example]\nvalue=7\n"
        Files.writeString(path, original)
        val feature = Example(description = "New overview", groupDescription = null, keyDescription = null)
        val store = lazyApplicationConfigStore(path)
        store.synchronizeDocumentation(feature)
        assertEquals("##### @yame example settings:\n# New overview\n\n[example]\nvalue=7\n", Files.readString(path))
        store.synchronizeDocumentation(feature)
        assertEquals("##### @yame example settings:\n# New overview\n\n[example]\nvalue=7\n", Files.readString(path))
    }

    @Test fun `root only mappings anchor deterministically and relocate when normalized to a section`() {
        val before = "# root note\r\nunknown= keep \r\nexample.value=7\r\n; footer\r\n"
        Files.writeString(path, before)
        val feature = Example(keyDescription = "Value")
        val store = lazyApplicationConfigStore(path)
        var events = 0
        store.onChange.subscribe { events++ }.use {
            store.synchronizeDocumentation(feature)
            val text = Files.readString(path)
            assertEquals(before.replace("example.value=7", "##### @yame example settings:\r\n# File overview\r\n\r\nexample.value=7"), text)
            repeat(2) { store.synchronizeDocumentation(feature) }
            assertEquals(text, Files.readString(path))
            assertEquals(0, events)
            assertEquals(7, store[feature.Value])
            store.set(feature.Value to 8)
            val normalized = Files.readString(path)
            assertEquals(1, normalized.lineSequence().count { it == "##### @yame example settings:" })
            assertContains(normalized, "##### @yame example settings:\r\n# File overview\r\n\r\n##### @yame example\r\n# Section overview\r\n# value: Value\r\n[example]\r\nvalue=8")
            assertContains(normalized, "unknown= keep \r\n; footer\r\n")
            assertEquals(1, events)
        }
    }

    @Test fun `dynamic sections relocate their shared structural description as rules change`() {
        val rulesPath = directory.resolve("element-simplifier.ini")
        Files.writeString(rulesPath, "# user heading\n[summary]\ntarget=p\n\n# article note\n[article]\ntarget=div\n")
        val store = lazyApplicationConfigStore(path)
        store.synchronizeDocumentation(ElementSimplifierSettings)
        val first = Files.readString(rulesPath)
        assertTrue(first.startsWith("# user heading\n##### @yame element-simplifier settings:"))
        val reorderedRules = no.skasti.yame.ppp.proxy.transform.ElementSimplifierConfiguration(linkedMapOf(
            "third" to ElementSimplifierRule("span"), "article" to ElementSimplifierRule("div")))
        store.set(ElementSimplifierSettings.Rules to reorderedRules)
        val changed = Files.readString(rulesPath)
        assertContains(changed, "# article note\n##### @yame element-simplifier settings:")
        assertTrue(changed.indexOf("##### @yame element-simplifier settings:") < changed.indexOf("[article]"))
        assertEquals(listOf("article", "third"), IniParser.parse(changed).sections())
        assertEquals(1, changed.lineSequence().count { it == "# HTML element simplification rules." })
        assertEquals(1, changed.lineSequence().count { it == "##### @yame element-simplifier settings:" })
        store.synchronizeDocumentation(ElementSimplifierSettings)
        assertEquals(changed, Files.readString(rulesPath))
        assertEquals(reorderedRules, lazyApplicationConfigStore(path)[ElementSimplifierSettings.Rules])
        store.set(ElementSimplifierSettings.Rules to no.skasti.yame.ppp.proxy.transform.ElementSimplifierConfiguration(emptyMap()))
        assertFalse(Files.readString(rulesPath).contains("##### @yame element-simplifier settings:"))
        assertContains(Files.readString(rulesPath), "# article note")
    }

}
