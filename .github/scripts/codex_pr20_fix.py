from pathlib import Path

main_path = Path('src/main/kotlin/no/skasti/serialmodem/ppp/HttpCompatibilityProxy.kt')
test_path = Path('src/test/kotlin/no/skasti/serialmodem/ppp/LegacyHttpsUrlCodecTest.kt')

src = main_path.read_text()

old = '''private val HTML_URL_ATTRIBUTE_PATTERN =
    Regex("""(?is)\\b(?:href|src|action|formaction|poster|data|cite|background)\\s*=\\s*(?:(["'])(.*?)\\1|([^\\s"'=<>\\x60]+))""")'''
new = '''private val HTML_URL_ATTRIBUTE_PATTERN =
    Regex("""(?is)\\b(?:href|src|action|formaction|poster|data|cite|background|style)\\s*=\\s*(?:(["'])(.*?)\\1|([^\\s"'=<>\\x60]+))""")'''
assert old in src, 'URL attribute pattern not found'
src = src.replace(old, new, 1)

old = '''internal fun rewriteHtmlUrlContexts(
    source: String,
    rewriteAttribute: (String) -> String,
    rewriteRaw: (String) -> String,
): String {'''
new = '''internal fun rewriteHtmlUrlContexts(
    source: String,
    rewriteAttribute: (String) -> String,
    rewriteRaw: (String) -> String,
    rewriteBaseAttribute: ((String) -> String)? = null,
): String {'''
assert old in src, 'rewriteHtmlUrlContexts signature not found'
src = src.replace(old, new, 1)

old = '''        var tag = rewriteHtmlAttributeValues(tagMatch.value, HTML_URL_ATTRIBUTE_PATTERN, rewriteAttribute)
        if (HTML_META_REFRESH_PATTERN.matches(tagMatch.value)) {'''
new = '''        val attributeRewriter =
            if (HTML_BASE_HREF_PATTERN.matches(tagMatch.value)) rewriteBaseAttribute ?: rewriteAttribute
            else rewriteAttribute
        var tag = rewriteHtmlAttributeValues(tagMatch.value, HTML_URL_ATTRIBUTE_PATTERN, attributeRewriter)
        if (HTML_META_REFRESH_PATTERN.matches(tagMatch.value)) {'''
assert old in src, 'tag attribute rewrite block not found'
src = src.replace(old, new, 1)

old = '''    fun rememberHttpsReference(flow: TcpProxyFlow, upstreamHttpsUri: URI): String {
        require(upstreamHttpsUri.scheme.equals("https", ignoreCase = true)) {
            "Compatibility reference target must be HTTPS"
        }
        val legacyUri = LegacyHttpUrl.mirrorOf(upstreamHttpsUri)
        exactMappings[exactKey(flow, legacyUri)] = LegacyHttpUrl.withoutFragment(upstreamHttpsUri)
        return legacyUri.toString()
    }
'''
new = old + '''
    fun rememberHttpsBaseReference(flow: TcpProxyFlow, upstreamHttpsUri: URI): String {
        val legacyUri = URI(rememberHttpsReference(flow, upstreamHttpsUri))
        remember(flow, legacyUri, upstreamHttpsUri)
        return legacyUri.toString()
    }
'''
assert old in src, 'rememberHttpsReference block not found'
src = src.replace(old, new, 1)

old = '''    private fun rewriteHtmlReferences(flow: TcpProxyFlow, upstreamBase: URI, source: String): String =
        rewriteHtmlUrlContexts(
            source = source,
            rewriteAttribute = { rewriteHttpsReferences(flow, upstreamBase, it, htmlContext = true) },
            rewriteRaw = { rewriteHttpsReferences(flow, upstreamBase, it, htmlContext = false) },
        )
'''
new = '''    private fun rewriteHtmlReferences(flow: TcpProxyFlow, upstreamBase: URI, source: String): String =
        rewriteHtmlUrlContexts(
            source = source,
            rewriteAttribute = { rewriteHttpsReferences(flow, upstreamBase, it, htmlContext = true) },
            rewriteRaw = { rewriteHttpsReferences(flow, upstreamBase, it, htmlContext = false) },
            rewriteBaseAttribute = { rewriteHtmlBaseReference(flow, upstreamBase, it) },
        )

    private fun rewriteHtmlBaseReference(flow: TcpProxyFlow, upstreamBase: URI, value: String): String {
        val decoded = decodeHtmlEntities(value)
        val rawUri = runCatching { URI(decoded) }.getOrNull()
        val target = runCatching { upstreamBase.resolve(decoded) }.getOrNull()
        val explicitAuthority = rawUri?.isAbsolute == true || decoded.startsWith("//")
        if (explicitAuthority && target?.host != null && target.scheme.equals("https", ignoreCase = true)) {
            return escapeHtmlAttributeUrl(originRoutes.rememberHttpsBaseReference(flow, target))
        }
        return rewriteHttpsReferences(flow, upstreamBase, value, htmlContext = true)
    }
'''
assert old in src, 'rewriteHtmlReferences block not found'
src = src.replace(old, new, 1)

main_path.write_text(src)

test = test_path.read_text()
marker = '''    @Test
    fun `HTTPS references are exposed as clean HTTP URLs and remembered exactly`() {'''
addition = '''    @Test
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
        val source = "<div style=\\\"background:url(https://secure.test/a.gif)\\\">x</div>"
        val rewritten = rewriteHtmlUrlContexts(
            source = source,
            rewriteAttribute = { it.replace("https://", "http://") },
            rewriteRaw = { it },
        )

        assertEquals("<div style=\\\"background:url(http://secure.test/a.gif)\\\">x</div>", rewritten)
    }

'''
assert marker in test, 'test insertion marker not found'
test = test.replace(marker, addition + marker, 1)
test_path.write_text(test)
