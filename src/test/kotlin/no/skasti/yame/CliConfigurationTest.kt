package no.skasti.yame

import no.skasti.yame.config.withInstalledConfig
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
        val options = withInstalledConfig(store) { parseArgs(arrayOf(
            "--baud", "9600", "--baud", "115200", "--port", "COM2", "--flow-control", "hardware",
            "--pickup-time", "500ms", "--dial-tone-time", "250ms", "--handshake-profile", "none",
            "--username", "cli", "--password", "cli-secret", "--subnet", "10.99.0.0/30",
            "--dns-upstream", "1.1.1.1", "--no-http-https-proxy", "--loglevel-modem", "debug",
            "--capture-limit-per-type", "20", "--capture-limit-total", "500", "--ui", "plain",
            "--log-dir", directory.resolve("logs").toString(),
        )) }
        assertEquals(1, notifications)
        assertEquals(UiMode.PLAIN, options.uiMode)
        assertEquals("COM2", store[SerialSettings.Port])
        assertEquals(115200, store[SerialSettings.Baud])
        assertEquals(SerialFlowControl.HARDWARE, store[SerialSettings.FlowControl])
        val modem = withInstalledConfig(store) { HayesModemConfig.fromConfigStore() }
        assertEquals(500.milliseconds, modem.pickupTime)
        assertEquals(250.milliseconds, modem.dialToneTime)
        assertEquals(no.skasti.yame.tone.HandshakeProfile.NONE, modem.handshakeProfile)
        assertEquals("cli-secret", modem.password)
        assertEquals("10.99.0.0/30", withInstalledConfig(store) { PppIpConfig.fromConfigStore() }.configuredSubnet.toString())
        assertEquals("1.1.1.1", withInstalledConfig(store) { PppDnsConfig.fromConfigStore() }.upstreamServer.toString())
        assertFalse(store[ProxySettings.Enabled])
        assertEquals(YameLogLevel.DEBUG, withInstalledConfig(store) { LoggingConfig.fromConfigStore() }.levels[YameLogModule.MODEM])
        assertEquals(PacketCaptureLimits(20, 500), withInstalledConfig(store) { LoggingConfig.fromConfigStore() }.captureLimits)

        store.set(TransformerSettings.Style to false)
        val restarted = lazyApplicationConfigStore(path)
        assertEquals("COM1", restarted[SerialSettings.Port])
        assertEquals(57600, restarted[SerialSettings.Baud])
        assertEquals(SerialFlowControl.DISABLED, restarted[SerialSettings.FlowControl])
        assertEquals("saved-secret", withInstalledConfig(restarted) { HayesModemConfig.fromConfigStore() }.password)
        assertEquals(YameLogLevel.WARN, withInstalledConfig(restarted) { LoggingConfig.fromConfigStore() }.levels[YameLogModule.MODEM])
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
            assertFails { withInstalledConfig(store) { parseArgs(args) } }
            assertNull(store[SerialSettings.Port])
            assertEquals(38400, store[SerialSettings.Baud])
            assertEquals(SerialFlowControl.DISABLED, store[SerialSettings.FlowControl])
            assertEquals(0, deliveries)
        }
    }

    @Test fun `production factories read installed settings while explicit values stay independent`() {
        val process = ConfigStore(listOf(SerialSettings.Baud to 115200, ModemSettings.Tone to false))
        val explicitModem = HayesModemConfig()
        val explicitLogging = LoggingConfig()
        ConfigStore.install(process).use {
            assertEquals(115200, ConfigStore[SerialSettings.Baud])
            assertFalse(HayesModemConfig.fromConfigStore().toneSimulationEnabled)
            assertTrue(explicitModem.toneSimulationEnabled)
            ConfigStore.set(LogSettings.Levels.getValue(YameLogModule.MODEM) to YameLogLevel.DEBUG)
            assertEquals(YameLogLevel.DEBUG, LoggingConfig.fromConfigStore().levels[YameLogModule.MODEM])
            assertEquals(YameLogLevel.INFO, explicitLogging.levels[YameLogModule.MODEM])
            assertFailsWith<UnsupportedOperationException> {
                (explicitLogging.levels as MutableMap<YameLogModule, YameLogLevel>).clear()
            }
        }
    }
}
