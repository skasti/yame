package no.skasti.yame.config

import no.skasti.yame.persistence.IniConfigurationRepository
import no.skasti.yame.ppp.proxy.transform.ElementSimplifierSettings
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SettingGroupOwnershipTest {
    @TempDir lateinit var directory: Path

    private class Feature(id: String, file: String? = null, section: String = "shared", name: String = "value") : Settings(id, file) {
        val Group = SettingGroup(section, this)
        val Value = SettingKey<Int>(name, Group, default = { 1 })
        val Other = SettingKey<Int>("other", Group, default = { 2 })
    }

    private class RecordingRepository : ConfigurationRepository {
        val reads = mutableListOf<String>()
        val installed = mutableListOf<String>()
        val writes = mutableListOf<ConfigChanges>()
        override fun createFileIfMissing(definition: Settings) {}
        override fun load(definition: Settings): ConfigurationLoad {
            reads += definition.id
            return ConfigurationLoad(definition.allSettingKeys.map(::defaultEntry)) { installed += definition.id }
        }
        override fun persist(changes: Collection<ConfigChange<*>>, snapshot: ConfigChanges) { writes += snapshot }
        @Suppress("UNCHECKED_CAST")
        private fun defaultEntry(key: SettingKey<*>): ConfigChange<*> =
            (key as SettingKey<Any?>).let { it to it.default() }
    }

    @Test fun `distinct sections share the primary file and repeated group references are valid`() {
        val first = Feature("first", section = "first")
        val second = Feature("second", section = "second")
        val store = ConfigStore()
        store.ensureLoaded(first)
        store.ensureLoaded(first)
        store.ensureLoaded(second)
        store.set(first.Value to 3, first.Other to 4, second.Value to 5)
        assertEquals(3, store[first.Value])
        assertEquals(4, store[first.Other])
        assertEquals(5, store[second.Value])
    }

    @Test fun `conflicting logical sections fail before repository IO and leave no registered state`() {
        for (name in listOf("value", "different")) {
            val repository = RecordingRepository()
            val store = ConfigStore(repository = repository)
            val first = Feature("first")
            val rejected = Feature("rejected", name = name)
            assertEquals(1, store[first.Value])
            var events = 0
            store.onChange.subscribe { events++ }.use {
                store.setTransient(first.Value to 9)
                repeat(2) {
                    val error = assertFailsWith<IllegalArgumentException> { store.ensureLoaded(rejected) }
                    assertContains(error.message.orEmpty(), "[shared] in primary file")
                    assertContains(error.message.orEmpty(), "'first'")
                    assertContains(error.message.orEmpty(), "'rejected'")
                }
                assertEquals(listOf("first"), repository.reads)
                assertEquals(listOf("first"), repository.installed)
                assertEquals(9, store[first.Value])
                assertEquals(2, store[first.Other])
                assertEquals(1, events)
                assertTrue(repository.writes.isEmpty())
                store.saveLoadedValues()
                val baseline = repository.writes.single()
                assertEquals(1, baseline[first.Value]?.value)
                assertNull(baseline[rejected.Value])
                assertEquals(2, baseline.count())
                // Reusing the rejected definition ID proves it was never registered.
                val replacement = Feature("rejected", section = "available")
                assertEquals(1, store[replacement.Value])
                assertEquals(listOf("first", "rejected"), repository.installed)
                store.set(first.Value to 3)
                assertEquals(3, store[first.Value])
                assertEquals(2, events)
            }
        }
    }

    @Test fun `distinct but equal groups in one definition conflict before IO`() {
        val feature = object : Settings("ambiguous") {
            val First = SettingGroup("shared", this)
            val Second = SettingGroup("shared", this)
            val A = SettingKey<Int>("a", First, default = { 1 })
            val B = SettingKey<Int>("b", Second, default = { 2 })
        }
        assertEquals(feature.First, feature.Second) // Structural equality must not confer ownership.
        assertNotSame(feature.First, feature.Second)
        val repository = RecordingRepository()
        val store = ConfigStore(repository = repository)
        val error = assertFailsWith<IllegalArgumentException> { store.ensureLoaded(feature) }
        assertContains(error.message.orEmpty(), "[shared] in primary file")
        assertContains(error.message.orEmpty(), "'ambiguous' and 'ambiguous'")
        assertTrue(repository.reads.isEmpty())
        assertTrue(repository.installed.isEmpty())
        assertEquals(1, store[Feature("ambiguous").Value])
    }

    @Test fun `identical paths in different logical files load and persist independently`() {
        val primary = directory.resolve("custom.ini")
        val first = Feature("primary")
        val second = Feature("secondary", file = "secondary")
        val third = object : SeparateSettings("third") {
            val Group = SettingGroup("shared", this)
            val Value = SettingKey<Int>("value", Group, default = { 7 })
        }
        val store = ConfigStore(repository = IniConfigurationRepository(primary))
        assertEquals(1, store[first.Value])
        assertEquals(1, store[second.Value])
        assertEquals(7, store[third.Value])
        store.set(first.Value to 3, second.Value to 4, third.Value to 5)
        val reloaded = ConfigStore(repository = IniConfigurationRepository(primary))
        assertEquals(3, reloaded[first.Value])
        assertEquals(4, reloaded[second.Value])
        assertEquals(5, reloaded[third.Value])
        val conflict = Feature("conflict", file = "secondary", name = "different")
        val error = assertFailsWith<IllegalArgumentException> { store.ensureLoaded(conflict) }
        assertContains(error.message.orEmpty(), "logical file 'secondary'")
        assertContains(error.message.orEmpty(), "'secondary' and 'conflict'")
    }

    @Test fun `structural mappings retain physical claim validation independently of declared groups`() {
        Files.writeString(directory.resolve("element-simplifier.ini"), "[article]\ntarget=p\n[summary]\ntarget=span\n")
        val store = ConfigStore(repository = IniConfigurationRepository(directory.resolve("custom.ini")))
        val rules = store[ElementSimplifierSettings.Rules]
        assertEquals("p", rules.rules.getValue("article").target)
        assertEquals("span", rules.rules.getValue("summary").target)
        val competitor = object : Settings("competitor", file = "element-simplifier") {
            val Group = SettingGroup("article", this)
            val Target = SettingKey<String>("target", Group, default = { "div" })
        }
        val error = assertFailsWith<IllegalArgumentException> { store[competitor.Target] }
        assertContains(error.message.orEmpty(), "[article] target")
        assertContains(error.message.orEmpty(), "conflicts with")
        assertEquals(rules, store[ElementSimplifierSettings.Rules])
    }

    @Test fun `concurrent loads cannot bypass exclusive ownership`() {
        val repository = RecordingRepository()
        val store = ConfigStore(repository = repository)
        val features = listOf(Feature("first"), Feature("second", name = "different"))
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = features.map { feature -> executor.submit<Throwable?> {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                runCatching { store.ensureLoaded(feature) }.exceptionOrNull()
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = tasks.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == null })
            assertEquals(1, results.count { it is IllegalArgumentException })
            assertEquals(1, repository.reads.size)
            assertEquals(repository.reads, repository.installed)
            val winner = features[results.indexOf(null)]
            assertEquals(1, store[winner.Value])
            store.set(winner.Value to 3)
            assertEquals(3, store[winner.Value])
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
}
