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

    override fun clean(html: String): String {
        val result = StringBuilder(minOf(html.length, maxOutputBytes))
        var position = 0
        for (match in HtmlCleanupMarkup.tags.findAll(html)) {
            val name = HtmlCleanupMarkup.name(match.value)
            val replacement = name?.let(configuration.rules::get)?.let { rewriteTag(match.value, it) } ?: match.value
            val textLength = match.range.first - position
            // Bound expansion from a short tag plus a long configured style. Fall back as a whole.
            if (textLength > maxOutputBytes - result.length ||
                replacement.length > maxOutputBytes - result.length - textLength) return html
            result.append(html, position, match.range.first).append(replacement)
            position = match.range.last + 1
        }
        if (html.length - position > maxOutputBytes - result.length) return html
        return result.append(html, position, html.length).toString()
    }

    private fun rewriteTag(tag: String, rule: ElementSimplifierRule): String {
        val name = requireNotNull(TAG_START.find(tag))
        val nameRange = requireNotNull(name.groups[2]).range
        var renamed = tag.replaceRange(nameRange, rule.target)
        if (name.groupValues[1] == "/" || rule.style == null) return renamed
        renamed = HtmlCleanupMarkup.attribute.replace(renamed) { attribute ->
            if (attribute.groupValues[1].lowercase(Locale.ROOT) == "style") "" else attribute.value
        }
        val ending = requireNotNull(TAG_END.find(renamed))
        val style = rule.style.replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;")
        return renamed.substring(0, ending.range.first) + " style=\"$style\"" + ending.value
    }

    private companion object {
        val TAG_START = Regex("""^<\s*(/?)\s*([A-Za-z][\w:-]*)""")
        val TAG_END = Regex("""\s*/?>$""")
    }
}
