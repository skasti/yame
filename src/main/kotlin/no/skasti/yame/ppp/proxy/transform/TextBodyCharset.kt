package no.skasti.yame.ppp.proxy.transform

import org.jsoup.Jsoup
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** HTML metadata is examined before any structural stage can remove the head. */
internal fun textBodyCharset(contentType: String?, body: ByteArray): Charset {
    val declared = charsetParameter(contentType)
    if (!contentType?.substringBefore(';')?.trim().equals("text/html", true)) {
        return declared ?: bomCharset(body) ?: StandardCharsets.ISO_8859_1
    }
    return bomCharset(body) ?: declared?.let(::htmlCharset) ?: metaCharset(body) ?: WINDOWS_1252
}

private fun charsetParameter(contentType: String?): Charset? = contentType
    ?.let { CHARSET_PARAMETER_PATTERN.find(it) }
    ?.let { match -> match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }
    ?.let(::supportedCharset)

private fun supportedCharset(name: String): Charset? =
    runCatching { Charset.forName(name.trim()) }.getOrNull()

// HTML browsers interpret these legacy labels using the Windows punctuation range.
private fun htmlCharset(charset: Charset): Charset = when (charset) {
    StandardCharsets.ISO_8859_1, StandardCharsets.US_ASCII -> WINDOWS_1252
    else -> charset
}

private fun metaCharset(body: ByteArray): Charset? {
    // Only ASCII-compatible declarations in the first 1024 bytes are considered.
    // Parse a bounded prefix so comments, quoted attributes and script text cannot
    // masquerade as meta elements. Jsoup.parse performs no external IO.
    val prefix = String(body, 0, minOf(body.size, 1024), StandardCharsets.ISO_8859_1)
    val complete = prefix.substring(0, prefix.lastIndexOf('>') + 1)
    for (meta in Jsoup.parse(complete).select("meta")) {
        val charset = supportedCharset(meta.attr("charset")) ?: if (
            meta.attr("http-equiv").trim().equals("content-type", true)
        ) charsetParameter(meta.attr("content")) else null
        if (charset != null) {
            // A UTF-16 declaration in an ASCII-compatible head describes UTF-8;
            // actual UTF-16 bytes have already been recognized by their BOM.
            return if (charset.name().startsWith("UTF-16")) StandardCharsets.UTF_8 else htmlCharset(charset)
        }
    }
    return null
}

private val WINDOWS_1252 = Charset.forName("windows-1252")

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

