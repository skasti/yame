package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.ppp.proxy.ReferenceRole
import no.skasti.yame.ppp.proxy.ResourceKind
import org.jsoup.Jsoup
import java.net.URI
import kotlin.test.*

class ReadablePageTransformerTest {
    private val uri = URI("https://example.test/project/page")
    private fun context(enabled: Boolean = true) = ResourceTransformationContext(
        emptySet(), URI("http://example.test/project/page"), uri, ResourceKind.DOCUMENT,
        null, ReferenceRole.NAVIGATION, emptyMap(), "GET", "test", { it.replace("https://", "http://") },
        HtmlTransformerSettings(readability = enabled),
    )
    private fun resource(html: String, charset: String = "UTF-8") = Resource(uri, ResourceRepresentation(
        200, mapOf("Content-Type" to listOf("text/html; charset=$charset"),
            "Content-Length" to listOf("999"), "Digest" to listOf("old")),
        html.toByteArray(charset(charset)),
    ))
    private fun fixture(name: String) = javaClass.getResource("/readability/$name.html")!!.readText()

    @Test fun `real adapter extracts news documentation and GitHub shaped README content`() {
        for (name in listOf("news", "documentation", "github")) {
            val original = resource(fixture(name))
            val result = defaultResourceTransformationPipeline(1_048_576).transform(context(), original).resource
            assertSame(original.source, result.source)
            val html = result.representation.body.toString(Charsets.UTF_8)
            val doc = Jsoup.parse(html)
            assertTrue(doc.body().text().contains("serial PPP connection"), name)
            assertFalse(html.contains("modernCode"), name)
            assertFalse(doc.body().text().contains("Sign in"), name)
            assertFalse(html.contains("<style"), name)
            assertEquals("http://example.test/project/guide.html", doc.selectFirst("a")!!.attr("href"))
            assertTrue(result.transformed!!.transformations.any { it.transformerId == "readability" })
            assertFalse(result.representation.headers.keys.any { it.equals("Content-Length", true) || it.equals("Digest", true) })
            if (name == "documentation") {
                assertTrue(doc.selectFirst("pre")!!.text().contains("baud=38400"))
                assertEquals(2, doc.select("li").size)
            }
            if (name == "github") assertEquals("DOS", doc.selectFirst("td")!!.text())
            if (name == "news") assertEquals("http://example.test/project/images/retro.gif", doc.selectFirst("img")!!.attr("src"))
        }
    }

    @Test fun `disabled reader leaves ordinary cleanup intact and has a distinct profile`() {
        val input = resource(fixture("news"))
        val out = defaultResourceTransformationPipeline(1_048_576).transform(context(false), input).resource
        assertTrue(out.representation.body.toString(Charsets.UTF_8).contains("Sign in"))
        assertFalse(out.transformed!!.transformations.any { it.transformerId == "readability" })
        assertNotEquals(context(false).htmlTransformers.profileToken, context().htmlTransformers.profileToken)
    }

    @Test fun `no result parser failure and output overflow retain prior representation`() {
        val input = resource(fixture("navigation"))
        for (interpreter in listOf(
            ReadabilityPageInterpreter(), PageInterpreter { _, _ -> null },
            PageInterpreter { _, _ -> error("parser failure") },
        )) {
            val out = ResourceTransformationPipeline(listOf(ReadablePageTransformer(1_048_576, interpreter)))
                .transform(context(), input).resource
            assertSame(input, out)
        }
        val out = ResourceTransformationPipeline(listOf(ReadablePageTransformer(16)))
            .transform(context(), resource(fixture("news"))).resource
        assertNull(out.transformed)
    }

    @Test fun `unsupported response or subresource never calls interpreter`() {
        val transformer = ReadablePageTransformer(1_048_576, PageInterpreter { _, _ -> error("must not run") })
        val input = resource(fixture("news"))
        val contexts = listOf(context().copy(requestMethod = "POST"), context().copy(role = ReferenceRole.SUBRESOURCE))
        contexts.forEach { assertFalse(transformer.supports(it, ResourceTransformationState(input))) }
        for (source in listOf(
            input.source.copy(statusCode = 206), input.source.copy(body = byteArrayOf()),
            input.source.copy(body = ByteArray(ReadabilityPageInterpreter.MAX_INPUT_CHARS + 1)),
            input.source.copy(headers = mapOf("Content-Type" to listOf("text/html"), "Content-Encoding" to listOf("gzip"))),
            input.source.copy(headers = mapOf("Content-Type" to listOf("image/png"))),
        )) assertFalse(transformer.supports(context(), ResourceTransformationState(input.copy(source = source))))
    }

    @Test fun `input work limits reject large wide and deep documents`() {
        val interpreter = ReadabilityPageInterpreter()
        for (html in listOf("x".repeat(ReadabilityPageInterpreter.MAX_INPUT_CHARS + 1),
            "<p>x</p>".repeat(ReadabilityPageInterpreter.MAX_ELEMENTS),
            "<div>".repeat(130) + fixture("news") + "</div>".repeat(130))) {
            assertNull(interpreter.interpret(uri, html))
        }
    }

    @Test fun `renderer uses only legacy markup safe attributes and escaped metadata`() {
        val html = LegacyHtmlRenderer().render(InterpretedPage("<script>title</script>", "<b>author</b>",
            """<p onclick="bad()" style="display:none">Text</p><script>bad()</script><iframe src="/secret"></iframe>
                <a href="javascript:bad()">Bad</a><a href="next">Next</a><img src="data:text/html,bad" onerror="bad()">
                <form><input name="password"></form><pre>  code
  next</pre>""", uri))
        val doc = Jsoup.parse(html)
        assertEquals("<script>title</script>", doc.title())
        assertTrue(doc.select("script, iframe, form, input, [onclick], [onerror], [style]").isEmpty())
        assertFalse(doc.select("a")[0].hasAttr("href"))
        assertEquals("https://example.test/project/next", doc.select("a")[1].attr("href"))
        assertFalse(doc.selectFirst("img")!!.hasAttr("src"))
        assertTrue(html.contains("  code\n  next"))
    }

    @Test fun `URL normalization preserves ports queries parents and fragments`() {
        val base = URI("https://example.test:8443/docs/page?old=1")
        val source = fixture("news").replace("guide.html", "../guide.html?q=2#part")
        val page = assertNotNull(ReadabilityPageInterpreter().interpret(base, source))
        val doc = Jsoup.parse(LegacyHtmlRenderer().render(page))
        assertEquals("https://example.test:8443/guide.html?q=2#part", doc.selectFirst("a")!!.attr("href"))
        assertEquals("https://example.test:8443/docs/images/retro.gif", doc.selectFirst("img")!!.attr("src"))
    }

    @Test fun `base URI is honored and unicode survives source charsets via entities`() {
        for (charset in listOf("UTF-8", "ISO-8859-1")) {
            val html = fixture("news").replace("<head>", "<head><base href='https://assets.test/docs/'>")
                .replace("Vintage computing news", "Blåbær &amp; café")
            val out = defaultResourceTransformationPipeline(1_048_576).transform(context(), resource(html, charset)).resource
            val doc = Jsoup.parse(out.representation.body.toString(charset(charset)))
            assertEquals("Blåbær & café", doc.title())
            assertEquals("http://assets.test/docs/guide.html", doc.selectFirst("a")!!.attr("href"))
        }
    }
}
