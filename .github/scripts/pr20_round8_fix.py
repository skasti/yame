from pathlib import Path

path = Path('src/main/kotlin/no/skasti/serialmodem/ppp/HttpCompatibilityProxy.kt')
text = path.read_text()

text = text.replace(
    '    val maxRequestBytes: Int = 0xffff,',
    '    val maxRequestBytes: Int = 16 * 1024 * 1024,',
)
text = text.replace(
    '        require(maxRequestBytes in 1..0xffff) { "HTTP compatibility maxRequestBytes must be 1..65535" }',
    '        require(maxRequestBytes > 0) { "HTTP compatibility maxRequestBytes must be positive" }',
)

marker = '''private val HTML_URL_ATTRIBUTE_PATTERN =
    Regex("""(?is)\\b(?:href|src|action|formaction|poster|data|cite|background)\\s*=\\s*(["'])(.*?)\\1""")
private val HTML_HTML_PATTERN = Regex("""(?is)<html\\b[^>]*>""")'''
replacement = '''private val HTML_URL_ATTRIBUTE_PATTERN =
    Regex("""(?is)\\b(?:href|src|action|formaction|poster|data|cite|background)\\s*=\\s*(["'])(.*?)\\1""")
private val HTML_TAG_PATTERN = Regex("""(?is)<(?:[^"'<>]|"[^"]*"|'[^']*')+>""")
private val HTML_HTML_PATTERN = Regex("""(?is)<html\\b[^>]*>""")

internal fun rewriteHtmlUrlContexts(
    source: String,
    rewriteAttribute: (String) -> String,
    rewriteRaw: (String) -> String,
): String {
    val output = StringBuilder(source.length)
    var cursor = 0
    HTML_TAG_PATTERN.findAll(source).forEach { tagMatch ->
        if (cursor < tagMatch.range.first) {
            output.append(rewriteRaw(source.substring(cursor, tagMatch.range.first)))
        }

        var tag = tagMatch.value
        HTML_URL_ATTRIBUTE_PATTERN.findAll(tagMatch.value).toList().asReversed().forEach { attributeMatch ->
            val valueGroup = attributeMatch.groups[2] ?: return@forEach
            val replacementValue = rewriteAttribute(valueGroup.value)
            if (replacementValue != valueGroup.value) {
                tag = tag.replaceRange(valueGroup.range, replacementValue)
            }
        }
        output.append(tag)
        cursor = tagMatch.range.last + 1
    }
    if (cursor < source.length) output.append(rewriteRaw(source.substring(cursor)))
    return output.toString()
}'''
if marker not in text:
    raise SystemExit('HTML pattern marker not found')
text = text.replace(marker, replacement)

old = '''    private fun rewriteHtmlReferences(flow: TcpProxyFlow, upstreamBase: URI, source: String): String {
        var rewritten = source
        HTML_URL_ATTRIBUTE_PATTERN.findAll(source).toList().asReversed().forEach { match ->
            val valueGroup = match.groups[2] ?: return@forEach
            val replacement = rewriteHttpsReferences(flow, upstreamBase, valueGroup.value, htmlContext = true)
            if (replacement != valueGroup.value) {
                rewritten = rewritten.replaceRange(valueGroup.range, replacement)
            }
        }
        return rewriteHttpsReferences(flow, upstreamBase, rewritten, htmlContext = false)
    }'''
new = '''    private fun rewriteHtmlReferences(flow: TcpProxyFlow, upstreamBase: URI, source: String): String =
        rewriteHtmlUrlContexts(
            source = source,
            rewriteAttribute = { rewriteHttpsReferences(flow, upstreamBase, it, htmlContext = true) },
            rewriteRaw = { rewriteHttpsReferences(flow, upstreamBase, it, htmlContext = false) },
        )'''
if old not in text:
    raise SystemExit('rewriteHtmlReferences block not found')
text = text.replace(old, new)
path.write_text(text)

Path('src/test/kotlin/no/skasti/serialmodem/ppp/CodexRound8RegressionTest.kt').write_text(r'''package no.skasti.serialmodem.ppp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexRound8RegressionTest {
    @Test
    fun `HTML attributes are rewritten once while script raw text stays raw`() {
        val source =
            "<a href=\"https://example.test/next?a=1&amp;b=2\">next</a>" +
                "<script>window.location.href = \"https://api.test/x?a=1&b=2\"</script>"

        val rewritten = rewriteHtmlUrlContexts(
            source = source,
            rewriteAttribute = { value -> value.replace("https://", "attr://") },
            rewriteRaw = { value -> value.replace("https://", "raw://") },
        )

        assertEquals(
            "<a href=\"attr://example.test/next?a=1&amp;b=2\">next</a>" +
                "<script>window.location.href = \"raw://api.test/x?a=1&b=2\"</script>",
            rewritten,
        )
    }

    @Test
    fun `default compatibility request limit no longer rejects normal uploads above 64 KiB`() {
        assertTrue(PppHttpCompatibilityConfig().maxRequestBytes > 0xffff)
        assertEquals(128 * 1024, PppHttpCompatibilityConfig(maxRequestBytes = 128 * 1024).maxRequestBytes)
    }
}
''')
