package no.skasti.yame

import no.skasti.yame.config.*
import no.skasti.yame.serial.*
import no.skasti.yame.modem.*
import no.skasti.yame.logging.*
import no.skasti.yame.ppp.*
import no.skasti.yame.ppp.dns.*
import no.skasti.yame.ppp.proxy.*
import no.skasti.yame.ppp.proxy.transform.TransformerSettings
import no.skasti.yame.persistence.lazyApplicationConfigStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class CliConfigurationTest {
    @TempDir lateinit var directory: Path

    @Test fun `CLI overrides file values in one batch without persisting unrelated overrides`() {
        val path = directory.resolve("yame.ini")
        Files.writeString(path, """
            [serial]
            port=COM1
            baud=57600
            flow-control=DISABLED
            [modem]
            login.username=saved
            login.password=saved-secret
            [logging]
            level-modem=WARN
        """.trimIndent())
        val store = lazyApplicationConfigStore(path)
        var notifications = 0
        store.onChange.subscribe { notifications++ }
        val options = parseArgs(arrayOf(
            "--baud", "9600", "--baud", "115200", "--port", "COM2", "--flow-control", "hardware",
            "--pickup-time", "500ms", "--dial-tone-time", "250ms", "--handshake-profile", "none",
            "--username", "cli", "--password", "cli-secret", "--subnet", "10.99.0.0/30",
            "--dns-upstream", "1.1.1.1", "--no-http-https-proxy", "--loglevel-modem", "debug",
            "--capture-limit-per-type", "20", "--capture-limit-total", "500", "--ui", "plain",
            "--log-dir", directory.resolve("logs").toString(),
        ), store)
        assertEquals(1, notifications)
        assertEquals(UiMode.PLAIN, options.uiMode)
        assertEquals(SerialConfig("COM2", 115200, SerialFlowControl.HARDWARE), SerialConfig.fromConfigStore(store))
        val modem = HayesModemConfig.fromConfigStore(store)
        assertEquals(500.milliseconds, modem.pickupTime)
        assertEquals(250.milliseconds, modem.dialToneTime)
        assertEquals(no.skasti.yame.tone.HandshakeProfile.NONE, modem.handshakeProfile)
        assertEquals("cli-secret", modem.password)
        assertEquals("10.99.0.0/30", PppIpConfig.fromConfigStore(store).configuredSubnet.toString())
        assertEquals("1.1.1.1", PppDnsConfig.fromConfigStore(store).upstreamServer.toString())
        assertFalse(store[ProxySettings.Enabled])
        assertEquals(YameLogLevel.DEBUG, LoggingConfig.fromConfigStore(store).levels[YameLogModule.MODEM])
        assertEquals(PacketCaptureLimits(20, 500), LoggingConfig.fromConfigStore(store).captureLimits)

        store.set(TransformerSettings.Style to false)
        val restarted = lazyApplicationConfigStore(path)
        assertEquals(SerialConfig("COM1", 57600), SerialConfig.fromConfigStore(restarted))
        assertEquals("saved-secret", HayesModemConfig.fromConfigStore(restarted).password)
        assertEquals(YameLogLevel.WARN, LoggingConfig.fromConfigStore(restarted).levels[YameLogModule.MODEM])
        assertFalse(restarted[TransformerSettings.Style])
        assertFalse(Files.readString(path).contains("cli-secret"))
        store.set(SerialSettings.Baud to 115200)
        assertEquals(115200, lazyApplicationConfigStore(path)[SerialSettings.Baud])
    }

    @Test fun `invalid CLI batch leaves effective values untouched and publishes nothing`() {
        for (args in listOf(arrayOf("--baud", "9600", "--username", "alone"),
            arrayOf("--port", "COM2", "--baud", "0"), arrayOf("--baud", "9600", "--unknown"))) {
            val store = ConfigStore()
            var deliveries = 0
            store.onChange.subscribe { deliveries++ }
            assertFails { parseArgs(args, store) }
            assertEquals(SerialConfig(), SerialConfig.fromConfigStore(store))
            assertEquals(0, deliveries)
        }
    }

    @Test fun `explicit store keeps serial modem and logging independent of process defaults`() {
        val process = ConfigStore(listOf(SerialSettings.Baud to 115200, ModemSettings.Tone to false))
        val explicit = ConfigStore(listOf(SerialSettings.Baud to 9600))
        ConfigStore.install(process).use {
            assertEquals(9600, SerialConfig.fromConfigStore(explicit).baudRate)
            assertTrue(HayesModemConfig.fromConfigStore(explicit).toneSimulationEnabled)
            explicit.set(SerialSettings.FlowControl to SerialFlowControl.HARDWARE)
            assertEquals(HayesModemConfig(), HayesModemConfig.fromConfigStore(explicit))
            assertEquals(115200, SerialConfig.fromConfigStore().baudRate)
            val logging = LoggingConfig.fromConfigStore(explicit)
            assertFailsWith<UnsupportedOperationException> {
                (logging.levels as MutableMap<YameLogModule, YameLogLevel>).clear()
            }
            explicit.set(LogSettings.Levels.getValue(YameLogModule.MODEM) to YameLogLevel.DEBUG)
            assertEquals(YameLogLevel.INFO, logging.levels[YameLogModule.MODEM])
        }
    }
}
