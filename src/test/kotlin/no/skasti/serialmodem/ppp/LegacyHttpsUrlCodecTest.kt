package no.skasti.serialmodem.ppp

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyOriginRouteTableTest {
    @Test
    fun `absolute URL stops before a CSS closing parenthesis`() {
        val match = requireNotNull(ABSOLUTE_HTTP_URL_PATTERN.find("background:url(https://secure.test)"))

        assertEquals("https://secure.test", match.value)
    }

    @Test
    fun `scheme-only HTTPS upgrade stays internal`() {
        val legacy = URI("http://example.test/app?q=1")
        val upstream = URI("https://example.test/app?q=1")

        assertFalse(shouldExposeRedirect(legacy, upstream))
        assertEquals(legacy, legacyRedirectUri(upstream))
    }

    @Test
    fun `redirect changing path is exposed as clean HTTP`() {
        val legacy = URI("http://example.test/start")
        val upstream = URI("https://example.test/app/index.html")

        assertTrue(shouldExposeRedirect(legacy, upstream))
        assertEquals(URI("http://example.test/app/index.html"), legacyRedirectUri(upstream))
    }

    @Test
    fun `redirect changing host is exposed as clean HTTP`() {
        val legacy = URI("http://old.example/start")
        val upstream = URI("https://new.example/app")

        assertTrue(shouldExposeRedirect(legacy, upstream))
        assertEquals(URI("http://new.example/app"), legacyRedirectUri(upstream))
    }

    @Test
    fun `fragment-only redirect is exposed to the browser`() {
        val legacy = URI("http://example.test/app")
        val upstream = URI("http://example.test/app#section")

        assertTrue(shouldExposeRedirect(legacy, upstream))
        assertEquals(upstream, legacyRedirectUri(upstream))
    }

    @Test
    fun `redirect method follows legacy browser compatible semantics`() {
        assertEquals("GET", redirectedMethod(302, "POST"))
        assertEquals("GET", redirectedMethod(303, "POST"))
        assertEquals("HEAD", redirectedMethod(303, "HEAD"))
        assertEquals("POST", redirectedMethod(307, "POST"))
    }

    @Test
    fun `cookie overrides evict the oldest identity at the session limit`() {
        val overrides = BoundedCookieOverrides(maxEntries = 2)
        val first = CookieOverride("first", "example.test", "/", secure = false)
        val second = CookieOverride("second", "example.test", "/", secure = false)
        val third = CookieOverride("third", "example.test", "/", secure = false)

        overrides.put(first)
        overrides.put(second)
        overrides.put(third)

        assertFalse(overrides.any { it == first })
        assertTrue(overrides.any { it == second })
        assertTrue(overrides.any { it == third })
    }

    @Test
    fun `replacing a cookie override refreshes its eviction order`() {
        val overrides = BoundedCookieOverrides(maxEntries = 2)
        val first = CookieOverride("session", "example.test", "/", secure = false)
        val replacement = first.copy(secure = true)
        val second = CookieOverride("second", "example.test", "/", secure = false)
        val third = CookieOverride("third", "example.test", "/", secure = false)

        overrides.put(first)
        overrides.put(second)
        overrides.put(replacement)
        overrides.put(third)

        assertTrue(overrides.any { it == replacement })
        assertFalse(overrides.any { it == second })
        assertTrue(overrides.any { it == third })
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
    fun `HTTPS references are exposed as clean HTTP URLs and remembered exactly`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 1)
        val upstream = URI("https://example.test/path?q=1#fragment")

        val legacy = routes.rememberHttpsReference(flow, upstream)

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

        val legacy = routes.rememberHttpsReference(flow, URI("https://example.test:8443/path"))

        assertEquals("http://example.test/path", legacy)
        assertEquals(
            URI("https://example.test:8443/path"),
            routes.resolve(flow, URI("http://example.test/path")),
        )
    }

    @Test
    fun `session route keeps root-relative requests on hidden HTTPS origin`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 4)
        val legacyPage = URI("http://example.test/app")
        val upstreamPage = URI("https://example.test:8443/app")

        routes.remember(flow, legacyPage, upstreamPage)

        assertEquals(
            URI("https://example.test:8443/assets/logo.gif?size=2"),
            routes.resolve(flow, URI("http://example.test/assets/logo.gif?size=2")),
        )
    }

    @Test
    fun `specific plain HTTP reference overrides an established HTTPS origin route`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 7)
        val upstreamHome = URI("https://example.test/home")
        val legacyHome = URI(routes.rememberHttpsReference(flow, upstreamHome))
        routes.remember(flow, legacyHome, upstreamHome)
        routes.rememberHttpReference(flow, URI("http://example.test/legacy"))

        assertEquals(
            URI("https://example.test/other"),
            routes.resolve(flow, URI("http://example.test/other")),
        )
        assertEquals(
            URI("http://example.test/legacy"),
            routes.resolve(flow, URI("http://example.test/legacy")),
        )
    }

    @Test
    fun `exact mappings evict the oldest entry at the per-session limit`() {
        val routes = LegacyOriginRouteTable(maxExactMappingsPerSession = 2)
        val flow = httpFlow(generation = 11)
        val first = URI(routes.rememberHttpsReference(flow, URI("https://example.test/first")))
        val second = URI(routes.rememberHttpsReference(flow, URI("https://example.test/second")))
        val third = URI(routes.rememberHttpsReference(flow, URI("https://example.test/third")))

        assertEquals(first, routes.resolve(flow, first))
        assertEquals(URI("https://example.test/second"), routes.resolve(flow, second))
        assertEquals(URI("https://example.test/third"), routes.resolve(flow, third))
    }

    @Test
    fun `origin mappings evict the oldest host at the per-session limit`() {
        val routes = LegacyOriginRouteTable(maxOriginMappingsPerSession = 2)
        val flow = httpFlow(generation = 12)
        val first = URI("http://first.test/page")
        val second = URI("http://second.test/page")
        val third = URI("http://third.test/page")

        routes.remember(flow, first, URI("https://upstream-first.test/page"))
        routes.remember(flow, second, URI("https://upstream-second.test/page"))
        routes.remember(flow, third, URI("https://upstream-third.test/page"))

        assertEquals(first, routes.resolve(flow, first))
        assertEquals(URI("https://upstream-second.test/page"), routes.resolve(flow, second))
        assertEquals(URI("https://upstream-third.test/page"), routes.resolve(flow, third))
    }

    @Test
    fun `session mappings are isolated and cleaned on reconnect`() {
        val routes = LegacyOriginRouteTable()
        val firstSession = httpFlow(generation = 8)
        val nextSession = httpFlow(generation = 9)
        val clean = URI(routes.rememberHttpsReference(firstSession, URI("https://example.test/next")))

        assertEquals(URI("https://example.test/next"), routes.resolve(firstSession, clean))
        assertEquals(clean, routes.resolve(nextSession, clean))

        routes.invalidateBefore(9)

        assertEquals(clean, routes.resolve(firstSession, clean))
    }

    @Test
    fun `rewritable text honors its declared charset`() {
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
