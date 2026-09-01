package no.skasti.serialmodem.logging

import java.nio.file.Files
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YameLoggingTest {
    @Test
    fun `module levels filter independently and all active files are created`() {
        val directory = Files.createTempDirectory("yame-logs")
        val levels = YameLogManager.defaultLevels().toMutableMap().apply {
            this[YameLogModule.PPP] = YameLogLevel.WARN
            this[YameLogModule.PROXY] = YameLogLevel.DEBUG
        }

        YameLogManager(levels, directory).use { logs ->
            logs.log(YameLogModule.PPP, YameLogLevel.INFO, "hidden ppp info")
            logs.log(YameLogModule.PPP, YameLogLevel.ERROR, "visible ppp error")
            logs.log(YameLogModule.PROXY, YameLogLevel.DEBUG, "visible proxy debug")
        }

        assertEquals(
            YameLogModule.entries.map { "${it.fileName}.log" }.toSet(),
            Files.list(directory).use { stream ->
                stream.map { it.name }.filter { !it.contains("-20") }.toList().toSet()
            },
        )
        val ppp = directory.resolve("ppp.log").readText()
        assertFalse("hidden ppp info" in ppp)
        assertContains(ppp, "visible ppp error")
        assertContains(directory.resolve("proxy.log").readText(), "visible proxy debug")
    }


    @Test
    fun `runtime level changes take effect immediately`() {
        val directory = Files.createTempDirectory("yame-log-runtime-levels")

        YameLogManager(YameLogManager.defaultLevels(), directory).use { logs ->
            logs.log(YameLogModule.PROXY, YameLogLevel.DEBUG, "hidden before")
            logs.setLevel(YameLogModule.PROXY, YameLogLevel.DEBUG)
            logs.log(YameLogModule.PROXY, YameLogLevel.DEBUG, "visible after")
            assertEquals(YameLogLevel.DEBUG, logs.level(YameLogModule.PROXY))
        }

        val proxy = directory.resolve("proxy.log").readText()
        assertFalse("hidden before" in proxy)
        assertContains(proxy, "visible after")
    }

    @Test
    fun `log initialization failure does not abort application logging setup`() {
        val notADirectory = Files.createTempFile("yame-log-path", ".tmp")

        YameLogManager(YameLogManager.defaultLevels(), notADirectory).use { logs ->
            assertEquals(YameLogLevel.INFO, logs.level(YameLogModule.PROXY))
            logs.log(YameLogModule.PROXY, YameLogLevel.ERROR, "logging path unavailable")
        }
    }

    @Test
    fun `serial reader termination is retained at error level`() {
        val directory = Files.createTempDirectory("yame-log-serial-error")
        val levels = YameLogManager.defaultLevels().toMutableMap().apply {
            this[YameLogModule.SERIAL] = YameLogLevel.ERROR
        }

        YameLogManager(levels, directory).use { logs ->
            logs.logger(YameLogModule.SERIAL)("Serial reader stopped: device disconnected")
        }

        assertContains(directory.resolve("serial.log").readText(), "Serial reader stopped")
    }

    @Test
    fun `active log rotates by size and archive retention is bounded`() {
        val directory = Files.createTempDirectory("yame-log-bounded")

        YameLogManager(
            YameLogManager.defaultLevels(),
            directory,
            maxActiveLogBytes = 120,
            maxArchivesPerModule = 2,
        ).use { logs ->
            repeat(20) { index ->
                logs.log(YameLogModule.PPP, YameLogLevel.INFO, "message-$index-" + "x".repeat(40))
            }
        }

        val pppFiles = Files.list(directory).use { stream ->
            stream.map { it.name }.filter { it.startsWith("ppp") }.toList()
        }
        assertTrue("ppp.log" in pppFiles)
        assertTrue(pppFiles.count { it != "ppp.log" } <= 2)
    }

    @Test
    fun `existing active log is archived using timestamped module name`() {
        val directory = Files.createTempDirectory("yame-log-rotation")

        YameLogManager(YameLogManager.defaultLevels(), directory).use { logs ->
            logs.log(YameLogModule.DNS, YameLogLevel.INFO, "first run")
        }

        YameLogManager(YameLogManager.defaultLevels(), directory).use { logs ->
            logs.log(YameLogModule.DNS, YameLogLevel.INFO, "second run")
        }

        val dnsFiles = Files.list(directory).use { stream ->
            stream.map { it.name }.filter { it.startsWith("dns") }.toList()
        }
        assertTrue("dns.log" in dnsFiles)
        val archives = dnsFiles.filter { it.matches(Regex("""dns-\d{4}-\d{2}-\d{2}-\d{2}-\d{2}(?:-\d+)?\.log""")) }
        assertEquals(1, archives.size)
        assertContains(directory.resolve(archives.single()).readText(), "first run")
        assertContains(directory.resolve("dns.log").readText(), "second run")
    }
}
