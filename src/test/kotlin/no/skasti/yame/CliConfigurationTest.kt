package no.skasti.yame

import no.skasti.yame.config.*
import no.skasti.yame.persistence.iniConfigStore
import no.skasti.yame.serial.*
import no.skasti.yame.modem.*
import no.skasti.yame.logging.*
import no.skasti.yame.ppp.dns.DnsSettings
import no.skasti.yame.ppp.proxy.ProxySettings
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class CliConfigurationTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("yame.ini")

    @Test fun `repeated flags use last value and file defaults remain durable`() {
        Files.writeString(path, "[serial]\nport=COM1\nbaud=57600\nflow-control=HARDWARE\n[modem]\nlogin.username=file\nlogin.password=saved\n")
        val store = iniConfigStore(path)
        parseArgs(arrayOf("--baud", "9600", "--baud", "19200", "--username", "cli", "--password", "secret",
            "--no-http-https-proxy", "--capture-limit-total", "42", "--dns-upstream", "1.1.1.1"), store, path)
        assertEquals(SerialConfig("COM1", 19200, SerialFlowControl.HARDWARE), SerialConfig.fromConfigStore(store))
        assertEquals(LoginCredentials("cli", "secret"), store[ModemSettings.Login])
        assertFalse(store[ProxySettings.Enabled])
        assertEquals(42, LoggingConfig.fromConfigStore(store).captureLimits.maxTotal)
        assertEquals("1.1.1.1", store[DnsSettings.Upstream].toString())
        assertEquals(57600, iniConfigStore(path)[SerialSettings.Baud])
        assertEquals("saved", iniConfigStore(path)[ModemSettings.Login].password)
    }

    @Test fun `invalid arguments never publish a partial override batch`() {
        val store = iniConfigStore(path)
        var notifications = 0
        store.onChange.subscribe { notifications++ }.use {
            for (args in listOf(arrayOf("--baud", "9600", "--unknown"),
                arrayOf("--baud", "9600", "--username", "user"),
                arrayOf("--baud", "0"), arrayOf("--port", "p".repeat(257)))) {
                assertFails { parseArgs(args, store, path) }
                assertEquals(38400, store[SerialSettings.Baud])
                assertNull(store[SerialSettings.Port])
            }
        }
        assertEquals(0, notifications)
        assertFalse(Files.exists(path))
    }

    @Test fun `informational commands avoid file reads and first run saves baseline only`() {
        Files.writeString(path, "[serial]\nbaud=invalid\n")
        assertTrue(parseArgs(arrayOf("--list"), iniConfigStore(path), path).listPorts)
        Files.delete(path)
        val store = iniConfigStore(path)
        parseArgs(arrayOf("--baud", "9600", "--username", "cli", "--password", "secret"), store, path)
        LoggingConfig.fromConfigStore(store)
        store.persistLoadedValues()
        val restart = iniConfigStore(path)
        assertEquals(38400, restart[SerialSettings.Baud])
        assertNull(restart[ModemSettings.Login].password)
        assertEquals(9600, store[SerialSettings.Baud])
        assertFalse(Files.readString(path).contains("secret"))
    }
}
