package no.skasti.serialmodem.ppp.proxy.transform

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal class LegacyTextResourceTransformer : ResourceTransformer {
    override val id: String = "legacy-text-url-rewrite"
    override val phase: ResourceTransformPhase = ResourceTransformPhase.COMPATIBILITY

    override fun supports(
        context: ResourceTransformationContext,
        representation: ResourceRepresentation,
    ): Boolean {
        val contentEncoding = firstHeader(representation.headers, "content-encoding")
        if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) return false
        val contentType = firstHeader(representation.headers, "content-type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?: return false
        return contentType in REWRITABLE_CONTENT_TYPES
    }

    override fun transform(
        context: ResourceTransformationContext,
        representation: ResourceRepresentation,
    ): ResourceRepresentation {
        val rewritten = rewriteEncodedTextBody(
            representation.headers,
            representation.body,
            context.rewriteText,
        )
        return if (rewritten === representation.body) {
            representation
        } else {
            representation.copy(
                headers = stripStaleRepresentationMetadata(representation.headers),
                body = rewritten,
            )
        }
    }
}

private fun firstHeader(headers: Map<String, List<String>>, name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

internal fun stripStaleRepresentationMetadata(
    headers: Map<String, List<String>>,
): Map<String, List<String>> =
    headers.filterKeys { name ->
        name.lowercase() !in STALE_REPRESENTATION_HEADERS
    }

internal fun rewriteEncodedTextBody(
    headers: Map<String, List<String>>,
    body: ByteArray,
    transform: (String) -> String,
): ByteArray {
    val contentType = headers.entries
        .firstOrNull { (name, _) -> name.equals("content-type", ignoreCase = true) }
        ?.value
        ?.firstOrNull()
    val declaredCharset = contentType
        ?.let { CHARSET_PARAMETER_PATTERN.find(it) }
        ?.let { match -> match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }
        ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
    val charset = declaredCharset ?: bomCharset(body) ?: StandardCharsets.ISO_8859_1
    val source = body.toString(charset)
    val rewritten = transform(source)
    return if (rewritten == source) body else rewritten.toByteArray(charset)
}

private fun bomCharset(body: ByteArray): Charset? =
    when {
        body.size >= 4 && body[0] == 0x00.toByte() && body[1] == 0x00.toByte() &&
            body[2] == 0xFE.toByte() && body[3] == 0xFF.toByte() -> Charset.forName("UTF-32BE")
        body.size >= 4 && body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte() &&
            body[2] == 0x00.toByte() && body[3] == 0x00.toByte() -> Charset.forName("UTF-32LE")
        body.size >= 3 && body[0] == 0xEF.toByte() && body[1] == 0xBB.toByte() && body[2] == 0xBF.toByte() -> StandardCharsets.UTF_8
        body.size >= 2 && body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte() -> StandardCharsets.UTF_16
        body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte() -> StandardCharsets.UTF_16
        else -> null
    }

private val CHARSET_PARAMETER_PATTERN =
    Regex("""(?i)(?:^|;)\s*charset\s*=\s*(?:"([^"]+)"|'([^']+)'|([^;\s]+))""")

private val REWRITABLE_CONTENT_TYPES = setOf(
    "text/html",
    "application/xhtml+xml",
    "text/css",
    "text/javascript",
    "application/javascript",
    "application/x-javascript",
)

private val STALE_REPRESENTATION_HEADERS = setOf(
    "etag",
    "content-md5",
    "digest",
    "content-digest",
    "repr-digest",
    "content-range",
    "accept-ranges",
    "content-length",
)
