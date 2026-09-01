package no.skasti.serialmodem.ppp

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyOriginRouteTableTest {
    @Test
    fun `protocol-relative matcher accepts single-label hosts without matching path double slashes`() {
        val source =
            "//localhost/next href=\"//router/status\" //example.test/path //127.0.0.1/x //[::1]/y " +
                "href=\"/files//server/share\""
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
    fun `seeing an HTTPS link does not reroute unrelated HTTP requests until it is followed`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 10)
        val upstream = URI("https://example.test/login")
        val legacy = URI(routes.rememberHttpsReference(flow, upstream))

        assertEquals(
            URI("http://example.test/assets/site.css"),
            routes.resolve(flow, URI("http://example.test/assets/site.css")),
        )
        assertEquals(upstream, routes.resolve(flow, legacy))

        routes.remember(flow, legacy, upstream)

        assertEquals(
            URI("https://example.test/assets/site.css"),
            routes.resolve(flow, URI("http://example.test/assets/site.css")),
        )
    }

    @Test
    fun `clean HTTPS userinfo link maps an origin-form browser request`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 11)
        val upstream = URI("https://alice:secret@Example.TEST/private")

        val legacy = routes.rememberHttpsReference(flow, upstream)

        assertEquals("http://alice:secret@Example.TEST/private", legacy)
        assertEquals(
            URI("https://alice:secret@Example.TEST/private"),
            routes.resolve(flow, URI("http://example.test/private")),
        )
    }

    @Test
    fun `exact mapping keys normalize hostname case`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 12)
        val upstream = URI("https://example.test/x")

        routes.rememberExact(flow, URI("http://Example.TEST/x"), upstream)

        assertEquals(upstream, routes.resolve(flow, URI("http://example.test/x")))
    }

    @Test
    fun `rewritable text honors declared UTF-16 charset`() {
        val body = "<a href=\"https://example.test/next\">next</a>".toByteArray(Charsets.UTF_16LE)
        val rewritten = rewriteEncodedTextBody(
            mapOf("Content-Type" to listOf("text/html; charset=UTF-16LE")),
            body,
        ) { it.replace("https://", "http://") }

        assertEquals(
            "<a href=\"http://example.test/next\">next</a>",
            rewritten.toString(Charsets.UTF_16LE),
        )
    }

    @Test
    fun `HTTPS base reference establishes routing for descendant URLs without requesting the base`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 13)

        val legacyBase = routes.rememberHttpsBaseReference(flow, URI("https://cdn.test/app/"))

        assertEquals("http://cdn.test/app/", legacyBase)
        assertEquals(
            URI("https://cdn.test/app/asset.gif"),
            routes.resolve(flow, URI("http://cdn.test/app/asset.gif")),
        )
    }

    @Test
    fun `inline style URL attributes are included in the HTML attribute rewrite pass`() {
        val source = "<div style=\"background:url(https://secure.test/a.gif)\">x</div>"
        val rewritten = rewriteHtmlUrlContexts(
            source = source,
            rewriteAttribute = { it.replace("https://", "http://") },
            rewriteRaw = { it },
        )

        assertEquals("<div style=\"background:url(http://secure.test/a.gif)\">x</div>", rewritten)
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
    fun `specific HTTPS and plain HTTP references override the established origin route`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 7)
        val upstreamHome = URI("https://example.test/home")
        val legacyHome = URI(routes.rememberHttpsReference(flow, upstreamHome))

        routes.remember(flow, legacyHome, upstreamHome)
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
        val legacyException = URI("http://example.test/legacy")
        assertEquals(legacyException, routes.resolve(flow, legacyException))

        routes.remember(flow, legacyException, legacyException)

        assertEquals(
            URI("https://example.test/other"),
            routes.resolve(flow, URI("http://example.test/other")),
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
