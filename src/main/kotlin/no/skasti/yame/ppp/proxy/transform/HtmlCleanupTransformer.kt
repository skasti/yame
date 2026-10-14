package no.skasti.yame.ppp.proxy.transform

import java.util.Locale
import no.skasti.yame.config.HtmlTransformer

/**
 * The four independently registered stages deliberately operate on markup, not a reconstructed DOM:
 * the original doctype, text, and legacy elements stay intact.
 */
internal abstract class HtmlCleanupTransformer(
    transformer: HtmlTransformer,
) : ResourceTransformer {
    final override val id = transformer.command
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

/** Name helpers for complete tags found by the bounded markup scanner. */
internal object HtmlCleanupMarkup {
    private val tagName = Regex("""^<\s*+/?\s*+([A-Za-z][\w:-]*)""")
    private val closingTag = Regex("""^<\s*/""")
    fun name(tag: String): String? =
        tagName.find(tag)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    fun closing(tag: String): Boolean = closingTag.containsMatchIn(tag)
}

/** Removes executable script elements and their contents, not script-looking text in attributes. */
internal class ScriptRemover() : HtmlCleanupTransformer(HtmlTransformer.SCRIPT_REMOVER) {
    override val priority = 10
    override fun clean(html: String): String =
        HtmlMarkupScanner.rewrite(html, removeElements = setOf("script"))
}

/** Removes embedded style blocks and stylesheet link elements; does not fetch them. */
internal class StyleRemover() : HtmlCleanupTransformer(HtmlTransformer.STYLE_REMOVER) {
    override val priority = 20
    override fun clean(html: String): String {
        return HtmlMarkupScanner.rewrite(html, removeElements = setOf("style")) { tag ->
            if (HtmlCleanupMarkup.name(tag) != "link") tag
            else {
                val relation = HtmlTagAttributes.scan(tag).firstOrNull { it.name.equals("rel", true) }?.value
                if (relation?.splitToSequence(' ', '\t', '\r', '\n', '\u000c')
                        ?.any { it.equals("stylesheet", true) } == true) "" else tag
            }
        }
    }
}

/** Preserve navigation, form, image and accessibility attributes; remove presentation and JS hooks. */
internal class AttributeCleaner() : HtmlCleanupTransformer(HtmlTransformer.ATTRIBUTE_CLEANER) {
    // Strip upstream styles before ElementSimplifier adds explicitly configured legacy styles.
    override val priority = 30
    override fun clean(html: String): String = HtmlMarkupScanner.rewrite(html) { tag ->
        val tagName = HtmlCleanupMarkup.name(tag)
        if (tagName == null || HtmlCleanupMarkup.closing(tag) || tag.startsWith("<!")) tag
        else HtmlTagAttributes.remove(tag) { attr ->
            val name = attr.name.lowercase(Locale.ROOT)
            name == "style" || name.startsWith("on") || name.startsWith("data-") ||
                name in setOf("class", "role", "tabindex", "contenteditable", "draggable", "hidden")
        }
    }
}
