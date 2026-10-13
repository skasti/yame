package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.config.ElementSimplifierConfiguration
import no.skasti.yame.config.ElementSimplifierRule
import no.skasti.yame.config.HtmlTransformerSettings
import org.junit.jupiter.api.Timeout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlCleanupTransformerTest {
    @Test @Timeout(5)
    fun unmatchedRawElementsAreScannedOnceAndRetained() {
        for ((tag, transformer) in listOf("script" to ScriptRemover(), "style" to StyleRemover())) {
            val html = "<$tag>".repeat(16_000)
            assertEquals(html, apply(transformer, html))
        }
        assertEquals("<p>Before</p><script>unclosed<main class='x'>",
            apply(ScriptRemover(), "<p>Before</p><script>unclosed<main class='x'>"))
    }

    @Test @Timeout(5)
    fun longUnterminatedTagsPassThroughWithoutRecursiveMatching() {
        for (html in listOf("<aaaa".repeat(32_000), "<main title='" + "<aaaa".repeat(32_000))) {
            for (transformer in listOf(ScriptRemover(), StyleRemover(), AttributeCleaner(), ElementSimplifier())) {
                assertEquals(html, apply(transformer, html))
            }
        }
    }

    @Test @Timeout(5)
    fun longWhitespaceRunsDoNotRescanTagOrAttributeTails() {
        val whitespace = " ".repeat(160_000)
        assertEquals("<$whitespace>", apply(AttributeCleaner(), "<$whitespace>"))
        assertEquals("<main$whitespace>", apply(AttributeCleaner(), "<main$whitespace>"))
        assertEquals("<p style=\"font-weight:bold;\"$whitespace>Title</p>",
            apply(ElementSimplifier(), "<summary$whitespace>Title</summary>"))
    }

    @Test fun retainedScriptBodiesAreOpaqueToOtherEnabledStages() {
        val script = """<script>document.write('<style>p{color:red}</style><link rel="stylesheet" href="x.css"><summary class="x" onclick="go()">Title</summary>');</script>"""
        val input = "<main class='x'><style>p{color:blue}</style>$script<summary>Title</summary></main>"
        assertEquals("<div>$script<p style=\"font-weight:bold;\">Title</p></div>",
            applyDefault(input, HtmlTransformerSettings(scriptRemover = false)))
        val unmatched = "<script>document.write('<style>x</style><main class=\"x\">');"
        assertEquals(unmatched, applyDefault(unmatched, HtmlTransformerSettings(scriptRemover = false)))
    }

    @Test fun retainedStyleAndOtherTextBodiesAreOpaque() {
        val style = """<style>p::after{content:'<script>go()</script><main class="x">'}</style>"""
        assertEquals("$style<div>News</div>",
            applyDefault("$style<main class='x'>News</main>", HtmlTransformerSettings(styleRemover = false)))
        for (tag in listOf("textarea", "title", "xmp", "iframe", "noembed", "noframes")) {
            val raw = "<$tag><script>go()</script><style>x</style><main class='x'>Text</main></$tag>"
            assertEquals(raw, applyDefault(raw))
        }
    }

    @Test fun commentsAndQuotedTagDelimitersArePreserved() {
        val input = """<!-- <script>x</script><main class="x"> --><main title="A > B <style>x</style>" class="x">Text</main>"""
        assertEquals("""<!-- <script>x</script><main class="x"> --><div title="A > B <style>x</style>">Text</div>""",
            applyDefault(input))
        assertEquals("<p>Before</p><!-- <main class='x'>", applyDefault("<p>Before</p><!-- <main class='x'>"))
        assertEquals("<scripture>Text</scripture><p>After</p>",
            apply(ScriptRemover(), "<scripture>Text</scripture><SCRIPT title='>'>x</ScRiPt ><p>After</p>"))
    }

    @Test fun attributeCleanerPreservesFragmentTargetsAndFormAssociations() {
        val input = """<a href="#details">Details</a><h2 id="details" class="x">Title</h2><label for="name">Name</label><input id="name" name="name" data-x="1">"""
        assertEquals("""<a href="#details">Details</a><h2 id="details">Title</h2><label for="name">Name</label><input id="name" name="name">""",
            apply(AttributeCleaner(), input))
    }

    @Test fun urlRewritingPreservesAllCleanupSummaries() {
        val input = """<main class="x"><script>go()</script><style>x</style><a href="https://example.com/news">News</a></main>"""
        val source = ResourceRepresentation(200, mapOf("content-type" to listOf("text/html")), input.toByteArray())
        val result = defaultResourceTransformationPipeline(1024).transform(
            context().copy(rewriteText = { it.replace("https://", "http://") }),
            Resource(java.net.URI("http://example.com/"), source),
        )
        assertEquals("""<div><a href="http://example.com/news">News</a></div>""",
            result.resource.representation.body.toString(Charsets.UTF_8))
        val summaries = requireNotNull(result.resource.transformed).transformations
        assertEquals(listOf("script-remover", "style-remover", "attribute-cleaner", "element-simplifier"),
            summaries.map { it.transformerId })
        assertTrue(summaries.sumOf { it.savedBytes } > 0)
        assertEquals(input, result.resource.source.body.toString(Charsets.UTF_8))
    }

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

    @Test fun elementSimplifierUsesBlockAndInlineMappingsAndKeepsLegacyElements() {
        val input = "<figure><figcaption>Caption</figcaption></figure><time>Today</time><mark>News</mark>" +
            "<h2>Heading</h2><p>Paragraph</p><custom-card>Unknown</custom-card>"
        assertEquals("<div><p>Caption</p></div><span>Today</span><span>News</span>" +
            "<h2>Heading</h2><p>Paragraph</p><custom-card>Unknown</custom-card>", apply(ElementSimplifier(), input))
    }

    @Test fun configuredStyleSurvivesAttributeCleanupAndDetailsBecomeVisible() {
        val input = """<details><SUMMARY class="x" style="display:none" onclick="go()">Title</SUMMARY><p>Text</p></details>"""
        val source = ResourceRepresentation(200, mapOf("content-type" to listOf("text/html")), input.toByteArray())
        val result = defaultResourceTransformationPipeline(1024).transform(context(), Resource(java.net.URI("http://example.com/"), source))
        assertEquals("""<div><p style="font-weight:bold;">Title</p><p>Text</p></div>""",
            result.resource.representation.body.toString(Charsets.UTF_8))
        assertEquals(input, result.resource.source.body.toString(Charsets.UTF_8))
    }

    @Test fun customMappingsReplaceExistingStyleEscapeQuotesAndRenameBothTags() {
        val configuration = ElementSimplifierConfiguration(mapOf(
            "news-title" to ElementSimplifierRule("h3", "font-family:\"Foo & Bar\";"),
        ))
        val input = """<news-title title="A > B" style='display:none'>News</news-title><summary>Unmapped</summary>"""
        assertEquals("""<h3 title="A > B" style="font-family:&quot;Foo &amp; Bar&quot;;">News</h3><summary>Unmapped</summary>""",
            apply(ElementSimplifier(configuration), input))
    }

    @Test fun configuredStylesRespectSelfClosingTagEndingAndEmptyRulesPassThrough() {
        val configuration = ElementSimplifierConfiguration(mapOf("time" to ElementSimplifierRule("span", "color:red;")))
        assertEquals("""<span style="color:red;"/>""", apply(ElementSimplifier(configuration), "<time/>"))
        assertEquals("<summary>Title</summary>",
            apply(ElementSimplifier(ElementSimplifierConfiguration(emptyMap())), "<summary>Title</summary>"))
    }

    @Test fun excessiveConfiguredStyleExpansionFallsBackWithoutPartialMapping() {
        val input = "<summary>x</summary>"
        for (style in listOf("x".repeat(100), "\u20ac".repeat(20))) {
            val configuration = ElementSimplifierConfiguration(mapOf("summary" to ElementSimplifierRule("p", style)))
            assertEquals(input, apply(ElementSimplifier(configuration, maxOutputBytes = 50), input))
        }
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

    private fun applyDefault(html: String, settings: HtmlTransformerSettings = HtmlTransformerSettings()): String {
        val source = ResourceRepresentation(200, mapOf("content-type" to listOf("text/html; charset=UTF-8")), html.toByteArray())
        return defaultResourceTransformationPipeline(1024 * 1024).transform(
            context().copy(htmlTransformers = settings), Resource(java.net.URI("http://example.com/"), source),
        ).resource.representation.body.toString(Charsets.UTF_8)
    }

    private fun context() = ResourceTransformationContext(
        emptySet(), java.net.URI("http://example.com/"), java.net.URI("http://example.com/"),
        no.skasti.yame.ppp.proxy.ResourceKind.DOCUMENT, null,
        no.skasti.yame.ppp.proxy.ReferenceRole.NAVIGATION, emptyMap(), "GET", "default", { it },
    )
}
