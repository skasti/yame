package no.skasti.yame.ppp.proxy.transform

import net.dankito.readability4j.Readability4J
import net.dankito.readability4j.model.ReadabilityOptions
import org.jsoup.Jsoup
import java.net.URI

internal class ReadabilityPageInterpreter : PageInterpreter {
    override fun interpret(uri: URI, html: String): InterpretedPage? {
        if (html.length > MAX_INPUT_CHARS || html.count { it == '<' } > MAX_ELEMENTS) return null
        val document = Jsoup.parse(html, uri.toString())
        // Bound library traversal and recursive algorithms before handing over the DOM.
        val pending = ArrayDeque<Pair<org.jsoup.nodes.Node, Int>>()
        pending.add(document to 0)
        var nodes = 0
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            if (++nodes > MAX_NODES || depth > MAX_DEPTH) return null
            node.childNodes().forEach { pending.add(it to depth + 1) }
        }
        val base = document.selectFirst("base[href]")?.absUrl("href")
            ?.let { runCatching { URI(it) }.getOrNull() }
            ?.takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) } ?: uri
        // Normalize first: Readability4J's old URL resolver drops explicit ports and
        // mishandles some query/parent-relative references. jsoup owns this once.
        document.setBaseUri(base.toString())
        document.select("a[href], img[src]").forEach { element ->
            val attribute = if (element.normalName() == "a") "href" else "src"
            element.absUrl(attribute).takeIf { it.isNotBlank() }?.let { element.attr(attribute, it) }
        }
        // Its mutable DOM is private to this call.
        val article = Readability4J(base.toString(), document, ReadabilityOptions(maxElemsToParse = MAX_ELEMENTS)).parse()
        val content = article.content?.takeIf { it.isNotBlank() } ?: return null
        // Readability can return tiny navigation fragments on non-article pages.
        if (article.textContent.orEmpty().trim().length < MIN_TEXT_CHARS) return null
        return InterpretedPage(article.title.orEmpty(), article.byline, content, base)
    }

    companion object {
        const val MAX_INPUT_CHARS = 1_048_576
        const val MAX_ELEMENTS = 10_000
        const val MAX_NODES = 30_000
        const val MAX_DEPTH = 128
        const val MIN_TEXT_CHARS = 200
    }
}
