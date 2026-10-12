package no.skasti.yame.ppp.proxy.transform

import java.util.Locale

/**
 * The four independently registered stages deliberately operate on markup, not a reconstructed DOM:
 * the original doctype, text, and legacy elements stay intact.
 */
internal abstract class HtmlCleanupTransformer : ResourceTransformer {
    override val phase = ResourceTransformPhase.STRUCTURAL
    override val cacheable = true

    protected abstract fun clean(html: String): String

    override fun supports(context: ResourceTransformationContext, state: ResourceTransformationState): Boolean {
        val current = state.resource.representation
        if (current.statusCode != 200 || current.body.isEmpty()) return false
        val encoding = current.headers.entries.firstOrNull { it.key.equals("content-encoding", true) }
            ?.value?.firstOrNull()
        if (encoding != null && !encoding.equals("identity", true)) return false
        val type = current.headers.entries.firstOrNull { it.key.equals("content-type", true) }
            ?.value?.firstOrNull()?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        return type == "text/html"
    }

    override fun transform(context: ResourceTransformationContext, state: ResourceTransformationState): ResourceTransformationState {
        val current = state.resource.representation
        val result = rewriteEncodedTextBody(current.headers, current.body, ::clean)
        if (result === current.body) return state
        val transformed = ResourceRepresentation(current.statusCode, transformedHeadersFrom(current.headers), result)
        return state.copy(resource = state.resource.copy(transformed = TransformedRepresentation(
            context.transformationProfile,
            transformed,
            state.resource.transformed?.transformations.orEmpty() + ResourceTransformationSummary(
                id, current.body.size, result.size, "simplified legacy HTML"
            ),
        )))
    }
}

/** Match whole tags while respecting quoted > characters. Not a full HTML5 parser. */
internal object HtmlCleanupMarkup {
    val tags = Regex("""(?s)<(?:[^>'"]|"[^"]*"|'[^']*')*>""")
    val attribute = Regex("""(?s)\s+([\w:-]+)(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))?""")
    fun name(tag: String): String? =
        Regex("""^<\s*/?\s*([A-Za-z][\w:-]*)""").find(tag)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    fun closing(tag: String): Boolean = tag.startsWith("</")
}

/** Removes executable script elements and their contents, not script-looking text in attributes. */
internal class ScriptRemover : HtmlCleanupTransformer() {
    override val id = "script-remover"
    override val priority = 10
    override fun clean(html: String): String =
        Regex("""(?is)<script\b(?:[^>'"]|"[^"]*"|'[^']*')*>.*?</script\s*>""").replace(html, "")
}

/** Removes embedded style blocks and stylesheet link elements; does not fetch them. */
internal class StyleRemover : HtmlCleanupTransformer() {
    override val id = "style-remover"
    override val priority = 20
    override fun clean(html: String): String {
        val withoutBlocks = Regex("""(?is)<style\b(?:[^>'"]|"[^"]*"|'[^']*')*>.*?</style\s*>""").replace(html, "")
        return HtmlCleanupMarkup.tags.replace(withoutBlocks) { m ->
            val tag = m.value
            if (HtmlCleanupMarkup.name(tag) != "link") tag
            else if (Regex("""(?i)\brel\s*=\s*(?:"[^"]*stylesheet[^"]*"|'[^']*stylesheet[^']*'|stylesheet)""").containsMatchIn(tag)) "" else tag
        }
    }
}

/** Rename only known HTML5 container elements: an arbitrary custom element might have different semantics. */
internal class ElementSimplifier : HtmlCleanupTransformer() {
    override val id = "element-simplifier"
    override val priority = 30
    override fun clean(html: String): String = HtmlCleanupMarkup.tags.replace(html) { m ->
        val tag = m.value
        val name = HtmlCleanupMarkup.name(tag)
        if (name !in modernContainers) tag
        else tag.replaceFirst(Regex("""(?i)(<\s*/?\s*)[\w:-]+"""), "${'$'}1div")
    }
    private companion object {
        val modernContainers = setOf("main", "article", "section", "nav", "header", "footer", "aside", "figure", "figcaption")
    }
}

/** Preserve navigation, form, image and accessibility attributes; remove presentation and JS hooks. */
internal class AttributeCleaner : HtmlCleanupTransformer() {
    override val id = "attribute-cleaner"
    override val priority = 40
    override fun clean(html: String): String = HtmlCleanupMarkup.tags.replace(html) { m ->
        val tag = m.value
        val tagName = HtmlCleanupMarkup.name(tag)
        if (tagName == null || HtmlCleanupMarkup.closing(tag) || tag.startsWith("<!")) tag
        else HtmlCleanupMarkup.attribute.replace(tag) { attr ->
            val name = attr.groupValues[1].lowercase(Locale.ROOT)
            if (name == "style" || name.startsWith("on") || name.startsWith("data-") ||
                name in setOf("class", "id", "role", "tabindex", "contenteditable", "draggable", "hidden")) "" else attr.value
        }
    }
}
