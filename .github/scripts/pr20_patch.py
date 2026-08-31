from pathlib import Path

main = Path('src/main/kotlin/no/skasti/serialmodem/ppp/HttpCompatibilityProxy.kt')
s = main.read_text()
s = s.replace('import java.nio.charset.StandardCharsets\n', 'import java.nio.charset.Charset\nimport java.nio.charset.StandardCharsets\n')
s = s.replace('''                append("http://")
                uri.rawUserInfo?.let { append(it).append('@') }
                append(formatHost(requireNotNull(uri.host)))''', '''                append("http://")
                append(formatHost(requireNotNull(uri.host).lowercase(Locale.ROOT)))''', 1)
old = '''    fun effectivePort(uri: URI): Int =
        when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
}'''
new = '''    fun effectivePort(uri: URI): Int =
        when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }

    fun requestObservableKey(uri: URI): String =
        URI(
            buildString {
                val scheme = requireNotNull(uri.scheme).lowercase(Locale.ROOT)
                append(scheme).append("://")
                append(formatHost(requireNotNull(uri.host).lowercase(Locale.ROOT)))
                val port = effectivePort(uri)
                val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
                if (!defaultPort) append(':').append(port)
                append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                uri.rawQuery?.let { append('?').append(it) }
            },
        ).toString()
}

internal fun rewriteEncodedTextBody(
    headers: Map<String, List<String>>,
    body: ByteArray,
    transform: (String) -> String,
): ByteArray {
    val contentType = headers.entries
        .firstOrNull { (name, _) -> name.equals("content-type", ignoreCase = true) }
        ?.value
        ?.firstOrNull()
    val declaredCharset = contentType
        ?.let { CHARSET_PARAMETER_PATTERN.find(it) }
        ?.let { match -> match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }
        ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
    val charset = declaredCharset ?: bomCharset(body) ?: StandardCharsets.ISO_8859_1
    val source = body.toString(charset)
    val rewritten = transform(source)
    return if (rewritten == source) body else rewritten.toByteArray(charset)
}

private fun bomCharset(body: ByteArray): Charset? =
    when {
        body.size >= 4 && body[0] == 0x00.toByte() && body[1] == 0x00.toByte() &&
            body[2] == 0xFE.toByte() && body[3] == 0xFF.toByte() -> Charset.forName("UTF-32BE")
        body.size >= 4 && body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte() &&
            body[2] == 0x00.toByte() && body[3] == 0x00.toByte() -> Charset.forName("UTF-32LE")
        body.size >= 3 && body[0] == 0xEF.toByte() && body[1] == 0xBB.toByte() && body[2] == 0xBF.toByte() -> StandardCharsets.UTF_8
        body.size >= 2 && body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte() -> StandardCharsets.UTF_16
        body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte() -> StandardCharsets.UTF_16
        else -> null
    }

private val CHARSET_PARAMETER_PATTERN =
    Regex("""(?i)(?:^|;)\s*charset\s*=\s*(?:\"([^\"]+)\"|'([^']+)'|([^;\s]+))""")'''
if old not in s:
    raise SystemExit('LegacyHttpUrl insertion point not found')
s = s.replace(old, new, 1)
old = '            legacyUri = LegacyHttpUrl.withoutFragment(legacyUri).toString(),'
new = '            legacyUri = LegacyHttpUrl.requestObservableKey(legacyUri),'
if old not in s:
    raise SystemExit('exactKey replacement point not found')
s = s.replace(old, new, 1)
old = '''        val source = body.toString(StandardCharsets.ISO_8859_1)
        val rewritten = rewriteHttpsReferences(flow, upstreamBase, source)
        return if (rewritten == source) body else rewritten.toByteArray(StandardCharsets.ISO_8859_1)'''
new = '''        return rewriteEncodedTextBody(headers, body) { source ->
            rewriteHttpsReferences(flow, upstreamBase, source)
        }'''
if old not in s:
    raise SystemExit('rewriteLegacyBody replacement point not found')
s = s.replace(old, new, 1)
main.write_text(s)

test = Path('src/test/kotlin/no/skasti/serialmodem/ppp/LegacyHttpsUrlCodecTest.kt')
t = test.read_text()
marker = '''    @Test
    fun `HTTPS references are exposed as clean HTTP URLs and remembered exactly`() {'''
additions = '''    @Test
    fun `clean HTTPS userinfo link is keyed by the browser observable URL`() {
        val routes = LegacyOriginRouteTable()
        val flow = httpFlow(generation = 11)
        val upstream = URI("https://alice:secret@Example.TEST/private")

        val legacy = routes.rememberHttpsReference(flow, upstream)

        assertEquals("http://example.test/private", legacy)
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

'''
if marker not in t:
    raise SystemExit('test insertion point not found')
t = t.replace(marker, additions + marker, 1)
test.write_text(t)
