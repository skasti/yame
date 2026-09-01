from pathlib import Path

src_path = Path('src/main/kotlin/no/skasti/serialmodem/ppp/HttpCompatibilityProxy.kt')
src = src_path.read_text()

def replace_once(old, new):
    global src
    if old not in src:
        raise SystemExit(f'missing source snippet: {old[:120]!r}')
    src = src.replace(old, new, 1)

replace_once(
'''    private data class FinalResponse(\n        val uri: URI,\n        val statusCode: Int,''',
'''    private data class FinalResponse(\n        val legacyUri: URI,\n        val uri: URI,\n        val statusCode: Int,''')

replace_once(
'''            return FinalResponse(\n                uri = uri,''',
'''            return FinalResponse(\n                legacyUri = legacyUri,\n                uri = uri,''')

replace_once(
'''        val legacyBody =\n            bufferedBody?.let { rewriteLegacyBody(state.flow, response.uri, response.headers, it) }''',
'''        val legacyBody =\n            bufferedBody?.let { rewriteLegacyBody(state.flow, response.legacyUri, response.uri, response.headers, it) }''')

replace_once(
'''    private fun rewriteLegacyBody(\n        flow: TcpProxyFlow,\n        upstreamBase: URI,\n        headers: Map<String, List<String>>,\n        body: ByteArray,\n    ): ByteArray {\n        if (body.isEmpty() || !bodyCanContainNavigableUrls(headers)) return body\n        return rewriteEncodedTextBody(headers, body) { source ->\n            rewriteHttpsReferences(flow, upstreamBase, source)\n        }\n    }''',
'''    private fun rewriteLegacyBody(\n        flow: TcpProxyFlow,\n        legacyUri: URI,\n        upstreamBase: URI,\n        headers: Map<String, List<String>>,\n        body: ByteArray,\n    ): ByteArray {\n        if (body.isEmpty() || !bodyCanContainNavigableUrls(headers)) return body\n        val contentType = firstHeader(headers, "content-type")\n            ?.substringBefore(';')\n            ?.trim()\n            ?.lowercase(Locale.ROOT)\n        val htmlContext = contentType in NAVIGATION_CONTENT_TYPES\n        return rewriteEncodedTextBody(headers, body) { source ->\n            val rewritten = rewriteHttpsReferences(flow, upstreamBase, source, htmlContext)\n            if (htmlContext) preserveDocumentBase(flow, legacyUri, upstreamBase, rewritten) else rewritten\n        }\n    }''')

