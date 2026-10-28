package no.skasti.yame.persistence

import no.skasti.yame.config.*
import no.skasti.yame.serial.*
import no.skasti.yame.modem.*
import no.skasti.yame.logging.*
import no.skasti.yame.ppp.PppSettings
import no.skasti.yame.ppp.ip.Ipv4Cidr
import no.skasti.yame.ppp.proxy.transform.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.*

class IniConfigStoreTest {
    @TempDir lateinit var directory: Path

    @Test fun `generic first run creation uses feature defaults and preserves transient values`() {
        val feature = object : SeparateSettings("independent") {
            val group = SettingGroup(this)
            val Value = SettingKey<Int>("value", group, default = { 7 })
        }
        val store = lazyApplicationConfigStore(directory.resolve("yame.ini"))
        store.setTransient(feature.Value to 9)
        store.createFileIfMissing(feature)
        assertEquals(9, store[feature.Value])
        assertEquals(7, lazyApplicationConfigStore(directory.resolve("yame.ini"))[feature.Value])
        assertFalse(Files.exists(directory.resolve("yame.ini")))
    }

    @Test fun `first run creation cannot overwrite a file appearing during preparation`() {
        val path = directory.resolve("race.ini")
        val existing = "# user file\n[race]\nvalue=42\n"
        val feature = object : SeparateSettings("race") {
            override fun loadFromIni(context: IniReadContext): List<ConfigChange<*>> {
                if (!context.existed) Files.writeString(path, existing)
                return super.loadFromIni(context)
            }
            val group = SettingGroup(this)
            val Value = SettingKey<Int>("value", group, default = { 7 })
        }
        val store = lazyApplicationConfigStore(directory.resolve("yame.ini"))
        store.createFileIfMissing(feature)
        assertEquals(existing, Files.readString(path))
        assertEquals(42, store[feature.Value])
        Files.list(directory).use { paths -> assertEquals(listOf(path), paths.toList()) }
    }

    @Test fun `invalid feature defaults create no file and can be retried`() {
        var initial = -1
        val feature = object : SeparateSettings("initial") {
            val group = SettingGroup(this)
            val Value = SettingKey<Int>("value", group, { require(it > 0) }, default = { initial })
        }
        val store = lazyApplicationConfigStore(directory.resolve("yame.ini"))
        var events = 0
        store.onChange.subscribe { events++ }
        assertFailsWith<IllegalArgumentException> { store.createFileIfMissing(feature) }
        assertFalse(Files.exists(directory.resolve("initial.ini")))
        initial = 8
        store.createFileIfMissing(feature)
        assertEquals(8, store[feature.Value])
        assertEquals(0, events)
    }

    @Test fun `missing files use feature defaults and first run saves baseline without CLI secrets`() {
        val path = directory.resolve("yame.ini")
        val store = lazyApplicationConfigStore(path)
        assertEquals(SerialConfig(), SerialConfig.fromConfigStore(store))
        assertEquals(HayesModemConfig(), HayesModemConfig.fromConfigStore(store))
        assertEquals(YameLogManager.defaultLevels(), LoggingConfig.fromConfigStore(store).levels)
        no.skasti.yame.parseArgs(arrayOf("--baud", "115200", "--username", "cli", "--password", "secret"), store)
        store.saveLoadedValues()
        val restarted = lazyApplicationConfigStore(path)
        assertEquals(38400, restarted[SerialSettings.Baud])
        assertNull(restarted[ModemSettings.Login].password)
        assertFalse(Files.readString(path).contains("secret"))
        assertTrue(Files.readString(path).contains("version=1"))
        assertEquals(115200, store[SerialSettings.Baud])
    }

