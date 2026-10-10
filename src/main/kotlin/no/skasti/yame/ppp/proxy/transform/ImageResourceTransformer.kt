package no.skasti.yame.ppp.proxy.transform

import java.util.Locale

internal class ImageResourceTransformer(
    private val policy: ImageOptimizationPolicy = ImageOptimizationPolicy(),
) : ResourceTransformer {
    private val codec = LegacyImageCodec(policy)
    private val pngEncoder = PngImageEncoder(policy, codec)

    override val id: String = "legacy-image-optimization"
    override val phase: ResourceTransformPhase = ResourceTransformPhase.OPTIMIZATION

    override fun supports(
        context: ResourceTransformationContext,
        state: ResourceTransformationState,
    ): Boolean {
        val representation = state.resource.representation
        if (representation.body.isEmpty() || representation.body.size > policy.maxEncodedBytes) return false

        val contentEncoding = firstHeaderValue(representation.headers, "content-encoding")
        if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) return false

        val contentType =
            firstHeaderValue(representation.headers, "content-type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?: return false

        return contentType in JPEG_CONTENT_TYPES || contentType == "image/png"
    }

    override fun transform(
        context: ResourceTransformationContext,
        state: ResourceTransformationState,
    ): ResourceTransformationState {
        val current = state.resource.representation
        val isPng = firstHeaderValue(current.headers, "content-type")
            ?.substringBefore(';')?.trim()?.equals("image/png", ignoreCase = true) == true
        if (isPng && !PngImageEncoder.isStaticPng(current.body)) return state
        val decoded = codec.decode(current.body, if (isPng) "png" else "jpeg") ?: return state
        val candidate = if (isPng) {
            pngEncoder.encode(decoded)
        } else {
            val resized = codec.resize(decoded, policy.targetDimensions(decoded.width, decoded.height))
            codec.encodeJpeg(resized)?.let { LegacyImageOutput(it, "image/jpeg", resized.width, resized.height) }
        } ?: return state

        // Optimization must never increase the slow serial transfer, including PNG.
        if (candidate.bytes.size >= current.body.size) return state

        val transformedHeaders =
            transformedHeadersFrom(current.headers)
                .filterKeys { !it.equals("content-type", ignoreCase = true) } +
                ("Content-Type" to listOf(candidate.contentType))

        val transformed =
            ResourceRepresentation(
                statusCode = current.statusCode,
                headers = transformedHeaders,
                body = candidate.bytes,
            )

        return state.copy(
            resource =
                state.resource.copy(
                    transformed =
                        TransformedRepresentation(
                            profile = context.transformationProfile,
                            representation = transformed,
                            transformations =
                                state.resource.transformed?.transformations.orEmpty() +
                                    ResourceTransformationSummary(
                                        transformerId = id,
                                        sourceBytes = current.body.size,
                                        outputBytes = candidate.bytes.size,
                                        detail = "${decoded.width}x${decoded.height} -> ${candidate.width}x${candidate.height} (${candidate.contentType})",
                                    ),
                        ),
                ),
        )
    }

    private fun firstHeaderValue(
        headers: Map<String, List<String>>,
        name: String,
    ): String? =
        headers.entries
            .firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

    private companion object {
        val JPEG_CONTENT_TYPES = setOf("image/jpeg", "image/jpg", "image/pjpeg")
    }
}