replace_once(
'''    private fun rewriteHttpsReferences(flow: TcpProxyFlow, upstreamBase: URI, value: String): String {\n        var rewritten = ABSOLUTE_HTTP_URL_PATTERN.replace(value) { match ->\n            val target = runCatching { URI(match.value) }.getOrNull()\n            when {\n                target?.host == null -> match.value\n                target.scheme.equals("https", ignoreCase = true) -> originRoutes.rememberHttpsReference(flow, target)\n                target.scheme.equals("http", ignoreCase = true) -> originRoutes.rememberHttpReference(flow, target)\n                else -> match.value\n            }\n        }\n\n        rewritten = LEGACY_PROTOCOL_RELATIVE_URL_PATTERN.replace(rewritten) { match ->\n            val scheme = if (upstreamBase.scheme.equals("https", ignoreCase = true)) "https" else "http"\n            val target = runCatching { URI("$scheme:${match.value}") }.getOrNull()\n            when {\n                target?.host == null -> match.value\n                target.scheme.equals("https", ignoreCase = true) -> originRoutes.rememberHttpsReference(flow, target)\n                else -> {\n                    originRoutes.rememberHttpReference(flow, target)\n                    match.value\n                }\n            }\n        }\n        return rewritten\n    }''',
'''    private fun rewriteHttpsReferences(\n        flow: TcpProxyFlow,\n        upstreamBase: URI,\n        value: String,\n        htmlContext: Boolean = false,\n    ): String {\n        var rewritten = ABSOLUTE_HTTP_URL_PATTERN.replace(value) { match ->\n            val rawTarget = if (htmlContext) decodeHtmlEntities(match.value) else match.value\n            val target = runCatching { URI(rawTarget) }.getOrNull()\n            val replacement = when {\n                target?.host == null -> null\n                target.scheme.equals("https", ignoreCase = true) -> originRoutes.rememberHttpsReference(flow, target)\n                target.scheme.equals("http", ignoreCase = true) -> originRoutes.rememberHttpReference(flow, target)\n                else -> null\n            }\n            replacement?.let { if (htmlContext) escapeHtmlAttributeUrl(it) else it } ?: match.value\n        }\n\n        rewritten = LEGACY_PROTOCOL_RELATIVE_URL_PATTERN.replace(rewritten) { match ->\n            val scheme = if (upstreamBase.scheme.equals("https", ignoreCase = true)) "https" else "http"\n            val rawTarget = if (htmlContext) decodeHtmlEntities(match.value) else match.value\n            val target = runCatching { URI("$scheme:$rawTarget") }.getOrNull()\n            when {\n                target?.host == null -> match.value\n                target.scheme.equals("https", ignoreCase = true) -> {\n                    val replacement = originRoutes.rememberHttpsReference(flow, target)\n                    if (htmlContext) escapeHtmlAttributeUrl(replacement) else replacement\n                }\n                else -> {\n                    originRoutes.rememberHttpReference(flow, target)\n                    match.value\n                }\n            }\n        }\n        return rewritten\n    }\n\n    private fun preserveDocumentBase(flow: TcpProxyFlow, legacyUri: URI, upstreamBase: URI, source: String): String {\n        val effectiveLegacyBase = if (upstreamBase.scheme.equals("https", ignoreCase = true)) {\n            originRoutes.rememberHttpsReference(flow, LegacyHttpUrl.withoutFragment(upstreamBase))\n        } else {\n            LegacyHttpUrl.withoutFragment(upstreamBase).toString()\n        }\n        if (LegacyHttpUrl.requestObservableKey(legacyUri) == LegacyHttpUrl.requestObservableKey(URI(effectiveLegacyBase))) {\n            return source\n        }\n\n        val existing = HTML_BASE_HREF_PATTERN.find(source)\n        if (existing != null) {\n            val rawHref = existing.groupValues[2]\n            val upstreamTarget = runCatching { upstreamBase.resolve(decodeHtmlEntities(rawHref)) }.getOrNull() ?: return source\n            val replacement = if (upstreamTarget.scheme.equals("https", true)) {\n                originRoutes.rememberHttpsReference(flow, upstreamTarget)\n            } else {\n                originRoutes.rememberHttpReference(flow, upstreamTarget)\n            }\n            val escaped = escapeHtmlAttributeUrl(replacement)\n            val valueStart = existing.range.first + existing.value.indexOf(rawHref)\n            return source.replaceRange(valueStart, valueStart + rawHref.length, escaped)\n        }\n\n        val baseTag = "<base href=\\\"${escapeHtmlAttributeUrl(effectiveLegacyBase)}\\\">"\n        val head = HTML_HEAD_PATTERN.find(source)\n        if (head != null) return source.substring(0, head.range.last + 1) + baseTag + source.substring(head.range.last + 1)\n        val html = HTML_HTML_PATTERN.find(source)\n        if (html != null) {\n            val insertAt = html.range.last + 1\n            return source.substring(0, insertAt) + "<head>$baseTag</head>" + source.substring(insertAt)\n        }\n        return baseTag + source\n    }\n\n    private fun decodeHtmlEntities(value: String): String =\n        HTML_ENTITY_PATTERN.replace(value) { match ->\n            when (val entity = match.groupValues[1]) {\n                "amp" -> "&"\n                "quot" -> "\\\""\n                "apos" -> "'"\n                "lt" -> "<"\n                "gt" -> ">"\n                else -> {\n                    val codePoint = when {\n                        entity.startsWith("#x", true) -> entity.substring(2).toIntOrNull(16)\n                        entity.startsWith('#') -> entity.substring(1).toIntOrNull()\n                        else -> null\n                    }\n                    codePoint?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: match.value\n                }\n            }\n        }\n\n    private fun escapeHtmlAttributeUrl(value: String): String =\n        value.replace("&", "&amp;").replace("\\\"", "&quot;")''')

