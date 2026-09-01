from pathlib import Path

path = Path('src/main/kotlin/no/skasti/serialmodem/ppp/HttpCompatibilityProxy.kt')
s = path.read_text()

old = '''internal val LEGACY_PROTOCOL_RELATIVE_URL_PATTERN =
    Regex("""(?<![A-Za-z0-9_./:-])//(?:[^\\s/?#"'<>@]+@)?(?:\\[[^\\]]+\\]|[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*)(?::\\d+)?(?:[/?#][^\\s"'<>\\)]*)?""")'''
new = '''internal val LEGACY_PROTOCOL_RELATIVE_URL_PATTERN =
    Regex("""(?<![A-Za-z0-9_./:-])//(?:[^\\s/?#"'<>@]+@)?(?:\\[[^\\]]+\\]|[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*)(?::\\d+)?(?:[/?#](?:[^\\s"'<>\\(\\)]|\\([^\\(\\)\\s"'<>]*\\))*)?""")

internal val ABSOLUTE_HTTP_URL_PATTERN =
    Regex("""https?://(?:[^\\s/?#"'<>@]+@)?(?:\\[[^\\]]+\\]|[^\\s/:?#"'<>]+)(?::\\d+)?(?:[/?#](?:[^\\s"'<>\\(\\)]|\\([^\\(\\)\\s"'<>]*\\))*)?""", RegexOption.IGNORE_CASE)

internal fun injectedBaseTag(contentType: String?, escapedHref: String): String =
    if (contentType == "application/xhtml+xml") "<base href=\\\"$escapedHref\\\" />"
    else "<base href=\\\"$escapedHref\\\">"'''
assert old in s, 'protocol relative pattern block not found'
s = s.replace(old, new, 1)

old = '''        if (legacyOrigin != upstreamOrigin && exactTarget == LegacyHttpUrl.withoutFragment(upstreamUri)) {
            exactMappings.remove(exactKey, exactTarget)
        }

        return if (previous == upstreamOrigin || (previous == null && legacyOrigin == upstreamOrigin)) {'''
new = '''        return if (previous == upstreamOrigin || (previous == null && legacyOrigin == upstreamOrigin)) {'''
assert old in s, 'exact mapping removal block not found'
s = s.replace(old, new, 1)

old = '''        val targetOrigin = Origin.from(upstreamHttpsUri)
        val existingOrigin = originMappings[originKey(flow, legacyUri)]
        val exactKey = exactKey(flow, legacyUri)
        if (existingOrigin == targetOrigin) {
            exactMappings.remove(exactKey)
        } else {
            exactMappings[exactKey] = LegacyHttpUrl.withoutFragment(upstreamHttpsUri)
        }
        return legacyUri.toString()'''
new = '''        exactMappings[exactKey(flow, legacyUri)] = LegacyHttpUrl.withoutFragment(upstreamHttpsUri)
        return legacyUri.toString()'''
assert old in s, 'rememberHttpsReference block not found'
s = s.replace(old, new, 1)

old = '''            if (htmlContext) preserveDocumentBase(flow, legacyUri, upstreamBase, rewritten) else rewritten'''
new = '''            if (htmlContext) preserveDocumentBase(flow, legacyUri, upstreamBase, contentType, rewritten) else rewritten'''
assert old in s, 'preserveDocumentBase call not found'
s = s.replace(old, new, 1)

old = '''    private fun preserveDocumentBase(flow: TcpProxyFlow, legacyUri: URI, upstreamBase: URI, source: String): String {'''
new = '''    private fun preserveDocumentBase(
        flow: TcpProxyFlow,
        legacyUri: URI,
        upstreamBase: URI,
        contentType: String?,
        source: String,
    ): String {'''
assert old in s, 'preserveDocumentBase signature not found'
s = s.replace(old, new, 1)

old = '''        val baseTag = "<base href=\\\"${escapeHtmlAttributeUrl(effectiveLegacyBase)}\\\">"'''
new = '''        val baseTag = injectedBaseTag(contentType, escapeHtmlAttributeUrl(effectiveLegacyBase))'''
assert old in s, 'base tag construction not found'
s = s.replace(old, new, 1)

old = '''        val ABSOLUTE_HTTP_URL_PATTERN =
            Regex("""https?://(?:[^\\s/?#"'<>@]+@)?(?:\\[[^\\]]+\\]|[^\\s/:?#"'<>]+)(?::\\d+)?(?:[/?#][^\\s"'<>\\)]*)?""", RegexOption.IGNORE_CASE)
'''
assert old in s, 'companion absolute pattern not found'
s = s.replace(old, '', 1)

path.write_text(s)

test = Path('src/test/kotlin/no/skasti/serialmodem/ppp/CodexRound7RegressionTest.kt')
test.write_text('''package no.skasti.serialmodem.ppp

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
        val source = "href=\\\"https://example.test/wiki/Foo_(bar)\\\" url(https://example.test/plain)"
        assertEquals(
            listOf("https://example.test/wiki/Foo_(bar)", "https://example.test/plain"),
            ABSOLUTE_HTTP_URL_PATTERN.findAll(source).map { it.value }.toList(),
        )
    }

    @Test
    fun `injected XHTML base element is self closing`() {
        assertEquals(
            "<base href=\\\"http://example.test/app/\\\" />",
            injectedBaseTag("application/xhtml+xml", "http://example.test/app/"),
        )
        assertEquals(
            "<base href=\\\"http://example.test/app/\\\">",
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
''')
