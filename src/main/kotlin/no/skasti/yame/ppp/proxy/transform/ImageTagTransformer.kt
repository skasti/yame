package no.skasti.yame.ppp.proxy.transform

import java.util.Locale

internal class ImageTagTransformer(
    private val policy: ImageOptimizationPolicy = ImageOptimizationPolicy(),
) : ResourceTransformer {
    override val id: String = "legacy-image-tag-sizing"
    override val phase: ResourceTransformPhase = ResourceTransformPhase.COMPATIBILITY
    override val priority: Int = 10
    override val cacheable: Boolean = false

    override fun supports(
        context: ResourceTransformationContext,
        state: ResourceTransformationState,
    ): Boolean {
        val representation = state.resource.representation
        val contentEncoding = firstHeader(representation.headers, "content-encoding")
        if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) return false
        val contentType = firstHeader(representation.headers, "content-type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        return contentType == "text/html" || contentType == "application/xhtml+xml"
    }

    override fun transform(
        context: ResourceTransformationContext,
        state: ResourceTransformationState,
    ): ResourceTransformationState {
        val current = state.resource.representation
        val rewritten = rewriteEncodedTextBody(current.headers, current.body) { html ->
            IMG_TAG_PATTERN.replace(html) { match ->
                resizeImageTag(match.value)
            }
        }
        if (rewritten === current.body) return state

        val transformed = ResourceRepresentation(
            statusCode = current.statusCode,
            headers = transformedHeadersFrom(current.headers),
            body = rewritten,
        )
        return state.copy(
            resource = state.resource.copy(
                transformed = TransformedRepresentation(
                    profile = context.transformationProfile,
                    representation = transformed,
                    transformations = state.resource.transformed?.transformations.orEmpty() +
                        ResourceTransformationSummary(
                            transformerId = id,
                            sourceBytes = current.body.size,
                            outputBytes = rewritten.size,
                            detail = "image dimensions updated",
                        ),
                ),
            ),
        )
    }

    private fun resizeImageTag(tag: String): String {
        val widthAttribute = findAttribute(tag, "width") ?: return tag
        val heightAttribute = findAttribute(tag, "height") ?: return tag
        val sourceWidth = widthAttribute.value.toIntOrNull()?.takeIf { it > 0 } ?: return tag
        val sourceHeight = heightAttribute.value.toIntOrNull()?.takeIf { it > 0 } ?: return tag
        val dimensions = policy.targetDimensions(sourceWidth, sourceHeight)
        if (dimensions.width == sourceWidth && dimensions.height == sourceHeight) return tag

        var resized = replaceAttributeValue(tag, widthAttribute, dimensions.width)
        val resizedHeightAttribute = findAttribute(resized, "height") ?: return tag
        resized = replaceAttributeValue(resized, resizedHeightAttribute, dimensions.height)
        return resized
    }

    private fun findAttribute(
        tag: String,
        requestedName: String,
    ): HtmlAttribute? {
        var index = tag.indexOf('<').takeIf { it >= 0 }?.plus(1) ?: return null
        while (index < tag.length && tag[index].isWhitespace()) index++
        while (index < tag.length && isAttributeNameCharacter(tag[index])) index++

        while (index < tag.length) {
            while (index < tag.length && tag[index].isWhitespace()) index++
            if (index >= tag.length || tag[index] == '>' || tag[index] == '/') return null

            val nameStart = index
            while (index < tag.length && isAttributeNameCharacter(tag[index])) index++
            if (index == nameStart) {
                index++
                continue
            }
            val name = tag.substring(nameStart, index)

            while (index < tag.length && tag[index].isWhitespace()) index++
            if (index >= tag.length || tag[index] != '=') continue
            index++
            while (index < tag.length && tag[index].isWhitespace()) index++
            if (index >= tag.length) return null

            val quote = tag[index].takeIf { it == '\'' || it == '"' }
            val replacementStart: Int
            val valueStart: Int
            val valueEndExclusive: Int
            val replacementEndExclusive: Int
            if (quote != null) {
                replacementStart = index
                index++
                valueStart = index
                while (index < tag.length && tag[index] != quote) index++
                if (index >= tag.length) return null
                valueEndExclusive = index
                index++
                replacementEndExclusive = index
            } else {
                replacementStart = index
                valueStart = index
                while (
                    index < tag.length &&
                    !tag[index].isWhitespace() &&
                    tag[index] != '>'
                ) {
                    index++
                }
                valueEndExclusive = index
                replacementEndExclusive = index
            }

            if (name.equals(requestedName, ignoreCase = true)) {
                return HtmlAttribute(
                    value = tag.substring(valueStart, valueEndExclusive),
                    quote = quote,
                    replacementStart = replacementStart,
                    replacementEndExclusive = replacementEndExclusive,
                )
            }
        }
        return null
    }

    private fun replaceAttributeValue(
        tag: String,
        attribute: HtmlAttribute,
        value: Int,
    ): String {
        val replacement =
            if (attribute.quote == '\'') {
                "\"$value\""
            } else if (attribute.quote == '"') {
                "\"$value\""
            } else {
                value.toString()
            }
        return tag.replaceRange(
            attribute.replacementStart,
            attribute.replacementEndExclusive,
            replacement,
        )
    }

    private fun isAttributeNameCharacter(character: Char): Boolean =
        character.isLetterOrDigit() || character == '-' || character == '_' || character == ':'

    private fun firstHeader(headers: Map<String, List<String>>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    private data class HtmlAttribute(
        val value: String,
        val quote: Char?,
        val replacementStart: Int,
        val replacementEndExclusive: Int,
    )

    private companion object {
        val IMG_TAG_PATTERN = Regex("""(?is)<\s*img\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*>""")
    }
}
