package no.skasti.serialmodem.ppp.proxy.transform

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
        val sourceWidth = dimensionValue(tag, WIDTH_ATTRIBUTE_PATTERN) ?: return tag
        val sourceHeight = dimensionValue(tag, HEIGHT_ATTRIBUTE_PATTERN) ?: return tag
        val dimensions = policy.targetDimensions(sourceWidth, sourceHeight)
        if (dimensions.width == sourceWidth && dimensions.height == sourceHeight) return tag
        var resized = replaceAttribute(tag, WIDTH_ATTRIBUTE_PATTERN, "width", dimensions.width)
        resized = replaceAttribute(resized, HEIGHT_ATTRIBUTE_PATTERN, "height", dimensions.height)
        return resized
    }

    private fun dimensionValue(
        tag: String,
        pattern: Regex,
    ): Int? {
        val match = pattern.find(tag) ?: return null
        return match.groupValues.drop(1)
            .firstOrNull { it.isNotEmpty() }
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }

    private fun replaceAttribute(
        tag: String,
        pattern: Regex,
        name: String,
        value: Int,
    ): String {
        val match = pattern.find(tag) ?: return tag
        return tag.replaceRange(match.range, "$name=\"$value\"")
    }

    private fun firstHeader(headers: Map<String, List<String>>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    private companion object {
        val IMG_TAG_PATTERN = Regex("""(?is)<\s*img\b[^>]*>""")
        val WIDTH_ATTRIBUTE_PATTERN = Regex("""(?is)(?<=\s)width\s*=\s*(?:"([0-9]+)"|'([0-9]+)'|([0-9]+))""")
        val HEIGHT_ATTRIBUTE_PATTERN = Regex("""(?is)(?<=\s)height\s*=\s*(?:"([0-9]+)"|'([0-9]+)'|([0-9]+))""")
    }
}
