package no.skasti.yame.ppp.proxy.transform.readability

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.nio.charset.StandardCharsets

/** YAME owns the output vocabulary; library output is never sent straight to the browser. */
internal class LegacyHtmlRenderer {
    fun render(page: InterpretedPage): String {
        val input = Jsoup.parseBodyFragment(page.contentHtml, page.baseUri.toString())
        val body = Cleaner(allowedMarkup()).clean(input).body()
        val output = Document.createShell(page.baseUri.toString())
        output.outputSettings().prettyPrint(false).syntax(Document.OutputSettings.Syntax.html)
            .charset(StandardCharsets.US_ASCII).escapeMode(Entities.EscapeMode.base)
        output.title(page.title)
        if (page.title.isNotBlank()) output.body().appendElement("h1").text(page.title)
        page.byline?.takeIf { it.isNotBlank() }?.let { output.body().appendElement("p").text(it) }
        body.childNodes().toList().forEach { output.body().appendChild(it) }
        // ASCII entities preserve characters with any source HTTP charset retained by the pipeline.
        // Netscape-era HTML consumers need decimal numeric references. jsoup has
        // no decimal output mode; convert only serialized references, after escaping.
        return HEX_REFERENCE.replace(output.outerHtml()) { match ->
            "&#${match.groupValues[1].toInt(16)};"
        }
    }

    private companion object {
        val HEX_REFERENCE = Regex("&#[xX]([0-9a-fA-F]+);")
    }

    private fun allowedMarkup() = Safelist()
        .addTags("a", "p", "div", "span", "h1", "h2", "h3", "h4", "h5", "h6",
            "b", "i", "strong", "em", "u", "s", "sub", "sup", "br", "hr", "blockquote",
            "pre", "code", "ul", "ol", "li", "dl", "dt", "dd", "table", "caption",
            "thead", "tbody", "tfoot", "tr", "th", "td", "img")
        .addAttributes(":all", "id")
        .addAttributes("a", "href", "name", "title")
        .addAttributes("img", "src", "alt", "width", "height")
        .addAttributes("td", "colspan", "rowspan")
        .addAttributes("th", "colspan", "rowspan")
        .addProtocols("a", "href", "http", "https", "mailto")
        .addProtocols("img", "src", "http", "https")
        .preserveRelativeLinks(false)
}
