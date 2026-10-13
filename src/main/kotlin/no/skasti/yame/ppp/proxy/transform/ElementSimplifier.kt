package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.config.ElementSimplifierConfiguration
import no.skasti.yame.config.ElementSimplifierRule
import no.skasti.yame.config.HtmlTransformer
import no.skasti.yame.config.HtmlTransformerControl
import java.util.Locale

/** Apply explicit tag mappings; unknown elements and existing headings/paragraphs stay intact. */
internal class ElementSimplifier(
    private val configuration: ElementSimplifierConfiguration = ElementSimplifierConfiguration.defaults(),
    private val maxOutputBytes: Int = 16 * 1024 * 1024,
    control: HtmlTransformerControl? = null,
) : HtmlCleanupTransformer(HtmlTransformer.ELEMENT_SIMPLIFIER, control) {
    init {
        require(maxOutputBytes > 0) { "Element-simplifier output limit must be positive" }
    }

    override val priority = 40

    override fun transform(context: ResourceTransformationContext, state: ResourceTransformationState): ResourceTransformationState {
        val result = super.transform(context, state)
        return if (result.resource.representation.body.size <= maxOutputBytes) result else state
    }

    override fun clean(html: String): String =
        HtmlMarkupScanner.rewrite(html, maxOutputLength = maxOutputBytes) { tag ->
            val name = HtmlCleanupMarkup.name(tag)
            name?.let(configuration.rules::get)?.let { rewriteTag(tag, it) } ?: tag
        }

    private fun rewriteTag(tag: String, rule: ElementSimplifierRule): String {
        val name = requireNotNull(TAG_START.find(tag))
        val nameRange = requireNotNull(name.groups[2]).range
        var renamed = tag.replaceRange(nameRange, rule.target)
        if (name.groupValues[1] == "/" || rule.style == null) return renamed
        renamed = HtmlCleanupMarkup.attribute.replace(renamed) { attribute ->
            if (attribute.groupValues[1].lowercase(Locale.ROOT) == "style") "" else attribute.value
        }
        var endingStart = renamed.lastIndex
        if (endingStart > 0 && renamed[endingStart - 1] == '/') endingStart--
        while (endingStart > 0 && renamed[endingStart - 1].isWhitespace()) endingStart--
        val style = rule.style.replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;")
        return renamed.substring(0, endingStart) + " style=\"$style\"" + renamed.substring(endingStart)
    }

    private companion object {
        val TAG_START = Regex("""^<\s*+(/?)\s*+([A-Za-z][\w:-]*)""")
    }
}