    @Test fun `optional values can be cleared and unrelated records survive`() {
        val path = directory.resolve("yame.ini")
        val untouched = "# keep\nfuture.setting=42\n[future]\nenabled=yes\n"
        Files.writeString(path, untouched)
        val store = lazyApplicationConfigStore(path)
        store.set(SerialSettings.Port to "COM1", ModemSettings.Login to LoginCredentials("user", "secret"),
            PppSettings.Subnet to Ipv4Cidr.parse("10.0.0.0/30"))
        store.set(SerialSettings.Port to null, ModemSettings.Login to LoginCredentials(null, null), PppSettings.Subnet to null)
        val restarted = lazyApplicationConfigStore(path)
        assertNull(restarted[SerialSettings.Port])
        assertNull(restarted[ModemSettings.Login].username)
        assertNull(restarted[PppSettings.Subnet])
        assertTrue(Files.readString(path).startsWith(untouched))
        assertFalse(Files.readString(path).contains("secret"))
    }

    @Test fun `maximum strings round trip and writes restrict permissions`() {
        val path = directory.resolve("yame.ini")
        val port = "p".repeat(SerialSettings.MAX_PORT_NAME_LENGTH)
        val login = LoginCredentials("u".repeat(HayesModemConfig.MAX_LOGIN_USERNAME_LENGTH),
            "p".repeat(HayesModemConfig.MAX_LOGIN_PASSWORD_LENGTH))
        lazyApplicationConfigStore(path).set(SerialSettings.Port to port, ModemSettings.Login to login)
        val reloaded = lazyApplicationConfigStore(path)
        assertEquals(port, reloaded[SerialSettings.Port])
        assertEquals(login, reloaded[ModemSettings.Login])
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(path))
        }
        assertFailsWith<IllegalArgumentException> { reloaded.set(SerialSettings.Port to port + "p") }
        assertEquals(port, reloaded[SerialSettings.Port])
        assertFailsWith<IllegalArgumentException> {
            reloaded.set(ModemSettings.Login to LoginCredentials(login.username + "u", login.password))
        }
        assertFailsWith<IllegalArgumentException> {
            reloaded.set(ModemSettings.Login to LoginCredentials(login.username, login.password + "p"))
        }
        assertEquals(login, reloaded[ModemSettings.Login])
    }

    @Test fun `invalid capture settings reject loading`() {
        val path = directory.resolve("yame.ini")
        for (value in listOf("0", "-1", "no", "2147483648")) {
            Files.writeString(path, "[logging]\nmax-per-type=$value")
            assertFailsWith<IllegalArgumentException> { LoggingConfig.fromConfigStore(lazyApplicationConfigStore(path)) }
        }
    }

    @Test fun `non regular oversized and unsupported version files reject loading`() {
        val path = directory.resolve("yame.ini")
        Files.createDirectory(path)
        assertFailsWith<IllegalArgumentException> { lazyApplicationConfigStore(path)[SerialSettings.Baud] }
        Files.delete(path)
        Files.write(path, ByteArray(no.skasti.yame.config.ini.IniParser.MAX_BYTES + 1) { 'x'.code.toByte() })
        assertFailsWith<IllegalArgumentException> { lazyApplicationConfigStore(path)[SerialSettings.Baud] }
        Files.writeString(path, "version=999")
        assertFailsWith<IllegalArgumentException> { lazyApplicationConfigStore(path)[SerialSettings.Baud] }
    }

    @Test fun `oversized write rolls back disk and memory`() {
        val path = directory.resolve("yame.ini")
        val store = lazyApplicationConfigStore(path)
        store.set(SerialSettings.Baud to 9600)
        val before = Files.readAllBytes(path)
        val huge = object : Settings("large") {
            val group = SettingGroup(this)
            val Value = SettingKey<String>("value", group, default = { "" })
        }
        assertFailsWith<IllegalArgumentException> {
            store.set(SerialSettings.Baud to 19200, huge.Value to "x".repeat(32 * 1024))
        }
        assertContentEquals(before, Files.readAllBytes(path))
        assertEquals(9600, store[SerialSettings.Baud])
        assertEquals("", store[huge.Value])
    }
}
