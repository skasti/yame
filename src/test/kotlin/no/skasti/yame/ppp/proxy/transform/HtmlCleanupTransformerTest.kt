package no.skasti.yame.ppp.proxy.transform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlCleanupTransformerTest {
    @Test fun scriptRemoverDropsScriptsButKeepsMarkup() {
        val html = """<h1>News</h1><script type="module">window.alert("<tag>")</script><p>Story</p>"""
        val out = apply(ScriptRemover(), html)
        assertEquals("<h1>News</h1><p>Story</p>", out)
    }

    @Test fun styleRemoverDropsLinkedAndInlineStyles() {
        val html = """<link rel="stylesheet" href="/style.css"><style>body{display:none}</style><h1>News</h1><link rel="icon" href="/favicon.ico">"""
        val out = apply(StyleRemover(), html)
        assertEquals("""<h1>News</h1><link rel="icon" href="/favicon.ico">""", out)
    }

    @Test fun elementSimplifierKeepsAttributesAndMatchingClosingTags() {
        assertEquals("""<div class="news"><div>Text</div></div>""",
            apply(ElementSimplifier(), """<main class="news"><article>Text</article></main>"""))
    }

    @Test fun attributeCleanerPreservesCriticalAttributes() {
        val input = """<a href="/news" class="x" data-id="2" onclick="go()" title="Read">News</a><img src="/x.gif" width="10" height="20" alt="x">"""
        val out = apply(AttributeCleaner(), input)
        assertEquals("""<a href="/news" title="Read">News</a><img src="/x.gif" width="10" height="20" alt="x">""", out)
    }

    @Test fun doesNotTransformBinaryOrEncodedResponses() {
        val transformer = ScriptRemover()
        val context = context()
        for (headers in listOf(
            mapOf("content-type" to listOf("image/gif")),
            mapOf("content-type" to listOf("text/html"), "content-encoding" to listOf("gzip")),
        )) {
            val resource = Resource(java.net.URI("http://example.com/"), ResourceRepresentation(200, headers, "<script>x</script>".toByteArray()))
            assertFalse(transformer.supports(context, ResourceTransformationState(resource)))
        }
    }

    @Test fun stagesComposeAndOriginalRemainsUntouched() {
        val html = """<main class="root"><script>bad()</script><style>p{display:none}</style><p style="color:red" onclick="go()">News</p></main>"""
        val source = ResourceRepresentation(200, mapOf("content-type" to listOf("text/html; charset=UTF-8"), "content-length" to listOf("999")), html.toByteArray())
        val resource = Resource(java.net.URI("http://example.com/"), source)
        val result = ResourceTransformationPipeline(listOf(ScriptRemover(), StyleRemover(), ElementSimplifier(), AttributeCleaner())).transform(context(), resource)
        assertEquals("""<div><p>News</p></div>""", result.resource.representation.body.toString(Charsets.UTF_8))
        assertEquals(html, source.body.toString(Charsets.UTF_8))
        assertFalse(result.resource.representation.headers.containsKey("content-length"))
        assertEquals(4, result.resource.transformed?.transformations?.size)
    }

    private fun apply(transformer: ResourceTransformer, html: String): String {
        val rep = ResourceRepresentation(200, mapOf("content-type" to listOf("text/html; charset=UTF-8")), html.toByteArray())
        val resource = Resource(java.net.URI("http://example.com/"), rep)
        return ResourceTransformationPipeline(listOf(transformer)).transform(context(), resource)
            .resource.representation.body.toString(Charsets.UTF_8)
    }

    private fun context() = ResourceTransformationContext(
        emptySet(), java.net.URI("http://example.com/"), java.net.URI("http://example.com/"),
        no.skasti.yame.ppp.proxy.ResourceKind.DOCUMENT, null,
        no.skasti.yame.ppp.proxy.ReferenceRole.NAVIGATION, emptyMap(), "GET", "default", { it },
    )
}
