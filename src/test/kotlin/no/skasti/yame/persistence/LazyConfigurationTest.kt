package no.skasti.yame.persistence

import no.skasti.yame.config.*
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.modem.ModemSettings
import no.skasti.yame.ppp.PppIpConfig
import no.skasti.yame.ppp.PppSettings
import no.skasti.yame.ppp.dns.PppDnsConfig
import no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig
import no.skasti.yame.ppp.proxy.transform.ElementSimplifierSettings
import no.skasti.yame.ppp.proxy.transform.HtmlTransformerSettings
import no.skasti.yame.ppp.proxy.transform.TransformerSettings
import no.skasti.yame.serial.SerialSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class LazyConfigurationTest {
    @TempDir lateinit var directory: Path

    @Test fun `reading an owned key loads only its definition and only once`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "[used]\nvalue=7\n[unused]\nvalue=9\n")
        class Definition(id: String) : Settings(id) {
            val group = SettingGroup(id, this)
            val key = SettingKey<Int>("$id.value", group)
            override val bindings by lazy { listOf(IniBinding(key, "value", { 0 }, String::toInt, Int::toString)) }
        }
        val used = Definition("used")
        val unused = Definition("unused")
        val delegate = IniConfigurationRepository(path)
        val loads = AtomicInteger()
        val repository = object : ConfigurationRepository by delegate {
            override fun load(definition: Settings): Pair<List<ConfigChange<*>>, Set<Pair<String, String>>> {
                loads.incrementAndGet()
                return delegate.load(definition)
            }
        }
        val store = ConfigStore(emptyList(), repository = repository)
        assertEquals(0, loads.get())
        assertEquals(7, store[used.key])
        assertEquals(7, store[used.key])
        assertEquals(1, loads.get())
        assertEquals(1, loads.get())
        assertEquals(9, store[unused.key])
        assertEquals(2, loads.get())
    }

    @Test fun `an explicitly populated store can read an owned key without a repository`() {
        val store = ConfigStore(listOf(ModemSettings.Tone to false))
        assertFalse(store[ModemSettings.Tone])
    }

    @Test fun `component factories read their keys from a fresh lazy store without global installation`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, """
            modem.tone-simulation-enabled=false
            ppp.subnet=10.1.1.0/30
            dns.upstream=1.1.1.1
            http-https-proxy.enabled=false
            transformers.script-remover.enabled=false
        """.trimIndent())
        val store = lazyApplicationConfigStore(path)

        assertFalse(HayesModemConfig.fromConfigStore(store).toneSimulationEnabled)
        assertEquals("10.1.1.0/30", PppIpConfig.fromConfigStore(store).configuredSubnet.toString())
        assertEquals("1.1.1.1", PppDnsConfig.fromConfigStore(store).upstreamServer.toString())
        assertFalse(HtmlTransformerSettings.fromConfigStore(store).scriptRemover)
        val http = PppHttpCompatibilityConfig.fromConfigStore(store)
        assertFalse(http.enabled)
        assertFalse(http.htmlTransformers.scriptRemover)

        assertFailsWith<IllegalStateException> { ConfigStore.current }
        assertEquals("10.2.2.0/30", PppIpConfig(no.skasti.yame.ppp.ip.Ipv4Cidr.parse("10.2.2.0/30")).configuredSubnet.toString())
        assertEquals("9.9.9.9", PppDnsConfig(no.skasti.yame.ppp.ip.Ipv4Address.parse("9.9.9.9")).upstreamServer.toString())
        assertFalse(HayesModemConfig(toneSimulationEnabled = false).toneSimulationEnabled)
        assertFalse(PppHttpCompatibilityConfig(enabled = false, htmlTransformers = HtmlTransformerSettings(scriptRemover = false)).htmlTransformers.scriptRemover)
    }

    @Test fun `concurrent first reads load one definition once`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "modem.tone-simulation-enabled=false\n")
        val delegate = IniConfigurationRepository(path)
        val loads = AtomicInteger()
        val repository = object : ConfigurationRepository by delegate {
            override fun load(definition: Settings): Pair<List<ConfigChange<*>>, Set<Pair<String, String>>> {
                loads.incrementAndGet()
                return delegate.load(definition)
            }
        }
        val store = ConfigStore(emptyList(), repository = repository)
        val start = CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = List(8) { thread {
            try {
                start.await()
                assertFalse(store[ModemSettings.Tone])
            } catch (error: Throwable) { failures.add(error) }
        } }
        start.countDown()
        workers.forEach { it.join(5_000); assertFalse(it.isAlive) }
        assertTrue(failures.isEmpty(), failures.toString())
        assertEquals(1, loads.get())
    }

    @Test fun `CLI leaves HTTP transformations unloaded until the HTTP owner requests them`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "serial.baud=57600\ntransformers.script-remover.enabled=false\n")
        Files.writeString(directory.resolve("element-simplifier.ini"), "[heading]\ntarget=h2\n")
        val store = lazyApplicationConfigStore(path)
        assertEquals(57600, cliConfiguration(store).baudRate)
        var transformersLoaded = false
        var rulesLoaded = false
        store.onChange[TransformerSettings.Group].subscribe(initial = true) { transformersLoaded = true }.close()
        store.onChange[ElementSimplifierSettings.Group].subscribe(initial = true) { rulesLoaded = true }.close()
        assertFalse(transformersLoaded)
        assertFalse(rulesLoaded)

        val http = no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig.fromConfigStore(store)
        assertFalse(http.htmlTransformers.scriptRemover)
        assertEquals("h2", http.elementSimplifier.rules.getValue("heading").target)
    }

    @Test fun `CLI overrides keep precedence while later feature reads retain saved values`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "serial.baud=57600\ntransformers.script-remover.enabled=false\n")
        val store = lazyApplicationConfigStore(path)
        val saved = cliConfiguration(store)
        applyCliOverrides(store, saved, saved.copy(baudRate = 9600))
        assertEquals(9600, store[SerialSettings.Baud])
        var transformersLoaded = false
        store.onChange[TransformerSettings.Group].subscribe(initial = true) { transformersLoaded = true }.close()
        assertFalse(transformersLoaded)
        assertFalse(store[TransformerSettings.Script])
        assertTrue(Files.readString(path).contains("serial.baud=57600"))
    }

    @Test fun `tone test reads no PPP DNS or proxy definitions`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "serial.baud=57600\nmodem.tone-simulation-enabled=false\nppp.subnet=invalid\n")
        val store = lazyApplicationConfigStore(path)
        val options = cliConfiguration(store, toneTest = true)
        assertEquals(57600, options.baudRate)
        assertFalse(options.modemConfig.toneSimulationEnabled)
        var pppLoaded = false
        var transformersLoaded = false
        store.onChange[PppSettings.Group].subscribe(initial = true) { pppLoaded = true }.close()
        store.onChange[TransformerSettings.Group].subscribe(initial = true) { transformersLoaded = true }.close()
        assertFalse(pppLoaded)
        assertFalse(transformersLoaded)
    }

    @Test fun `only requested definitions load and late load preserves saved changes`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "# original\nmodem.tone-simulation-enabled=false\nppp.subnet=10.1.1.0/30\nunknown.keep=value\n")
        val store = lazyApplicationConfigStore(path)
        store.ensureLoaded(ModemSettings)
        var pppLoaded = false
        store.onChange[PppSettings.Group].subscribe(initial = true) { pppLoaded = true }.close()
        assertFalse(pppLoaded)
        assertFalse(HayesModemConfig.fromConfigStore(store).toneSimulationEnabled)
        store.set(ModemSettings.Tone to true)
        assertTrue(Files.readString(path).contains("ppp.subnet=10.1.1.0/30"))
        assertTrue(Files.readString(path).contains("# original"))
        store.ensureLoaded(PppSettings)
        assertEquals("10.1.1.0/30", store[PppSettings.Subnet].toString())
        store.set(PppSettings.Subnet to null)
        assertEquals(true, store[ModemSettings.Tone])
        assertTrue(Files.readString(path).contains("unknown.keep=value"))
    }

    @Test fun `saving one key leaves external edits to another key in the same definition alone`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "serial.baud=38400\nserial.port=COM1\n")
        val store = lazyApplicationConfigStore(path)
        store.ensureLoaded(SerialSettings)
        Files.writeString(path, "serial.baud=38400\nserial.port=COM2\n")
        store.set(SerialSettings.Baud to 57600)
        assertEquals("COM1", store[SerialSettings.Port]) // In-memory values refresh on restart only.
        assertTrue(Files.readString(path).contains("serial.port=COM2"))
    }

    @Test fun `missing module is untouched and malformed load retries without partial state`() {
        val path = directory.resolve("yame.ini")
        val stray = directory.resolve("stray.ini")
        Files.writeString(stray, "[general]\nenabled=maybe\n")
        Files.writeString(path, "serial.baud=bad\n")
        val store = lazyApplicationConfigStore(path)
        assertFailsWith<IllegalArgumentException> { store[SerialSettings.Baud] }
        var serialLoaded = false
        store.onChange[SerialSettings.Group].subscribe(initial = true) { serialLoaded = true }.close()
        assertFalse(serialLoaded)
        Files.writeString(path, "serial.baud=57600\n")
        assertEquals(57600, store[SerialSettings.Baud])
        Files.writeString(path, "serial.baud=9600\n")
        store.ensureLoaded(SerialSettings)
        assertEquals(57600, store[SerialSettings.Baud])
    }

    @Test fun `dynamic legacy rule sections round trip with static root file`() {
        val path = directory.resolve("element-simplifier.ini")
        assertEquals("element-simplifier", ElementSimplifierSettings.file)
        assertEquals(path, IniConfigurationRepository(directory.resolve("yame.ini")).file(ElementSimplifierSettings))
        Files.writeString(path, "; keep\n[heading]\ntarget=h1\nstyle=color:red;\n")
        val store = lazyApplicationConfigStore(directory.resolve("yame.ini"))
        store.ensureLoaded(ElementSimplifierSettings)
        val rules = store[ElementSimplifierSettings.Rules]
        assertEquals("h1", rules.rules.getValue("heading").target)
        store.set(ElementSimplifierSettings.Rules to no.skasti.yame.ppp.proxy.transform.ElementSimplifierConfiguration(mapOf("heading" to no.skasti.yame.ppp.proxy.transform.ElementSimplifierRule("h2"))))
        assertTrue(Files.readString(path).contains("; keep"))
        assertEquals("h2", store[ElementSimplifierSettings.Rules].rules.getValue("heading").target)
    }

    @Test fun `concurrent first use installs one consistent definition`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "modem.tone-simulation-enabled=false\n")
        val store = lazyApplicationConfigStore(path)
        val start = CountDownLatch(1)
        val workers = List(8) { thread { start.await(); store.ensureLoaded(ModemSettings) } }
        start.countDown()
        workers.forEach(Thread::join)
        assertFalse(store[ModemSettings.Tone])
        var modemKeyCount = 0
        store.onChange[ModemSettings.Group].subscribe(initial = true) { modemKeyCount = it.count() }.close()
        assertEquals(5, modemKeyCount)
    }

    @Test fun `new definitions share an INI without central registration`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "[first]\nvalue=7\n[second]\nvalue=9\n")
        class TestDefinition(id: String) : Settings(id) {
            val group = SettingGroup(id, this)
            val key = SettingKey<Int>("$id.value", group)
            override val bindings by lazy { listOf(IniBinding(key, "value", { 0 }, String::toInt, Int::toString)) }
        }
        val first = TestDefinition("first")
        val second = TestDefinition("second")
        val store = lazyApplicationConfigStore(path)
        store.ensureLoaded(first)
        var secondLoaded = false
        store.onChange[second.group].subscribe(initial = true) { secondLoaded = true }.close()
        assertFalse(secondLoaded)
        store.set(first.key to 8)
        store.ensureLoaded(second)
        assertEquals(9, store[second.key])
        assertTrue(Files.readString(path).contains("[second]\nvalue=9"))
        val overlap = TestDefinition("first")
        assertFailsWith<IllegalArgumentException> { store.ensureLoaded(overlap) }
    }

    @Test fun `logical names resolve next to an explicit primary file and can be shared`() {
        val primary = directory.resolve("custom.ini")
        val shared = directory.resolve("shared-settings.ini")
        val primaryText = "serial.baud=57600\n# primary comment\n"
        Files.writeString(primary, primaryText)
        Files.writeString(shared, "# shared comment\n[shared]\nfirst=7\nsecond=9\nother=untouched\n")
        class SharedDefinition(id: String, property: String) : Settings(id, file = "shared-settings") {
            val group = SettingGroup(id, this, "shared")
            val key = SettingKey<Int>("$id.value", group)
            override val bindings by lazy { listOf(IniBinding(key, property, { 0 }, String::toInt, Int::toString)) }
        }
        val first = SharedDefinition("first-owner", "first")
        val second = SharedDefinition("second-owner", "second")
        val repository = IniConfigurationRepository(primary)
        assertEquals(primary, repository.file(SerialSettings))
        assertEquals(shared, repository.file(first))
        assertEquals(shared, repository.file(second))

        val store = lazyApplicationConfigStore(primary)
        assertEquals(57600, store[SerialSettings.Baud])
        assertEquals(7, store[first.key])
        assertEquals(9, store[second.key])
        store.set(first.key to 8, second.key to 10)
        assertEquals(primaryText, Files.readString(primary))
        val saved = Files.readString(shared)
        for (record in listOf("# shared comment", "first=8", "second=10", "other=untouched")) {
            assertTrue(saved.contains(record), record)
        }

        val conflict = SharedDefinition("third-owner", "first")
        val error = assertFailsWith<IllegalArgumentException> { store[conflict.key] }
        assertTrue(error.message.orEmpty().contains("INI property"), error.message)
        assertEquals(8, store[first.key])
    }

    @Test fun `logical file names reject paths extensions and invalid characters`() {
        for (name in listOf("", ".", "..", "../escape", "nested/file", "nested\\file", "/absolute",
                "name.ini", "with space", "a:b", "æøå")) {
            val error = assertFailsWith<IllegalArgumentException> {
                object : Settings("owner", file = name) {
                    override val bindings: List<IniBinding<*>> = emptyList()
                }
            }
            assertTrue(error.message.orEmpty().contains("Invalid logical configuration file name"), name)
        }
        assertFailsWith<IllegalArgumentException> {
            object : SeparateSettings("../escape") {
                override val bindings: List<IniBinding<*>> = emptyList()
            }
        }
    }

    @Test fun `invalid dynamic rule does not partially register`() {
        val path = directory.resolve("element-simplifier.ini")
        Files.writeString(path, "[heading]\nstyle=color:red\n")
        val store = lazyApplicationConfigStore(directory.resolve("yame.ini"))
        assertFailsWith<IllegalArgumentException> { store.ensureLoaded(ElementSimplifierSettings) }
        var rulesLoaded = false
        store.onChange[ElementSimplifierSettings.Group].subscribe(initial = true) { rulesLoaded = true }.close()
        assertFalse(rulesLoaded)
    }

    @Test fun `legacy files load the same effective values through the lazy store`() {
        val path = directory.resolve("yame.ini")
        val expected = YameConfiguration(
            baudRate = 57600,
            modemConfig = HayesModemConfig(username = "user", password = "s:e=cret\\value", toneSimulationEnabled = false),
            pppHttpCompatibilityConfig = no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig(enabled = false),
        )
        YameConfigurationStore(path).save(expected)
        assertEquals(YameConfigurationStore(path).load(), cliConfiguration(lazyApplicationConfigStore(path)))
    }

    @Test fun `a later file failure reports partial disk replacement without memory publication`() {
        val first = object : Settings("first") {
            val group = SettingGroup("first", this, "first")
            val key = SettingKey<Int>("first.value", group)
            override val bindings by lazy { listOf(IniBinding(key, "value", { 1 }, String::toInt, Int::toString)) }
        }
        val second = object : SeparateSettings("second") {
            val group = SettingGroup("second", this, "second")
            val key = SettingKey<Int>("second.value", group)
            override val bindings by lazy { listOf(IniBinding(key, "value", { 2 }, String::toInt, Int::toString)) }
        }
        val primary = directory.resolve("yame.ini")
        val store = lazyApplicationConfigStore(primary)
        store.ensureLoaded(first)
        store.ensureLoaded(second)
        Files.createDirectory(directory.resolve("second.ini"))
        val error = assertFailsWith<PartialIniWriteException> {
            store.set(first.key to 3, second.key to 4)
        }
        assertEquals(listOf(primary), error.replacedFiles)
        assertEquals(1, store[first.key])
        assertEquals(2, store[second.key])
        assertTrue(Files.readString(primary).contains("value=3"))
    }

    @Test fun `failed save does not publish changes`() {
        val path = directory.resolve("yame.ini")
        val store = lazyApplicationConfigStore(path)
        store.ensureLoaded(SerialSettings)
        Files.createDirectory(path)
        assertFails { store.set(SerialSettings.Baud to 9600) }
        assertEquals(38_400, store[SerialSettings.Baud])
    }
}
