package no.skasti.yame.ppp.proxy

import java.net.URI
import kotlin.test.*

class HttpResourceMetadataResolverTest {
    private val uri = URI("https://download.test/files/fallback%20name.exe?token=hidden#part")
    private fun filename(header: String? = null, target: URI = uri) =
        HttpResourceMetadataResolver.filename(target, header?.let { listOf("cOnTeNt-DiSpOsItIoN" to it) }.orEmpty())

    @Test fun `extended UTF-8 names precede plain names and quoted separators are preserved`() {
        assertEquals("WINZIP95.EXE", filename("attachment; filename=\"WINZIP95.EXE\""))
        assertEquals("blåbær.exe", filename("attachment; filename=plain.exe; filename*=UTF-8'nb'bl%C3%A5b%C3%A6r.exe"))
        assertEquals("a;b.exe", filename("attachment; filename=\"a;b.exe\""))
        assertEquals("a\"b.exe", filename("attachment; filename=\"a\\\"b.exe\""))
        assertEquals("blå.exe", filename("attachment; filename*=ISO-8859-1''bl%E5.exe"))
    }

    @Test fun `invalid metadata falls back without leaking query or fragment into the label`() {
        for (header in listOf(null, "attachment", "attachment; filename*=UTF-8''%GG",
                "attachment; filename*=UTF-8''%FF", "attachment; filename*=unknown''name",
                "attachment; filename=\"unterminated", "attachment; filename=x; filename=y",
                "attachment; filename=\"..\"", "attachment; filename=\"\"",
                "attachment; filename=\"bad\r\nname\"", "attachment; filename=" + "a".repeat(9000))) {
            assertEquals("fallback name.exe", filename(header), header)
        }
        assertEquals("plain.exe", filename("attachment; filename=plain.exe; filename*=UTF-8''%FF"))
        assertNull(filename(target = URI("http://host.test/")))
        assertNull(filename(target = URI("http://host.test/%FF")))
        assertEquals("a+b.exe", filename(target = URI("http://host.test/a+b.exe")))
        assertEquals("last", filename(target = URI("http://host.test/last/")))
    }

    @Test fun `paths controls and invisible direction overrides are removed with bounded unicode names`() {
        assertEquals("WINZIP95.EXE", filename("attachment; filename=\"../../WINZIP95.EXE\""))
        assertEquals("file.exe", filename("attachment; filename=\"C:\\\\tmp\\\\file.exe\""))
        assertEquals("file.exe", filename("attachment; filename*=UTF-8''..%2Ffile.exe"))
        assertEquals("bad[31m.exe", filename("attachment; filename*=UTF-8''bad%1B%5B31m.exe"))
        assertEquals("safe.exe", filename("attachment; filename*=UTF-8''safe%E2%80%AE.exe"))
        val name = assertNotNull(filename("attachment; filename=\"${"😀".repeat(300)}\""))
        assertEquals(256, name.codePointCount(0, name.length))
    }
}
