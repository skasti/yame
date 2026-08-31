package no.skasti.serialmodem.ppp

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyOriginRouteTableTest {
    @Test
    fun `protocol-relative matcher accepts single-label hosts`() {
        val source = "//localhost/next //router/status //example.test/path //127.0.0.1/x //[::1]/y"
        assertEquals(
            listOf(
                "//localhost/next",
                "//router/status",
                "//example.test/path",
                "//127.0.0.1/x",
                "//[::1]/y",
            ),
            LEGACY_PROTOCOL_RELATIVE_URL_PATTERN.findAll(source).map { it.value }.toList(),
        )
    }

    @Test
    fun `HTTPS references are exposed as clean HTTP URLs and remembered exactly`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 1)

        val legacy = routes.rememberHttpsReference(
            flow,
            URI("https://example.test/path?q=1#fragment"),
        )

        assertEquals("http://example.test/path?q=1#fragment", legacy)
        assertEquals(
            URI("https://example.test/path?q=1"),
            routes.resolve(flow, URI("http://example.test/path?q=1")),
        )
    }

    @Test
    fun `clean HTTP reference preserves a non-default HTTPS port internally`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 2)

        val legacy = routes.rememberHttpsReference(
            flow,
            URI("https://example.test:8443/path"),
        )

        assertEquals("http://example.test/path", legacy)
        assertEquals(
            URI("https://example.test:8443/path"),
            routes.resolve(flow, URI("http://example.test/path")),
        )
    }

    @Test
    fun `clean HTTP reference supports IPv6 HTTPS authorities`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 3)

        val legacy = routes.rememberHttpsReference(
            flow,
            URI("https://[2001:db8::1]:8443/path"),
        )

        assertEquals("http://[2001:db8::1]/path", legacy)
        assertEquals(
            URI("https://[2001:db8::1]:8443/path"),
            routes.resolve(flow, URI("http://[2001:db8::1]/path")),
        )
    }

    @Test
    fun `session route keeps root-relative requests on hidden HTTPS origin`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 4)
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
    }

    @Test
    fun `following a clean rewritten URL establishes its origin route`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 5)
        val upstream = URI("https://example.test:8443/app")
        val legacy = URI(routes.rememberHttpsReference(flow, upstream))

        assertEquals(URI("https://example.test:8443/app"), routes.resolve(flow, legacy))

        routes.remember(flow, legacy, upstream)

        assertEquals(
            URI("https://example.test:8443/assets/site.css"),
            routes.resolve(flow, URI("http://example.test/assets/site.css")),
        )
    }

    @Test
    fun `exact clean mappings can distinguish HTTPS ports until navigation establishes the origin`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 6)

        routes.rememberHttpsReference(flow, URI("https://example.test:443/standard"))
        routes.rememberHttpsReference(flow, URI("https://example.test:8443/alternate"))

        assertEquals(
            URI("https://example.test/standard"),
            routes.resolve(flow, URI("http://example.test/standard")),
        )
        assertEquals(
            URI("https://example.test:8443/alternate"),
            routes.resolve(flow, URI("http://example.test/alternate")),
        )
    }

    @Test
    fun `specific HTTPS and plain HTTP references override the general origin route`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 7)

        routes.rememberHttpsReference(flow, URI("https://example.test/home"))
        routes.rememberHttpsReference(flow, URI("https://example.test:8443/admin"))
        routes.rememberHttpReference(flow, URI("http://example.test/legacy"))

        assertEquals(
            URI("https://example.test/other"),
            routes.resolve(flow, URI("http://example.test/other")),
        )
        assertEquals(
            URI("https://example.test:8443/admin"),
            routes.resolve(flow, URI("http://example.test/admin")),
        )
        assertEquals(
            URI("http://example.test/legacy"),
            routes.resolve(flow, URI("http://example.test/legacy")),
        )
    }

    @Test
    fun `session mappings are isolated by PPP generation and cleaned on reconnect`() {
        val routes = LegacyOriginRouteTable()
        val firstSession = httpFlow(generation = 8)
        val nextSession = httpFlow(generation = 9)
        val clean = URI(routes.rememberHttpsReference(firstSession, URI("https://example.test/next")))

        assertEquals(URI("https://example.test/next"), routes.resolve(firstSession, clean))
        assertEquals(clean, routes.resolve(nextSession, clean))

        routes.invalidateBefore(9)

        assertEquals(clean, routes.resolve(firstSession, clean))
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
