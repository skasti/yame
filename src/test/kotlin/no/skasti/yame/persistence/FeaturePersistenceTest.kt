package no.skasti.yame.persistence

import no.skasti.yame.config.withInstalledConfig
import no.skasti.yame.config.*

import no.skasti.yame.serial.SerialSettings
import no.skasti.yame.modem.ModemSettings
import no.skasti.yame.modem.LoginCredentials
import no.skasti.yame.ppp.proxy.transform.TransformerSettings
import no.skasti.yame.ppp.proxy.transform.ElementSimplifierSettings
import no.skasti.yame.ppp.proxy.transform.HtmlTransformerSettings
import no.skasti.yame.logging.LogSettings
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.logging.YameLogLevel
import java.nio.file.Files
import kotlin.test.*

class FeaturePersistenceTest {
    @Test fun `separate rule file switch is persisted by its owner`() {
        val directory = Files.createTempDirectory("yame-rule-switch")
        try {
            val path = directory.resolve("yame.ini")
            val store = lazyApplicationConfigStore(path)
            assertTrue(withInstalledConfig(store) { HtmlTransformerSettings.fromConfigStore() }.elementSimplifier)
            store.set(ElementSimplifierSettings.Enabled to false)
            assertFalse(withInstalledConfig(lazyApplicationConfigStore(path)) { HtmlTransformerSettings.fromConfigStore() }.elementSimplifier)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `CLI overrides and runtime discovery do not leak into later persistent changes`() {
        var saved = ConfigChanges(emptyList())
        var saves = 0
        val store = ConfigStore(persist = { saved = it; saves++ })
        withInstalledConfig(store) { no.skasti.yame.parseArgs(arrayOf("--baud", "115200", "--username", "cli", "--password", "secret")) }
        store.setTransient(SerialSettings.Port to "COM2")
        store.set(TransformerSettings.Style to false, LogSettings.Levels.getValue(YameLogModule.MODEM) to YameLogLevel.DEBUG)
        assertEquals(1, saves)
        assertEquals(38400, saved[SerialSettings.Baud]?.value)
        assertNull(saved[SerialSettings.Port]?.value)
        assertNull(saved[ModemSettings.Login]?.value?.password)
        assertEquals(115200, store[SerialSettings.Baud])
        assertEquals("COM2", store[SerialSettings.Port])
        store.set(SerialSettings.Baud to 115200)
        assertEquals(2, saves)
        assertEquals(115200, saved[SerialSettings.Baud]?.value)
    }

    @Test fun `feature changes autosave one compatible file snapshot and invalid credentials reject entire batch`() {
        val directory = Files.createTempDirectory("yame-store-test")
        try {
            val path = directory.resolve("yame.ini")
            val store = lazyApplicationConfigStore(path)
            store.set(SerialSettings.Baud to 9600, ModemSettings.Login to LoginCredentials("user", "secret"), TransformerSettings.Script to false)
            val loaded = lazyApplicationConfigStore(path)
            assertEquals(9600, loaded[SerialSettings.Baud])
            assertEquals("secret", loaded[ModemSettings.Login].password)
            assertFalse(loaded[TransformerSettings.Script])
            val bytes = Files.readAllBytes(path)
            assertFailsWith<IllegalArgumentException> {
                store.set(SerialSettings.Baud to 19200, ModemSettings.Login to LoginCredentials("user", null))
            }
            assertContentEquals(bytes, Files.readAllBytes(path))
            assertEquals(9600, store[SerialSettings.Baud])
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `cross feature transaction persists independently owned keys`() {
        val directory = Files.createTempDirectory("yame-bindings")
        try {
            val path = directory.resolve("yame.ini")
            val store = lazyApplicationConfigStore(path)
            val changes = listOf(
                SerialSettings.Port to "COM4",
                SerialSettings.Baud to 19200,
                SerialSettings.FlowControl to no.skasti.yame.serial.SerialFlowControl.HARDWARE,
                ModemSettings.PickupTime to kotlin.time.Duration.ZERO,
                ModemSettings.DialToneTime to kotlin.time.Duration.ZERO,
                ModemSettings.Handshake to no.skasti.yame.tone.HandshakeProfile.NONE,
                ModemSettings.Login to LoginCredentials("user", "secret"),
                ModemSettings.Tone to false,
                no.skasti.yame.ppp.PppSettings.Subnet to no.skasti.yame.ppp.ip.Ipv4Cidr.parse("10.99.0.0/30"),
                no.skasti.yame.ppp.dns.DnsSettings.Upstream to no.skasti.yame.ppp.ip.Ipv4Address.parse("1.1.1.1"),
                no.skasti.yame.ppp.proxy.ProxySettings.Enabled to false,
                TransformerSettings.Script to false,
                TransformerSettings.Style to false,
                TransformerSettings.Attributes to false,
                TransformerSettings.Elements to false,
            ) + LogSettings.Levels.values.map { it to YameLogLevel.DEBUG } +
                LogSettings.CaptureLimits.values.map { it to 20 }
            store.set(*changes.toTypedArray())
            val reloaded = lazyApplicationConfigStore(path)
            changes.forEach { change ->
                assertEquals(change.value, reloaded[change.key], change.key.path)
            }
            val text = Files.readString(directory.resolve("yame.ini"))
            for (name in listOf("version=1", "login.username=user", "port=COM4", "subnet=10.99.0.0/30", "upstream=1.1.1.1")) {
                assertTrue(text.contains(name), name)
            }
        } finally { directory.toFile().deleteRecursively() }
    }

}
