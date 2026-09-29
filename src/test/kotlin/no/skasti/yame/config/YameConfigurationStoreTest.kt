package no.skasti.yame.config

import no.skasti.yame.logging.YameLogLevel
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.ppp.PppIpConfig
import no.skasti.yame.ppp.dns.PppDnsConfig
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.ip.Ipv4Cidr
import no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig
import no.skasti.yame.serial.SerialFlowControl
import no.skasti.yame.tone.HandshakeProfile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

class YameConfigurationStoreTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `saves and reloads complete configuration`() {
        val path = tempDirectory.resolve("yame.ini")
        val store = YameConfigurationStore(path)
        val expected = YameConfiguration(
            portName = "/dev/ttyUSB0",
            baudRate = 57_600,
            flowControl = SerialFlowControl.HARDWARE,
            modemConfig = HayesModemConfig(
                pickupTime = 3.seconds,
                dialToneTime = 750.milliseconds,
                handshakeProfile = HandshakeProfile.NONE,
                username = "modem-user",
                password = "s:e=cret\\value",
                pppIpConfig = PppIpConfig(Ipv4Cidr.parse("10.10.0.0/30")),
                pppDnsConfig = PppDnsConfig(Ipv4Address.parse("1.1.1.1")),
                pppHttpCompatibilityConfig = PppHttpCompatibilityConfig(enabled = false),
            ),
            logLevels = YameLogModule.entries.associateWith { YameLogLevel.DEBUG },
        )

        store.save(expected)

        assertEquals(expected, store.load())
    }

    @Test
    fun `configuration file containing a password is owner readable only`() {
        val path = tempDirectory.resolve("yame.ini")
        YameConfigurationStore(path).save(
            YameConfiguration(
                modemConfig = HayesModemConfig(username = "user", password = "secret"),
            ),
        )

        val permissions = Files.getPosixFilePermissions(path)
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            permissions,
        )
        assertTrue(Files.readString(path).contains("login.password=secret"))
    }

    @Test
    fun `missing config file returns defaults`() {
        assertEquals(
            YameConfiguration(),
            YameConfigurationStore(tempDirectory.resolve("missing.ini")).load(),
        )
        assertEquals(YameLogManager.defaultLevels(), YameConfiguration().logLevels)
    }
}
