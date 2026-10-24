package no.skasti.yame.persistence

import no.skasti.yame.config.ini.IniParser
import no.skasti.yame.serial.SerialSettings
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class IniParserCompatibilityTest {
    @TempDir lateinit var directory: Path

    @Test fun `section values preserve whitespace separators escapes and inline comment markers`() {
        val content = "# keep\n  \t  \n[ Modem ]\n login.password = secret  \t\n" +
            "arbitrary=\t  a  b\t=c:d\\path # literal ; literal  \nempty=\n; keep too\n"
        val document = IniParser.parse(content, "yame.ini")
        assertEquals(" secret  \t", document.get("modem", "login.password"))
        assertEquals("\t  a  b\t=c:d\\path # literal ; literal  ", document.get("modem", "arbitrary"))
        assertEquals("", document.get("modem", "empty"))
        assertEquals(null, document.get("modem", "missing"))
        assertEquals(content, document.render())

        document.put("modem", "arbitrary", "  changed\t=x:y\\z # stays  ")
        val written = document.render()
        assertTrue(written.startsWith("# keep\n  \t  \n[ Modem ]\n login.password = secret  \t\n"))
        assertTrue(written.contains("; keep too\n"))
        assertEquals("  changed\t=x:y\\z # stays  ", IniParser.parse(written).get("modem", "arbitrary"))
    }

    @Test fun `root Java Properties escapes leading spaces while unescaped leading spaces are syntax`() {
        val content = "serial.port=\\ leading  spaces\\=x\\\\path  \n" +
            "another=  unescaped  \ninline=value # literal; still value\n# comment\n"
        val document = IniParser.parse(content)
        assertEquals(" leading  spaces=x\\path  ", document.get("", "serial.port"))
        assertEquals("unescaped  ", document.get("", "another"))
        assertEquals("value # literal; still value", document.get("", "inline"))
        assertEquals(content, document.render())
    }

    @Test fun `root properties retain Java Properties decoding and last declaration wins`() {
        val content = """
            # existing comment
            ! Java Properties comment
            version : 1
            serial\.baud = 9600
            serial.baud: 57600
            serial.port = COM\ 4
            login.username=Andr\u0065
            login.password : s\:e\=cret\\value
            future\ key\:name : spaced\ value  
            future.continued=first\
               second
            future.unicode=Smør
            future.escaped-unicode=\u00E9
        """.trimIndent() + "\n"
        val expected = Properties().apply { load(StringReader(content)) }
        val document = IniParser.parse(content, "yame.ini")
        assertEquals(expected.stringPropertyNames().associateWith(expected::getProperty), document.properties(""))

        val path = directory.resolve("yame.ini")
        Files.writeString(path, content)
        val legacy = YameConfigurationStore(path).load()
        val runtime = lazyApplicationConfigStore(path)
        assertEquals(57_600, legacy.baudRate)
        assertEquals(legacy.baudRate, runtime[SerialSettings.Baud])
        assertEquals(legacy.portName, runtime[SerialSettings.Port])
        // Historical login.* is intentionally not an alias for modem.login.*.
        assertEquals(null, no.skasti.yame.modem.HayesModemConfig.fromConfigStore(runtime).username)
    }

    @Test fun `writing one setting keeps unknown escaped values continuation and comments`() {
        val path = directory.resolve("yame.ini")
        val untouched = """
            # keep this comment
            ! and this comment
            future\ key\:name : spaced\ value  
            future.continued=first\
               second
            future.unicode=Smør
        """.trimIndent() + "\n"
        Files.writeString(path, untouched + "serial.baud=38400\nserial.baud: 57600\n")
        val original = Properties().apply { Files.newBufferedReader(path).use { load(it) } }

        val store = lazyApplicationConfigStore(path)
        assertEquals(57_600, store[SerialSettings.Baud])
        store.set(SerialSettings.Baud to 115_200)

        val written = Files.readString(path)
        assertTrue(written.startsWith(untouched))
        assertTrue(written.contains("[serial]\nbaud=115200\n"))
        assertTrue(!written.contains("serial.baud="))
        val updated = Properties().apply { Files.newBufferedReader(path).use { load(it) } }
        original.stringPropertyNames().filter { it != "serial.baud" }.forEach { key ->
            assertEquals(original.getProperty(key), updated.getProperty(key), key)
        }
        assertEquals("115200", IniParser.parse(written).get("serial", "baud"))
        assertEquals(115_200, YameConfigurationStore(path).load().baudRate)
    }

    @Test fun `legacy save preserves unknown Java Properties records`() {
        val path = directory.resolve("yame.ini")
        val untouched = "# keep\nfuture\\ key:old\\ value  \nfuture.more=one\\\n  two\n"
        Files.writeString(path, untouched + "serial.baud=9600\n")
        YameConfigurationStore(path).save(YameConfiguration(baudRate = 57_600))
        assertTrue(Files.readString(path).startsWith(untouched))
    }

    @Test fun `unchanged comments retain CRLF and files without final newline stay that way`() {
        val crlf = directory.resolve("crlf.ini")
        Files.writeString(crlf, "# existing\r\nfuture.continued=one\\\r\n  two\r\nserial.baud=38400\r\n")
        val store = lazyApplicationConfigStore(crlf)
        store[SerialSettings.Baud]
        store.set(SerialSettings.Baud to 57_600)
        assertTrue(Files.readString(crlf).startsWith("# existing\r\nfuture.continued=one\\\r\n  two\r\n"))
        assertTrue(Files.readString(crlf).endsWith("[serial]\r\nbaud=57600\r\n"))

        val unterminated = directory.resolve("unterminated.ini")
        Files.writeString(unterminated, "# keep\nserial.baud=38400")
        val second = lazyApplicationConfigStore(unterminated)
        second[SerialSettings.Baud]
        second.set(SerialSettings.Baud to 57_600)
        assertEquals("# keep\n[serial]\nbaud=57600", Files.readString(unterminated))
    }

    @Test fun `malformed Unicode escape reports file and physical line`() {
        val error = assertFailsWith<IllegalArgumentException> {
            IniParser.parse("# comment\nserial.port=COM\\uXYZ1\n", "yame.ini")
        }
        assertTrue(error.message.orEmpty().contains("yame.ini line 2"), error.message)
    }

    @Test fun `named sections retain strict duplicates with location`() {
        val error = assertFailsWith<IllegalArgumentException> {
            IniParser.parse("[heading]\ntarget=p\ntarget=div\n", "element-simplifier.ini")
        }
        assertTrue(error.message.orEmpty().contains("element-simplifier.ini line 3"), error.message)
        assertTrue(error.message.orEmpty().contains("target"), error.message)
    }
}
