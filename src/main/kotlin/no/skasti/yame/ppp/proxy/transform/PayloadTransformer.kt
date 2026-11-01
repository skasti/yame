package no.skasti.yame.ppp.proxy.transform

import java.util.Locale

internal class LegacyTextResourceTransformer : ResourceTransformer {
    override val id: String = "legacy-text-url-rewrite"
    override val phase: ResourceTransformPhase = ResourceTransformPhase.COMPATIBILITY
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
            ?: return false
        return contentType in REWRITABLE_CONTENT_TYPES
    }

    override fun transform(
        context: ResourceTransformationContext,
        state: ResourceTransformationState,
    ): ResourceTransformationState {
        val current = state.resource.representation
        val rewritten = rewriteEncodedTextBody(
            current.headers,
            current.body,
            context.rewriteText,
        )
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
                    transformations = state.resource.transformed?.transformations.orEmpty(),
                ),
            ),
        )
    }
}

private fun firstHeader(headers: Map<String, List<String>>, name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

internal fun transformedHeadersFrom(
    sourceHeaders: Map<String, List<String>>,
): Map<String, List<String>> =
    sourceHeaders.filterKeys { name ->
        name.lowercase(Locale.ROOT) !in SOURCE_ONLY_REPRESENTATION_HEADERS
    }

// ETag and Last-Modified are intentionally preserved. Within one YAME process the
// transformation pipeline is effectively static and deterministic: unchanged
// upstream source state therefore implies unchanged client-visible output.
// Validators can safely describe that stable source state even when the payload
// bytes themselves have been rewritten.

internal fun rewriteEncodedTextBody(
    headers: Map<String, List<String>>,
    body: ByteArray,
    transform: (String) -> String,
): ByteArray {
    val contentType = headers.entries
        .firstOrNull { (name, _) -> name.equals("content-type", ignoreCase = true) }
        ?.value
        ?.firstOrNull()
    val charset = textBodyCharset(contentType, body)
    val source = body.toString(charset)
    val rewritten = transform(source)
    return if (rewritten == source) body else rewritten.toByteArray(charset)
}

private val REWRITABLE_CONTENT_TYPES = setOf(
    "text/html",
    "application/xhtml+xml",
    "text/css",
    "text/javascript",
    "application/javascript",
    "application/x-javascript",
)

private val SOURCE_ONLY_REPRESENTATION_HEADERS = setOf(
    "content-md5",
    "digest",
    "content-digest",
    "repr-digest",
    "content-range",
    "accept-ranges",
    "content-length",
)
