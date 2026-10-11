package no.skasti.yame.ppp.proxy.transform

import java.util.Locale

internal data class LegacyImageOutput(
    val bytes: ByteArray,
    val contentType: String,
    val width: Int,
    val height: Int,
)

internal fun ResourceRepresentation.supportsImageOptimization(
    policy: ImageOptimizationPolicy,
    contentTypes: Set<String>,
): Boolean {
    if (body.isEmpty() || body.size > policy.maxEncodedBytes) return false
    val encoding = firstImageHeader("content-encoding")
    if (encoding != null && !encoding.equals("identity", ignoreCase = true)) return false
    val contentType = firstImageHeader("content-type")
        ?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    return contentType in contentTypes
}

private fun ResourceRepresentation.firstImageHeader(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

internal fun ResourceTransformationState.withOptimizedImage(
    context: ResourceTransformationContext,
    transformerId: String,
    sourceDimensions: ImageDimensions,
    candidate: LegacyImageOutput,
): ResourceTransformationState {
    val current = resource.representation
    if (candidate.bytes.size >= current.body.size) return this
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

    return copy(
        resource =
            resource.copy(
                transformed =
                    TransformedRepresentation(
                        profile = context.transformationProfile,
                        representation = transformed,
                        transformations =
                            resource.transformed?.transformations.orEmpty() +
                                ResourceTransformationSummary(
                                    transformerId = transformerId,
                                    sourceBytes = current.body.size,
                                    outputBytes = candidate.bytes.size,
                                    detail = "${sourceDimensions.width}x${sourceDimensions.height} -> ${candidate.width}x${candidate.height} (${candidate.contentType})",
                                ),
                    ),
            ),
    )
}
