package no.skasti.yame.persistence

import no.skasti.yame.config.*
import no.skasti.yame.config.ini.IniParser
import no.skasti.yame.serial.*
import no.skasti.yame.modem.*
import no.skasti.yame.logging.*
import no.skasti.yame.ppp.PppSettings
import no.skasti.yame.ppp.proxy.transform.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class IniConfigStoreTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("yame.ini")

    @Test fun `component defaults are feature owned and independent`() {
        val store = iniConfigStore(path)
        assertEquals(SerialConfig(), SerialConfig.fromConfigStore(store))
        assertEquals(HayesModemConfig(), HayesModemConfig.fromConfigStore(store))
        val logging = LoggingConfig.fromConfigStore(store)
        assertEquals(YameLogManager.defaultLevels(), logging.levels)
        assertEquals(PacketCaptureLimits(), logging.captureLimits)
        assertFalse(Files.exists(path))
        store.set(SerialSettings.Baud to 9600, SerialSettings.FlowControl to SerialFlowControl.HARDWARE)
        assertEquals(9600, SerialConfig.fromConfigStore(store).baudRate)
        assertEquals(HayesModemConfig(), HayesModemConfig.fromConfigStore(store))
        store.set(ModemSettings.Tone to false)
        assertEquals(SerialFlowControl.HARDWARE, SerialConfig.fromConfigStore(store).flowControl)
    }

    @Test fun `optional values can be removed without touching unrelated records`() {
        Files.writeString(path, "# keep\n[future]\nvalue=42\n")
        val store = iniConfigStore(path)
        store.ensureLoaded(SerialSettings)
        store.ensureLoaded(ModemSettings)
        store.ensureLoaded(PppSettings)
        store.set(SerialSettings.Port to "COM1", ModemSettings.Login to LoginCredentials("user", "secret"),
            PppSettings.Subnet to no.skasti.yame.ppp.ip.Ipv4Cidr.parse("10.0.0.0/30"))
        store.set(SerialSettings.Port to null, ModemSettings.Login to LoginCredentials(null, null), PppSettings.Subnet to null)
        val restart = iniConfigStore(path)
        assertNull(restart[SerialSettings.Port])
        assertNull(restart[ModemSettings.Login].password)
        assertNull(restart[PppSettings.Subnet])
        assertTrue(Files.readString(path).startsWith("# keep\n[future]\nvalue=42\n"))
        assertFalse(Files.readString(path).contains("secret"))
    }

    @Test fun `maximum strings persist with private permissions and excessive values fail`() {
        val store = iniConfigStore(path)
        store.ensureLoaded(SerialSettings)
        store.ensureLoaded(ModemSettings)
        val port = "p".repeat(SerialSettings.MAX_PORT_NAME_LENGTH)
        val login = LoginCredentials("u".repeat(HayesModemConfig.MAX_LOGIN_USERNAME_LENGTH),
            "p".repeat(HayesModemConfig.MAX_LOGIN_PASSWORD_LENGTH))
        store.set(SerialSettings.Port to port, ModemSettings.Login to login)
        assertEquals(port, iniConfigStore(path)[SerialSettings.Port])
        assertEquals(login, iniConfigStore(path)[ModemSettings.Login])
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(path))
        }
        val before = Files.readAllBytes(path)
        assertFailsWith<IllegalArgumentException> { store.set(SerialSettings.Port to port + "x") }
        assertContentEquals(before, Files.readAllBytes(path))
    }

    @Test fun `invalid limits paths and oversized files fail on owner read`() {
        for (value in listOf("0", "-1", "no", "2147483648")) {
            Files.writeString(path, "[logging]\nmax-per-type=$value\n")
            assertFailsWith<IllegalArgumentException> { LoggingConfig.fromConfigStore(iniConfigStore(path)) }
        }
        Files.delete(path)
        Files.createDirectory(path)
        assertFailsWith<IllegalArgumentException> { iniConfigStore(path)[SerialSettings.Baud] }
        Files.delete(path)
        Files.write(path, ByteArray(IniParser.MAX_BYTES + 1) { 'x'.code.toByte() })
        assertFailsWith<IllegalArgumentException> { iniConfigStore(path)[SerialSettings.Baud] }
    }

    @Test fun `transformer switches persist independently of mapping file`() {
        val store = iniConfigStore(path)
        store.ensureLoaded(TransformerSettings)
        store.set(TransformerSettings.Script to false, TransformerSettings.Elements to false)
        val restart = iniConfigStore(path)
        assertFalse(HtmlTransformerSettings.fromConfigStore(restart).scriptRemover)
        assertFalse(HtmlTransformerSettings.fromConfigStore(restart).elementSimplifier)
        assertFalse(Files.exists(directory.resolve("element-simplifier.ini")))
        Files.writeString(path, "[transformers]\nstyle-remover-enabled=maybe\n")
        assertFailsWith<IllegalArgumentException> { HtmlTransformerSettings.fromConfigStore(iniConfigStore(path)) }
    }
}
