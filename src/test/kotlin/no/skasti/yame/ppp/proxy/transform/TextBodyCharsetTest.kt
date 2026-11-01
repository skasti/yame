package no.skasti.yame.ppp.proxy.transform

import java.nio.charset.Charset
import kotlin.test.*

class TextBodyCharsetTest {
    private fun decode(bytes: ByteArray, type: String): String {
        var decoded = ""
        val result = rewriteEncodedTextBody(mapOf("Content-Type" to listOf(type)), bytes) {
            decoded = it
            it
        }
        assertSame(bytes, result) // Detection alone must not rewrite source bytes.
        return decoded
    }

    @Test fun `HTML meta charset supplies encoding when HTTP omits it`() {
        for (meta in listOf("<meta charset='UTF-8'>", "<meta charset='UTF-16'>",
            "<META content='text/html; charset=UTF-8' HTTP-EQUIV='Content-Type'>")) {
            val html = "<html><head>$meta</head><body>Blåbær – «bygdekollektiv»</body></html>"
            assertEquals(html, decode(html.toByteArray(), "text/html"))
            assertEquals(html, decode(html.toByteArray(), "text/html; charset=invalid-label"))
        }
    }

    @Test fun `HTML BOM wins and HTTP charset precedes meta`() {
        val html = "<html><meta charset='windows-1252'><p>Blåbær –</p></html>"
        assertEquals("\uFEFF$html", decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            html.toByteArray(), "text/html; charset=windows-1252"))
        assertEquals(html, decode(html.toByteArray(), "text/html; charset=UTF-8"))
    }

    @Test fun `HTML Latin1 and fallback support Windows punctuation while other text keeps existing fallback`() {
        val html = "<p>Blåbær – “sitat” €</p>"
        val bytes = html.toByteArray(Charset.forName("windows-1252"))
        for (type in listOf("text/html", "text/html; charset=ISO-8859-1", "text/html; charset=US-ASCII")) assertEquals(html, decode(bytes, type))
        assertEquals(bytes.toString(Charsets.ISO_8859_1), decode(bytes, "text/css"))
    }

    @Test fun `fake late and non HTML meta declarations cannot override fallback`() {
        for (html in listOf(
            "<!-- <meta charset='utf-8'> --><p>Blåbær</p>",
            "<script>\"<meta charset='utf-8'>\"</script><p>Blåbær</p>",
            "<div title=\"<meta charset='utf-8'>\"></div><p>Blåbær</p>",
            " ".repeat(1024) + "<meta charset='utf-8'><p>Blåbær</p>",
            "<meta charset='nonexistent-charset'><p>Blåbær</p>",
        )) assertEquals(html, decode(html.toByteArray(Charsets.ISO_8859_1), "text/html"))
        val css = "/* <meta charset='utf-8'> */ Blåbær"
        assertEquals(css, decode(css.toByteArray(Charsets.ISO_8859_1), "text/css"))
    }
}
