package no.skasti.serialmodem.ppp

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyHttpsUrlCodecTest {
    @Test
    fun `rewrites default HTTPS port onto legacy HTTP port 80`() {
        assertEquals(
            "http://example.test/.yame/https/443/path?q=1#fragment",
            LegacyHttpsUrlCodec.rewriteReferences("https://example.test/path?q=1#fragment"),
        )
        assertEquals(
            "http://example.test/.yame/https/443/path",
            LegacyHttpsUrlCodec.rewriteReferences("https://example.test:443/path"),
        )
    }

    @Test
    fun `preserves non-default HTTPS port inside compatibility marker`() {
        assertEquals(
            "http://example.test/.yame/https/8443/path",
            LegacyHttpsUrlCodec.rewriteReferences("https://example.test:8443/path"),
        )
    }

    @Test
    fun `decodes compatibility marker back to original HTTPS target`() {
        assertEquals(
            URI("https://example.test/path?q=1"),
            LegacyHttpsUrlCodec.decodeLegacyUri(
                URI("http://example.test/.yame/https/443/path?q=1"),
            ),
        )
        assertEquals(
            URI("https://example.test:8443/path"),
            LegacyHttpsUrlCodec.decodeLegacyUri(
                URI("http://example.test/.yame/https/8443/path"),
            ),
        )
    }

    @Test
    fun `leaves normal HTTP URLs unchanged`() {
        val uri = URI("http://example.test/path")
        assertEquals(uri, LegacyHttpsUrlCodec.decodeLegacyUri(uri))
        assertEquals("http://example.test/path", LegacyHttpsUrlCodec.rewriteReferences("http://example.test/path"))
    }
}