# Insert regex constants near other top-level patterns.
needle = '''private val CHARSET_PARAMETER_PATTERN =\n    Regex("""(?i)(?:^|;)\\s*charset\\s*=\\s*(?:"([^\"]+)"|'([^']+)'|([^;\\s]+))""")\n'''
addition = needle + '''\nprivate val HTML_ENTITY_PATTERN = Regex("""&([A-Za-z]+|#[0-9]+|#x[0-9A-Fa-f]+);""")\nprivate val HTML_BASE_HREF_PATTERN = Regex("""(?is)<base\\b[^>]*?\\bhref\\s*=\\s*(["'])(.*?)\\1""")\nprivate val HTML_HEAD_PATTERN = Regex("""(?is)<head\\b[^>]*>""")\nprivate val HTML_HTML_PATTERN = Regex("""(?is)<html\\b[^>]*>""")\n'''
if needle not in src:
    raise SystemExit('missing charset pattern insertion point')
src = src.replace(needle, addition, 1)

src_path.write_text(src)

# Append focused regression tests before helper methods.
test_path = Path('src/test/kotlin/no/skasti/serialmodem/ppp/HttpCompatibilityReviewRegressionTest.kt')
t = test_path.read_text()
marker = '''    private fun request(proxy: SystemHttpCompatibilityProxy, peerPort: Int, hostPort: Int, path: String): String {'''
if marker not in t:
    raise SystemExit('missing test insertion marker')
new_tests = r'''    @Test
    fun `hidden redirect preserves final document path as browser base`() {
        val server = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
        val thread = Thread {
            server.use { listening ->
                listening.accept().use { socket ->
                    readRequest(socket)
                    writeResponse(
                        socket,
                        "HTTP/1.1 302 Found\r\n" +
                            "Location: http://127.0.0.1:${listening.localPort}/app/index.html\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n",
                    )
                }
                listening.accept().use { socket ->
                    val request = readRequest(socket)
                    assertTrue(request.startsWith("GET /app/index.html HTTP/1.1"), request)
                    val body = "<html><head><title>x</title></head><body><img src=\"logo.png\"></body></html>"
                    writeResponse(
                        socket,
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                            "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body",
                    )
                }
            }
        }.apply { isDaemon = true; start() }
        val proxy = SystemHttpCompatibilityProxy(PppHttpCompatibilityConfig(requestTimeoutMillis = 2_000))
        try {
            val response = request(proxy, 2310, server.localPort, "/old")
            assertTrue(response.contains("<base href=\"http://127.0.0.1:${server.localPort}/app/index.html\">"), response)
        } finally {
            proxy.close()
            runCatching { server.close() }
            thread.join(2_000)
        }
    }

    @Test
    fun `HTML entity encoded HTTPS query is rewritten without changing entity spelling`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val body = "<a href=\"https://example.test/x?a=1&amp;b=2\">next</a>"
        val thread = serveOnce(server) {
            "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        }
        val proxy = SystemHttpCompatibilityProxy(PppHttpCompatibilityConfig(requestTimeoutMillis = 2_000))
        try {
            val response = request(proxy, 2311, server.localPort, "/")
            assertTrue(response.contains("http://example.test/x?a=1&amp;b=2"), response)
            assertTrue(!response.contains("a=1&amp;amp;b=2"), response)
        } finally {
            proxy.close()
            runCatching { server.close() }
            thread.join(2_000)
        }
    }

'''
t = t.replace(marker, new_tests + marker, 1)
test_path.write_text(t)
