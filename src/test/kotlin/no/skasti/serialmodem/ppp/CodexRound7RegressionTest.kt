package no.skasti.serialmodem.ppp

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class CodexRound7RegressionTest {
    @Test
    fun `exact routes survive later origin promotion to another HTTPS port`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(70)
        val standardLegacy = URI(routes.rememberHttpsReference(flow, URI("https://example.test/standard")))
        val alternateLegacy = URI(routes.rememberHttpsReference(flow, URI("https://example.test:8443/alternate")))

        val standardUpstream = routes.resolve(flow, standardLegacy)
        routes.remember(flow, standardLegacy, standardUpstream)
        val alternateUpstream = routes.resolve(flow, alternateLegacy)
        routes.remember(flow, alternateLegacy, alternateUpstream)

        assertEquals(URI("https://example.test/standard"), routes.resolve(flow, standardLegacy))
        assertEquals(URI("https://example.test:8443/alternate"), routes.resolve(flow, alternateLegacy))
    }

    @Test
    fun `absolute URL matcher keeps balanced closing parenthesis in path`() {
        val source = "href=\"https://example.test/wiki/Foo_(bar)\" url(https://example.test/plain)"
        assertEquals(
            listOf("https://example.test/wiki/Foo_(bar)", "https://example.test/plain"),
            ABSOLUTE_HTTP_URL_PATTERN.findAll(source).map { it.value }.toList(),
        )
    }

    @Test
    fun `injected XHTML base element is self closing`() {
        assertEquals(
            "<base href=\"http://example.test/app/\" />",
            injectedBaseTag("application/xhtml+xml", "http://example.test/app/"),
        )
        assertEquals(
            "<base href=\"http://example.test/app/\">",
            injectedBaseTag("text/html", "http://example.test/app/"),
        )
    }

    private fun httpFlow(generation: Long): TcpProxyFlow =
        TcpProxyFlow(
            key = TcpFlowKey(
                peerAddress = Ipv4Address.parse("10.0.0.2"),
                peerPort = 2370,
                remoteAddress = Ipv4Address.parse("127.0.0.1"),
                remotePort = 80,
            ),
            generation = generation,
        )
}
