package no.skasti.serialmodem.ppp

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LegacyHttpsUrlCodecTest {
    @Test
    fun `encodes default HTTPS port onto legacy HTTP port 80`() {
        assertEquals(
            "http://example.test/.yame/https/443/path?q=1#fragment",
            LegacyHttpsUrlCodec.encode(URI("https://example.test/path?q=1#fragment")),
        )
        assertEquals(
            "http://example.test/.yame/https/443/path",
            LegacyHttpsUrlCodec.encode(URI("https://example.test:443/path")),
        )
    }

    @Test
    fun `preserves non-default HTTPS port inside compatibility marker`() {
        assertEquals(
            "http://example.test/.yame/https/8443/path",
            LegacyHttpsUrlCodec.encode(URI("https://example.test:8443/path")),
        )
    }

    @Test
    fun `encodes and decodes IPv6 HTTPS authorities`() {
        val target = URI("https://[2001:db8::1]:8443/path")
        val legacy = URI(LegacyHttpsUrlCodec.encode(target))

        assertEquals("http://[2001:db8::1]/.yame/https/8443/path", legacy.toString())
        assertEquals(target, LegacyHttpsUrlCodec.decodeLegacyUri(legacy))
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
    fun `session route keeps root-relative requests on hidden HTTPS origin`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 1)
        val legacyPage = URI("http://example.test/app")
        val upstreamPage = URI("https://example.test:8443/app")

        assertEquals(
            "http://example.test" to "https://example.test:8443",
            routes.remember(flow, legacyPage, upstreamPage),
        )
        assertEquals(
            URI("https://example.test:8443/assets/logo.gif?size=2"),
            routes.resolve(flow, URI("http://example.test/assets/logo.gif?size=2")),
        )
        assertEquals(
            "http://example.test/next",
            routes.cleanHttpReference(flow, URI("https://example.test:8443/next")),
        )
    }

    @Test
    fun `bootstrap request establishes clean session route after first HTTPS navigation`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 4)
        val target = URI("https://example.test:8443/app")
        val bootstrap = URI(LegacyHttpsUrlCodec.encode(target))

        assertEquals(target, routes.resolve(flow, bootstrap))
        routes.remember(flow, bootstrap, target)

        assertEquals(
            URI("https://example.test:8443/assets/site.css"),
            routes.resolve(flow, URI("http://example.test/assets/site.css")),
        )
        assertEquals(
            "http://example.test/other",
            routes.cleanHttpReference(flow, URI("https://example.test:8443/other")),
        )
    }

    @Test
    fun `session mappings are isolated by PPP generation and cleaned on reconnect`() {
        val routes = LegacyOriginRouteTable()
        val legacy = URI("http://example.test/app")
        val upstream = URI("https://example.test/app")
        val firstSession = httpFlow(generation = 7)
        val nextSession = httpFlow(generation = 8)

        routes.remember(firstSession, legacy, upstream)

        assertEquals(URI("https://example.test/next"), routes.resolve(firstSession, URI("http://example.test/next")))
        assertEquals(URI("http://example.test/next"), routes.resolve(nextSession, URI("http://example.test/next")))

        routes.invalidateBefore(8)

        assertEquals(URI("http://example.test/next"), routes.resolve(firstSession, URI("http://example.test/next")))
        assertNull(routes.cleanHttpReference(firstSession, URI("https://example.test/next")))
    }

    private fun httpFlow(generation: Long): TcpProxyFlow =
        TcpProxyFlow(
            key = TcpFlowKey(
                peerAddress = Ipv4Address.parse("10.0.0.2"),
                peerPort = 2300,
                remoteAddress = Ipv4Address.parse("127.0.0.1"),
                remotePort = 80,
            ),
            generation = generation,
        )
}
