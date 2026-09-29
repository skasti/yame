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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
                toneSimulationEnabled = false,
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
    fun `accepts maximum string lengths and saves a readable configuration`() {
        val path = tempDirectory.resolve("maximum.ini")
        val configuration = YameConfiguration(
            portName = "p".repeat(YameConfiguration.MAX_PORT_NAME_LENGTH),
            modemConfig = HayesModemConfig(
                username = "u".repeat(HayesModemConfig.MAX_LOGIN_USERNAME_LENGTH),
                password = "p".repeat(HayesModemConfig.MAX_LOGIN_PASSWORD_LENGTH),
            ),
        )
        val store = YameConfigurationStore(path)

        store.save(configuration)

        assertTrue(Files.size(path) <= 32 * 1024)
        assertEquals(configuration, store.load())
    }

    @Test
    fun `rejects string values longer than their supported limits`() {
        assertFailsWith<IllegalArgumentException> {
            YameConfiguration(
                portName = "p".repeat(YameConfiguration.MAX_PORT_NAME_LENGTH + 1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            HayesModemConfig(
                username = "u".repeat(HayesModemConfig.MAX_LOGIN_USERNAME_LENGTH + 1),
                password = "secret",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            HayesModemConfig(
                username = "user",
                password = "p".repeat(HayesModemConfig.MAX_LOGIN_PASSWORD_LENGTH + 1),
            )
        }
    }

    @Test
    fun `configuration file containing a password is owner readable only`() {
        val path = tempDirectory.resolve("yame.ini")
        YameConfigurationStore(path).save(
            YameConfiguration(
                modemConfig = HayesModemConfig(username = "user", password = "secret"),
            ),
        )

        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            val permissions = Files.getPosixFilePermissions(path)
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                permissions,
            )
        }
        assertTrue(Files.readString(path).contains("login.password=secret"))
    }

    @Test
    fun `rejects properties that exceed the serialized config size limit`() {
        val properties = Properties().apply {
            setProperty("oversized", "x".repeat(MAX_CONFIG_BYTES))
        }

        assertFailsWith<IOException> {
            serializePropertiesWithinLimit(properties)
        }
    }

    @Test
    fun `rejects non-regular configuration paths`() {
        val path = tempDirectory.resolve("config-directory")
        Files.createDirectory(path)

        assertFailsWith<IllegalArgumentException> {
            YameConfigurationStore(path).load()
        }
    }

    @Test
    fun `rejects configuration larger than the read limit`() {
        val path = tempDirectory.resolve("oversized.ini")
        Files.write(path, ByteArray(32 * 1024 + 1) { 'x'.code.toByte() })

        assertFailsWith<IllegalArgumentException> {
            YameConfigurationStore(path).load()
        }
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
