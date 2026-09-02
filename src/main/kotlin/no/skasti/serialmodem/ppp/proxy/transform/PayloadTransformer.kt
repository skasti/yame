package no.skasti.serialmodem.ppp.proxy.transform

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal data class PayloadTransformationContext(
    val headers: Map<String, List<String>>,
    val rewriteText: (String) -> String,
)

internal interface PayloadTransformer {
    fun supports(context: PayloadTransformationContext): Boolean
    fun transform(context: PayloadTransformationContext, payload: ByteArray): ByteArray
}

internal class PayloadTransformationPipeline(
    private val transformers: List<PayloadTransformer>,
) {
    fun supports(context: PayloadTransformationContext): Boolean =
        transformers.any { it.supports(context) }

    fun transform(context: PayloadTransformationContext, payload: ByteArray): ByteArray =
        transformers.fold(payload) { current, transformer ->
            if (transformer.supports(context)) transformer.transform(context, current) else current
        }
}

internal class LegacyTextPayloadTransformer : PayloadTransformer {
    override fun supports(context: PayloadTransformationContext): Boolean {
        val contentEncoding = firstHeader(context.headers, "content-encoding")
        if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) return false
        val contentType = firstHeader(context.headers, "content-type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?: return false
        return contentType in REWRITABLE_CONTENT_TYPES
    }

    override fun transform(context: PayloadTransformationContext, payload: ByteArray): ByteArray =
        rewriteEncodedTextBody(context.headers, payload, context.rewriteText)
}

private fun firstHeader(headers: Map<String, List<String>>, name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

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
