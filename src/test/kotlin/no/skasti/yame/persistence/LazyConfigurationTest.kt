package no.skasti.yame.persistence

import no.skasti.yame.config.withInstalledConfig
import no.skasti.yame.config.*
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.modem.ModemSettings
import no.skasti.yame.modem.LoginCredentials
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
            val key = SettingKey<Int>("value", group, default = { 0 })
        }
        val used = Definition("used")
        val unused = Definition("unused")
        val delegate = IniConfigurationRepository(path)
        val loads = AtomicInteger()
        val repository = object : ConfigurationRepository by delegate {
            override fun load(definition: Settings): ConfigurationLoad {
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
            [modem]
            tone-simulation-enabled=false
            [ppp]
            subnet=10.1.1.0/30
            [dns]
            upstream=1.1.1.1
            [proxy]
            enabled=false
            [transformers]
            script-remover-enabled=false
        """.trimIndent())
        val store = lazyApplicationConfigStore(path)

        assertFalse(withInstalledConfig(store) { HayesModemConfig.fromConfigStore() }.toneSimulationEnabled)
        assertEquals("10.1.1.0/30", withInstalledConfig(store) { PppIpConfig.fromConfigStore() }.configuredSubnet.toString())
        assertEquals("1.1.1.1", withInstalledConfig(store) { PppDnsConfig.fromConfigStore() }.upstreamServer.toString())
        assertFalse(withInstalledConfig(store) { HtmlTransformerSettings.fromConfigStore() }.scriptRemover)
        val http = withInstalledConfig(store) { PppHttpCompatibilityConfig.fromConfigStore() }
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
            override fun load(definition: Settings): ConfigurationLoad {
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

    @Test fun `equivalent section and flat values agree while conflicts identify the property`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "serial.baud=57600\n[serial]\nbaud=57600\n")
        val store = lazyApplicationConfigStore(path)
        assertEquals(57_600, store[SerialSettings.Baud])
        store.set(SerialSettings.Baud to 115_200)
        val written = Files.readString(path)
        assertTrue(written.contains("[serial]\nbaud=115200"))
        assertFalse(written.contains("serial.baud="))

        Files.writeString(path, "serial.baud=9600\n[serial]\nbaud=57600\n")
        val error = assertFailsWith<IllegalArgumentException> {
            lazyApplicationConfigStore(path)[SerialSettings.Baud]
        }
        assertTrue(error.message.orEmpty().contains("yame.ini"))
        assertTrue(error.message.orEmpty().contains("serial.baud"))
    }

    @Test fun `lowercase declared names read case insensitive section syntax and write canonical paths`() {
        val path = directory.resolve("yame.ini")
        val feature = object : Settings("feature") {
            val group = SettingGroup("feature.options_2", this)
            val key = SettingKey<Int>("max-rate_2", group, default = { 1 })
        }
        Files.writeString(path, "[ FEATURE.OPTIONS_2 ]\nMAX-RATE_2=7\n")
        val store = lazyApplicationConfigStore(path)
        assertEquals("feature.options_2.max-rate_2", feature.key.path)
        assertEquals(7, store[feature.key])
        store.set(feature.key to 9)
        assertTrue(Files.readString(path).contains("max-rate_2=9"))
        assertEquals(9, lazyApplicationConfigStore(path)[feature.key])
    }

    @Test fun `string settings preserve exact whitespace through section load save and reload`() {
        val path = directory.resolve("yame.ini")
        val original = "# keep\nfuture.value=  untouched  \n  \t  \n" +
            "[modem]\nlogin.username=user\nlogin.password= secret \n" +
            "[serial]\nport=\t COM  1\\path=x:y\t \n"
        Files.writeString(path, original)
        val store = lazyApplicationConfigStore(path)
        assertEquals(" secret ", store[ModemSettings.Login].password)
        assertEquals("\t COM  1\\path=x:y\t ", store[SerialSettings.Port])

        store.set(ModemSettings.Login to LoginCredentials("user", "temporary"))
        store.set(ModemSettings.Login to LoginCredentials("user", " secret "))
        assertEquals(" secret ", lazyApplicationConfigStore(path)[ModemSettings.Login].password)

        val credentials = LoginCredentials("user", "  new =: \\secret  ")
        val port = "  COM  2\t=mode:fast\\path  "
        store.set(ModemSettings.Login to credentials, SerialSettings.Port to port)
        val written = Files.readString(path)
        assertTrue(written.startsWith("# keep\nfuture.value=  untouched  \n  \t  \n"))
        assertTrue(written.contains("login.password=  new =: \\secret  \n"))
        assertTrue(written.contains("port=$port\n"))
        val reloaded = lazyApplicationConfigStore(path)
        assertEquals(credentials, reloaded[ModemSettings.Login])
        assertEquals(port, reloaded[SerialSettings.Port])
    }

    @Test fun `root escaped string values agree with sections and unequal logical values conflict`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "modem.login.password=\\ secret \n[modem]\n" +
            "login.username=user\nlogin.password= secret \n")
        val store = lazyApplicationConfigStore(path)
        assertEquals(" secret ", store[ModemSettings.Login].password)
        store.set(ModemSettings.Login to LoginCredentials("user", " new "))
        val written = Files.readString(path)
        assertFalse(written.contains("modem.login.password="))
        assertEquals(" new ", lazyApplicationConfigStore(path)[ModemSettings.Login].password)

        Files.writeString(path, "modem.login.password= secret \n[modem]\n" +
            "login.username=user\nlogin.password= secret \n")
        val error = assertFailsWith<IllegalArgumentException> {
            lazyApplicationConfigStore(path)[ModemSettings.Login]
        }
        assertTrue(error.message.orEmpty().contains("yame.ini"))
        assertTrue(error.message.orEmpty().contains("login.password"))
    }

    @Test fun `empty string is present while absent nullable property uses its default`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "[serial]\nport=\n")
        assertEquals("", lazyApplicationConfigStore(path)[SerialSettings.Port])
        Files.writeString(path, "[serial]\n")
        assertNull(lazyApplicationConfigStore(path)[SerialSettings.Port])
    }

    @Test fun `numeric and duration values tolerate surrounding whitespace in both layouts`() {
        val path = directory.resolve("yame.ini")
        for (content in listOf(
            "[serial]\nbaud= 57600 \t\n[modem]\npickup-time= 500ms \t\n",
            "serial.baud= 57600 \t\nmodem.pickup-time= 500ms \t\n",
        )) {
            Files.writeString(path, content)
            val store = lazyApplicationConfigStore(path)
            assertEquals(57_600, store[SerialSettings.Baud])
            assertEquals(kotlin.time.Duration.parse("500ms"), store[ModemSettings.PickupTime])
        }
    }

    @Test fun `duration and reusable composite keys round trip in canonical sections`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "modem.login.username=alice\n[modem]\nlogin.password=secret\npickup-time=1.5s\n")
        val store = lazyApplicationConfigStore(path)
        assertEquals(LoginCredentials("alice", "secret"), store[ModemSettings.Login])
        assertEquals(kotlin.time.Duration.parse("1.5s"), store[ModemSettings.PickupTime])
        assertTrue(ModemSettings.Login in ModemSettings.compositeSettingKeys)
        assertTrue(ModemSettings.PickupTime in ModemSettings.settingKeys)
        store.set(ModemSettings.Login to LoginCredentials("bob", "other"),
            ModemSettings.PickupTime to kotlin.time.Duration.parse("2s"))
        val written = Files.readString(path)
        assertFalse(written.contains("modem.login.username="))
        assertTrue(written.contains("login.username=bob"))
        assertTrue(written.contains("login.password=other"))
        assertTrue(written.contains("pickup-time=2s"))

        val accounts = object : Settings("accounts") {
            val Group = SettingGroup(this)
            val Admin = SettingKey<LoginCredentials>("admin", Group, default = { LoginCredentials(null, null) })
            val User = SettingKey<LoginCredentials>("user", Group, default = { LoginCredentials(null, null) })
        }
        Files.writeString(path, "accounts.admin.username=a\n[accounts]\nadmin.password=b\nuser.username=c\nuser.password=d\n")
        val reused = lazyApplicationConfigStore(path)
        assertEquals(LoginCredentials("a", "b"), reused[accounts.Admin])
        assertEquals(LoginCredentials("c", "d"), reused[accounts.User])
        assertEquals(2, accounts.compositeSettingKeys.size)
        reused.set(accounts.Admin to LoginCredentials("new", "secret"),
            accounts.User to LoginCredentials("other", "password"))
        val normalized = Files.readString(path)
        assertFalse(normalized.contains("accounts.admin.username="))
        for (child in listOf("admin.username=new", "admin.password=secret",
            "user.username=other", "user.password=password")) {
            assertTrue(normalized.contains(child), child)
        }
    }

    @Test fun `readable durations round trip and malformed duration reports file and property`() {
        val path = directory.resolve("yame.ini")
        val durations = object : Settings("durations") {
            val Group = SettingGroup(this)
            val Interval = SettingKey<kotlin.time.Duration>("interval", Group,
                default = { kotlin.time.Duration.ZERO })
        }
        for (duration in listOf("500ms", "2s", "1.5s", "2h 5m")) {
            Files.writeString(path, "[durations]\ninterval=$duration\n")
            val store = lazyApplicationConfigStore(path)
            assertEquals(kotlin.time.Duration.parse(duration), store[durations.Interval])
            store.set(durations.Interval to kotlin.time.Duration.parse("3s"))
            assertTrue(Files.readString(path).contains("interval=3s"))
        }
        Files.writeString(path, "[durations]\ninterval=invalid\n")
        val error = assertFailsWith<IllegalArgumentException> {
            lazyApplicationConfigStore(path)[durations.Interval]
        }
        assertTrue(error.message.orEmpty().contains("yame.ini"))
        assertTrue(error.message.orEmpty().contains("interval"))
    }

    @Test fun `CLI leaves HTTP transformations unloaded until the HTTP owner requests them`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "serial.baud=57600\ntransformers.script-remover-enabled=false\n")
        Files.writeString(directory.resolve("element-simplifier.ini"), "[heading]\ntarget=h2\n")
        val store = lazyApplicationConfigStore(path)
        withInstalledConfig(store) { no.skasti.yame.parseArgs(emptyArray()) }
        assertEquals(57600, store[SerialSettings.Baud])
        var transformersLoaded = false
        var rulesLoaded = false
        store.onChange[TransformerSettings.Group].subscribe(initial = true) { transformersLoaded = true }.close()
        store.onChange[ElementSimplifierSettings.Group].subscribe(initial = true) { rulesLoaded = true }.close()
        assertFalse(transformersLoaded)
        assertFalse(rulesLoaded)

        val http = withInstalledConfig(store) { no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig.fromConfigStore() }
        assertFalse(http.htmlTransformers.scriptRemover)
        assertEquals("h2", http.elementSimplifier.rules.getValue("heading").target)
    }

    @Test fun `CLI overrides keep precedence while later feature reads retain saved values`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "serial.baud=57600\ntransformers.script-remover-enabled=false\n")
        val store = lazyApplicationConfigStore(path)
        withInstalledConfig(store) { no.skasti.yame.parseArgs(arrayOf("--baud", "9600")) }
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
        withInstalledConfig(store) { no.skasti.yame.parseArgs(arrayOf("--test-tone", "123")) }
        assertEquals(57600, store[SerialSettings.Baud])
        assertFalse(withInstalledConfig(store) { HayesModemConfig.fromConfigStore() }.toneSimulationEnabled)
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
        assertFalse(withInstalledConfig(store) { HayesModemConfig.fromConfigStore() }.toneSimulationEnabled)
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

    @Test fun `rule file enabled and live switch both control element simplification`() {
        val rulesFile = directory.resolve("element-simplifier.ini")
        Files.writeString(rulesFile, "[element-simplifier.general]\nenabled=false\n[main]\ntarget=div\n")
        val store = lazyApplicationConfigStore(directory.resolve("yame.ini"))
        assertFalse(store[ElementSimplifierSettings.Enabled])
        assertEquals("div", store[ElementSimplifierSettings.Rules].rules.getValue("main").target)
        assertFalse(withInstalledConfig(store) { HtmlTransformerSettings.fromConfigStore() }.elementSimplifier)

        store.set(ElementSimplifierSettings.Enabled to true)
        assertTrue(withInstalledConfig(store) { HtmlTransformerSettings.fromConfigStore() }.elementSimplifier)
        store.set(TransformerSettings.Elements to false)
        assertFalse(withInstalledConfig(store) { HtmlTransformerSettings.fromConfigStore() }.elementSimplifier)
        store.set(ElementSimplifierSettings.Enabled to false, TransformerSettings.Elements to true)
        assertFalse(withInstalledConfig(store) { HtmlTransformerSettings.fromConfigStore() }.elementSimplifier)
        assertTrue(Files.readString(rulesFile).contains("enabled=false"))
        assertTrue(Files.readString(rulesFile).contains("[main]\ntarget=div"))
        assertFalse(withInstalledConfig(lazyApplicationConfigStore(directory.resolve("yame.ini"))) { HtmlTransformerSettings.fromConfigStore() }.elementSimplifier)
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
            val key = SettingKey<Int>("value", group, default = { 0 })
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
        val primaryText = "[serial]\nbaud=57600\n# primary comment\n"
        Files.writeString(primary, primaryText)
        Files.writeString(shared, "# shared comment\n[shared]\nfirst=7\nsecond=9\nother=untouched\n")
        class SharedDefinition(id: String, property: String) : Settings(id, file = "shared-settings") {
            val group = SettingGroup("shared", this)
            val key = SettingKey<Int>(property, group, default = { 0 })
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
        assertTrue(error.message.orEmpty().contains("Duplicate setting path shared.first"), error.message)
        assertEquals(8, store[first.key])
    }

    @Test fun `logical file names reject paths extensions and invalid characters`() {
        for (name in listOf("", ".", "..", "../escape", "nested/file", "nested\\file", "/absolute",
                "name.ini", "with space", "a:b", "æøå")) {
            val error = assertFailsWith<IllegalArgumentException> {
                object : Settings("owner", file = name) {
                }
            }
            assertTrue(error.message.orEmpty().contains("Invalid logical configuration file name"), name)
        }
        assertFailsWith<IllegalArgumentException> {
            object : SeparateSettings("../escape") {
            }
        }
    }

    @Test fun `invalid dynamic rule does not partially register`() {
        val path = directory.resolve("element-simplifier.ini")
        Files.writeString(path, "[heading]\ntarget=h1\n[broken]\nstyle=color:red\n")
        val store = lazyApplicationConfigStore(directory.resolve("yame.ini"))
        assertFailsWith<IllegalArgumentException> { store.ensureLoaded(ElementSimplifierSettings) }
        var rulesLoaded = false
        store.onChange[ElementSimplifierSettings.Group].subscribe(initial = true) { rulesLoaded = true }.close()
        assertFalse(rulesLoaded)
        Files.writeString(path, "[heading]\ntarget=h1\n[broken]\ntarget=p\n")
        assertEquals(listOf("heading", "broken"), store[ElementSimplifierSettings.Rules].rules.keys.toList())
    }

    @Test fun `existing section files load component values without migration`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, """
            version=1
            [serial]
            baud=57600
            [modem]
            login.username=user
            login.password=s:e=cret\value
            tone-simulation-enabled=false
            [proxy]
            enabled=false
        """.trimIndent())
        val before = Files.readAllBytes(path)
        val store = lazyApplicationConfigStore(path)
        withInstalledConfig(store) { no.skasti.yame.parseArgs(emptyArray()) }
        assertEquals(57600, store[SerialSettings.Baud])
        assertEquals(HayesModemConfig(username = "user", password = "s:e=cret\\value", toneSimulationEnabled = false),
            withInstalledConfig(store) { HayesModemConfig.fromConfigStore() })
        assertFalse(store[no.skasti.yame.ppp.proxy.ProxySettings.Enabled])
        assertContentEquals(before, Files.readAllBytes(path))
    }

    @Test fun `a later file failure reports partial disk replacement without memory publication`() {
        val first = object : Settings("first") {
            val group = SettingGroup("first", this)
            val key = SettingKey<Int>("value", group, default = { 1 })
        }
        val second = object : SeparateSettings("second") {
            val group = SettingGroup("second", this)
            val key = SettingKey<Int>("value", group, default = { 2 })
        }
        val primary = directory.resolve("yame.ini")
        val secondary = directory.resolve("second.ini")
        val repo = IniConfigurationRepository(primary) { temporary, destination ->
            if (destination == secondary) throw java.io.IOException("injected replacement failure")
            Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val store = ConfigStore(emptyList(), repository = repo)
        store.ensureLoaded(first)
        store.ensureLoaded(second)
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

    @Test fun `different keys share a section but cannot claim the same attribute in either syntax`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "shared.first=7\n[shared]\nsecond=9\n")
        class Owner(id: String, name: String) : Settings(id) {
            val group = SettingGroup("shared", this)
            val key = SettingKey<Int>(name, group, default = { 0 })
        }
        val first = Owner("first-owner", "first")
        val second = Owner("second-owner", "second")
        val store = lazyApplicationConfigStore(path)
        assertEquals(7, store[first.key])
        assertEquals(9, store[second.key])
        store.set(first.key to 8, second.key to 10)
        val text = Files.readString(path)
        assertTrue(text.contains("first=8"))
        assertTrue(text.contains("second=10"))
        assertFalse(text.contains("shared.first="))

        val conflicting = object : Settings("third-owner") {
            val group = SettingGroup("shared", this)
            val key = SettingKey<Int>("alias", group, default = { 0 }, structural = true)
            override fun loadFromIni(context: IniReadContext): List<ConfigChange<*>> = listOf(
                key to requireNotNull(context.compose(key, "shared") { attrs -> attrs["first"]?.toInt() }),
            )
        }
        val error = assertFailsWith<IllegalArgumentException> { store[conflicting.key] }
        assertTrue(error.message.orEmpty().contains("yame.ini [shared] first"), error.message)
        assertTrue(error.message.orEmpty().contains(first.key.path), error.message)
        assertEquals(8, store[first.key])
    }

    @Test fun `composition commits only nonnull claims including absent attributes`() {
        val doc = no.skasti.yame.config.ini.IniParser.parse("[tags]\ntarget=p\nother=keep\n", "rules.ini")
        val definition = object : Settings("test") {
            val group = SettingGroup(this)
            val selected = SettingKey<String>("selected", group, default = { "" }, structural = true)
            val other = SettingKey<String>("other", group, default = { "" }, structural = true)
        }
        val context = IniReadContext("rules.ini", doc, true)
        assertEquals(2, context.compose(definition.selected, "tags") { it.keys.size })
        assertTrue(context.claims().isEmpty())
        assertNull(context.compose<String, String>(definition.selected, "tags") { attrs ->
            assertEquals(setOf("target", "other"), attrs.keys)
            attrs["target"]
            null
        })
        assertTrue(context.claims().isEmpty())
        assertFailsWith<IllegalArgumentException> {
            context.compose<String, String>(definition.selected, "tags") { attrs -> attrs["target"]; error("bad rule") }
        }
        assertTrue(context.claims().isEmpty())
        assertEquals("p", context.compose(definition.selected, "tags") { attrs ->
            assertEquals(setOf("target", "other"), attrs.keys)
            assertEquals(null, attrs["style"])
            attrs["target"]
        })
        assertEquals(setOf("tags" to "target", "tags" to "style"), context.claims().keys)
        assertFailsWith<IllegalArgumentException> {
            context.compose(definition.other, "tags") { it["style"] ?: "fallback" }
        }
    }

    @Test fun `later load failure rolls back earlier key claims and allows retry`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "[shared]\nfirst=7\nsecond=bad\n")
        val first = object : Settings("first-owner") {
            val group = SettingGroup("shared", this)
            val key = SettingKey<Int>("first", group, default = { 0 })
            val second = SettingKey<Int>("second", group, default = { 0 })
        }
        val replacement = object : Settings("replacement-owner") {
            val group = SettingGroup("shared", this)
            val key = SettingKey<Int>("replacement", group, default = { 0 }, structural = true)
            override fun loadFromIni(context: IniReadContext): List<ConfigChange<*>> = listOf(
                key to requireNotNull(context.compose(key, "shared") { attrs -> attrs["first"]?.toInt() }),
            )
        }
        val store = lazyApplicationConfigStore(path)
        assertFailsWith<IllegalArgumentException> { store[first.key] }
        assertEquals(7, store[replacement.key])
        Files.writeString(path, "[shared]\nfirst=7\nsecond=9\n")
        val conflict = assertFailsWith<IllegalArgumentException> { store[first.key] }
        assertTrue(conflict.message.orEmpty().contains("conflicts with"))
        assertEquals(7, store[replacement.key])
    }

    @Test fun `candidate validation rejects staged ownership before installation`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "[shared]\nvalue=bad\n")
        val invalid = object : Settings("invalid") {
            val group = SettingGroup("shared", this)
            val key = SettingKey<String>("candidate", group, validateValue = { require(it != "bad") },
                default = { "ok" }, structural = true)
            override fun loadFromIni(context: IniReadContext): List<ConfigChange<*>> = listOf(
                key to requireNotNull(context.compose(key, "shared") { it["value"] }),
            )
        }
        val replacement = object : Settings("replacement") {
            val group = SettingGroup("shared", this)
            val key = SettingKey<String>("replacement", group, default = { "" }, structural = true)
            override fun loadFromIni(context: IniReadContext): List<ConfigChange<*>> = listOf(
                key to requireNotNull(context.compose(key, "shared") { it["value"] }),
            )
        }
        val store = lazyApplicationConfigStore(path)
        assertFailsWith<IllegalArgumentException> { store[invalid.key] }
        assertEquals("bad", store[replacement.key])
        assertEquals("[shared]\nvalue=bad\n", Files.readString(path))
    }

    @Test fun `unowned and differently owned attributes prevent whole section removal`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "[shared]\nfirst=7\nsecond=9\nunknown=keep\n")
        val first = object : Settings("first-owner") {
            val group = SettingGroup("shared", this)
            val key = SettingKey<Int>("first", group, default = { 0 })
            override fun saveToIni(context: IniWriteContext) {
                context.removeSection(key, "shared")
            }
        }
        val second = object : Settings("second-owner") {
            val group = SettingGroup("shared", this)
            val key = SettingKey<Int>("second", group, default = { 0 })
        }
        val store = lazyApplicationConfigStore(path)
        assertEquals(7, store[first.key])
        val before = Files.readString(path)
        assertFailsWith<IllegalArgumentException> { store.set(first.key to 8) }
        assertEquals(before, Files.readString(path))
        assertEquals(7, store[first.key])
        assertEquals(9, store[second.key])
        Files.writeString(path, before.replace("unknown=keep\n", ""))
        val sharedOnly = Files.readString(path)
        val error = assertFailsWith<IllegalArgumentException> { store.set(first.key to 8) }
        assertTrue(error.message.orEmpty().contains(second.key.path), error.message)
        assertEquals(sharedOnly, Files.readString(path))
    }

    @Test fun `key scoped writes cannot overwrite another key or composite child`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, "[modem]\nlogin.username=user\nlogin.password=secret\n")
        val trespasser = object : Settings("trespasser") {
            val group = SettingGroup("modem", this)
            val key = SettingKey<Int>("attempt", group, default = { 0 })
            override fun saveToIni(context: IniWriteContext) {
                context.writeAttributes(key, "modem") { it["login.password"] = "stolen" }
            }
        }
        val store = lazyApplicationConfigStore(path)
        assertEquals(LoginCredentials("user", "secret"), store[ModemSettings.Login])
        assertEquals(0, store[trespasser.key])
        val original = Files.readString(path)
        val error = assertFailsWith<IllegalArgumentException> { store.set(trespasser.key to 1) }
        assertTrue(error.message.orEmpty().contains("modem.login"), error.message)
        assertEquals(original, Files.readString(path))
        assertEquals(0, store[trespasser.key])

        val competingReader = object : Settings("competing-reader") {
            val group = SettingGroup("modem", this)
            val key = SettingKey<String>("alias", group, default = { "" }, structural = true)
            override fun loadFromIni(context: IniReadContext): List<ConfigChange<*>> = listOf(
                key to requireNotNull(context.compose(key, "modem") { it["login.password"] }),
            )
        }
        assertFailsWith<IllegalArgumentException> { store[competingReader.key] }
    }

    @Test fun `all documents validate before the first file is replaced`() {
        val primary = directory.resolve("yame.ini")
        val secondary = directory.resolve("secondary.ini")
        Files.writeString(primary, "[first]\nvalue=1\n")
        Files.writeString(secondary, "[second]\nvalue=2\n")
        val first = object : Settings("first") {
            val group = SettingGroup(this)
            val key = SettingKey<Int>("value", group, default = { 1 })
        }
        val second = object : SeparateSettings("secondary") {
            val group = SettingGroup("second", this)
            val key = SettingKey<Int>("value", group, default = { 2 })
            override fun saveToIni(context: IniWriteContext) {
                context.writeAttributes(key, "second") { it["value"] = "3" }
                error("validation failed")
            }
        }
        val store = lazyApplicationConfigStore(primary)
        assertEquals(1, store[first.key])
        assertEquals(2, store[second.key])
        assertFails { store.set(first.key to 3, second.key to 3) }
        assertEquals("[first]\nvalue=1\n", Files.readString(primary))
        assertEquals("[second]\nvalue=2\n", Files.readString(secondary))
        assertEquals(1, store[first.key])
        assertEquals(2, store[second.key])
    }

    @Test fun `saving rules preserves newly added sections that this store never claimed`() {
        val primary = directory.resolve("yame.ini")
        val path = directory.resolve("element-simplifier.ini")
        Files.writeString(path, "; keep\n[heading]\n# retain rule comment\ntarget=h1\n")
        val store = lazyApplicationConfigStore(primary)
        val rules = store[ElementSimplifierSettings.Rules]
        Files.writeString(path, Files.readString(path) + "[external]\ntarget=span\n")
        store.set(ElementSimplifierSettings.Rules to no.skasti.yame.ppp.proxy.transform.ElementSimplifierConfiguration(
            rules.rules + ("heading" to no.skasti.yame.ppp.proxy.transform.ElementSimplifierRule("h2"))))
        val written = Files.readString(path)
        assertTrue(written.contains("[external]\ntarget=span"))
        assertTrue(written.contains("[heading]\n# retain rule comment\ntarget=h2"))
        assertFalse(written.contains("rules="))
        assertEquals("span", lazyApplicationConfigStore(primary)[ElementSimplifierSettings.Rules]
            .rules.getValue("external").target)
    }

    @Test fun `unrelated named and root flat sections remain unclaimed during rule updates`() {
        val primary = directory.resolve("yame.ini")
        val path = directory.resolve("element-simplifier.ini")
        val original = "# keep\nfuture.meta.target=span\nfuture.meta.value=untouched\n" +
            "[future.meta]\n# foreign comment\nvalue=keep\ntarget=div\n" +
            "[heading]\ntarget=h1\nstyle=color:red;\n"
        Files.writeString(path, original)
        val store = lazyApplicationConfigStore(primary)
        val rules = store[ElementSimplifierSettings.Rules]
        assertEquals(listOf("heading"), rules.rules.keys.toList())
        store.set(ElementSimplifierSettings.Rules to no.skasti.yame.ppp.proxy.transform.ElementSimplifierConfiguration(
            mapOf("heading" to no.skasti.yame.ppp.proxy.transform.ElementSimplifierRule("h2", "color:red;"))))
        val saved = Files.readString(path)
        assertTrue(saved.contains(original.substringBefore("[heading]")))
        assertTrue(saved.contains("[heading]\ntarget=h2\nstyle=color:red;"))
        assertEquals(listOf("heading"), lazyApplicationConfigStore(primary)[ElementSimplifierSettings.Rules].rules.keys.toList())

        Files.writeString(path, "[future.meta]\nvalue=keep\n[heading]\ntarget=h1\nunknown=bad\n")
        val error = assertFailsWith<IllegalArgumentException> {
            lazyApplicationConfigStore(primary)[ElementSimplifierSettings.Rules]
        }
        assertTrue(error.message.orEmpty().contains("[heading]"), error.message)
        assertTrue(error.message.orEmpty().contains("unknown"), error.message)
    }

    @Test fun `lazy reads protect files even before sensitive keys are requested`() {
        val primary = directory.resolve("yame.ini")
        Files.writeString(primary, "[serial]\nbaud=57600\n[modem]\nlogin.username=user\nlogin.password=secret\n")
        if (!Files.getFileStore(primary).supportsFileAttributeView("posix")) return
        val public = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--")
        val private = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
        Files.setPosixFilePermissions(primary, public)
        val store = lazyApplicationConfigStore(primary)
        assertEquals(57_600, store[SerialSettings.Baud])
        assertEquals(private, Files.getPosixFilePermissions(primary))
        store.set(SerialSettings.Baud to 115_200)
        assertEquals(private, Files.getPosixFilePermissions(primary))
        assertTrue(Files.readString(primary).contains("login.password=secret"))

        val separate = object : SeparateSettings("future-secret") {
            val group = SettingGroup(this)
            val secret = SettingKey<String>("value", group, sensitive = true, default = { "" })
        }
        val extra = directory.resolve("future-secret.ini")
        Files.writeString(extra, "[future-secret]\nvalue=token\n")
        Files.setPosixFilePermissions(extra, public)
        assertEquals("token", store[separate.secret])
        assertEquals(private, Files.getPosixFilePermissions(extra))
    }

    @Test fun `obsolete rule deletion refuses unknown attributes but removes only owned sections`() {
        val primary = directory.resolve("yame.ini")
        val path = directory.resolve("element-simplifier.ini")
        Files.writeString(path, "[heading]\ntarget=h1\n[paragraph]\ntarget=p\n")
        val store = lazyApplicationConfigStore(primary)
        store[ElementSimplifierSettings.Rules]
        val replacement = no.skasti.yame.ppp.proxy.transform.ElementSimplifierConfiguration(
            mapOf("heading" to no.skasti.yame.ppp.proxy.transform.ElementSimplifierRule("h2")))
        Files.writeString(path, Files.readString(path).replace("[paragraph]\ntarget=p", "[paragraph]\ntarget=p\nunknown=keep"))
        val before = Files.readString(path)
        assertFailsWith<IllegalArgumentException> { store.set(ElementSimplifierSettings.Rules to replacement) }
        assertEquals(before, Files.readString(path))
        Files.writeString(path, before.replace("\nunknown=keep", ""))
        store.set(ElementSimplifierSettings.Rules to replacement)
        assertFalse(Files.readString(path).contains("[paragraph]"))
        assertEquals("h2", lazyApplicationConfigStore(primary)[ElementSimplifierSettings.Rules]
            .rules.getValue("heading").target)
    }

    @Test fun `root flat dynamic rule attributes normalize into owned sections`() {
        val primary = directory.resolve("yame.ini")
        val path = directory.resolve("element-simplifier.ini")
        Files.writeString(path, "# retain\nheading.target=h1\n[heading]\nstyle=color:red;\n")
        val store = lazyApplicationConfigStore(primary)
        val rules = store[ElementSimplifierSettings.Rules]
        assertEquals("h1", rules.rules.getValue("heading").target)
        store.set(ElementSimplifierSettings.Rules to no.skasti.yame.ppp.proxy.transform.ElementSimplifierConfiguration(
            rules.rules + ("heading" to no.skasti.yame.ppp.proxy.transform.ElementSimplifierRule("h2", "color:red;"))))
        val written = Files.readString(path)
        assertFalse(written.contains("heading.target="))
        assertTrue(written.contains("# retain"))
        assertTrue(written.contains("[heading]"))
        assertTrue(written.contains("target=h2"))
        assertEquals("h2", lazyApplicationConfigStore(primary)[ElementSimplifierSettings.Rules]
            .rules.getValue("heading").target)
    }
}
