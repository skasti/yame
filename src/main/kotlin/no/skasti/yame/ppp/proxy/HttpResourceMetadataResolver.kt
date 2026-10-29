package no.skasti.yame.ppp.proxy

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Bounded HTTP parsing; returned names are display labels, never filesystem paths. */
internal object HttpResourceMetadataResolver {
    private const val MAX_INPUT = 8192
    private const val MAX_NAME_CODE_POINTS = 256

    fun filename(uri: URI, headers: List<Pair<String, String>> = emptyList()): String? {
        val dispositions = headers.filter { it.first.equals("Content-Disposition", true) }
        val parameters = dispositions.singleOrNull()?.second?.let(::parameters)
        parameters?.get("filename*")?.let(::extendedValue)?.let(::safeName)?.let { return it }
        parameters?.get("filename")?.let(::safeName)?.let { return it }
        val segment = uri.rawPath?.takeIf { it.length <= MAX_INPUT }
            ?.split('/')?.lastOrNull { it.isNotEmpty() } ?: return null
        return percentDecode(segment, Charsets.UTF_8)?.let(::safeName)
    }

    private fun extendedValue(value: String): String? {
        val first = value.indexOf('\'')
        val second = value.indexOf('\'', first + 1)
        if (first <= 0 || second < 0) return null
        val charset = when (value.substring(0, first).lowercase(Locale.ROOT)) {
            "utf-8" -> Charsets.UTF_8
            "iso-8859-1" -> Charsets.ISO_8859_1
            else -> return null
        }
        return percentDecode(value.substring(second + 1), charset)
    }

    private fun percentDecode(value: String, charset: Charset): String? = runCatching {
        val source = value.toByteArray(Charsets.UTF_8)
        val bytes = ByteArrayOutputStream(source.size)
        var index = 0
        while (index < source.size) {
            if (source[index] == '%'.code.toByte()) {
                require(index + 2 < source.size)
                val high = source[index + 1].toInt().toChar().digitToIntOrNull(16)
                val low = source[index + 2].toInt().toChar().digitToIntOrNull(16)
                require(high != null && low != null)
                bytes.write(high * 16 + low)
                index += 3
            } else {
                bytes.write(source[index].toInt()) // '+' is a literal path/attribute character.
                index++
            }
        }
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }.getOrNull()

    private fun safeName(value: String): String? {
        if (value.length > MAX_INPUT) return null
        val leaf = value.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            leaf.codePoints().forEach { point ->
                if (!Character.isISOControl(point) &&
                    Character.getType(point) != Character.FORMAT.toInt() &&
                    Character.getType(point) != Character.LINE_SEPARATOR.toInt() &&
                    Character.getType(point) != Character.PARAGRAPH_SEPARATOR.toInt()) appendCodePoint(point)
            }
        }.trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null
        return cleaned.substring(0, cleaned.offsetByCodePoints(0,
            minOf(MAX_NAME_CODE_POINTS, cleaned.codePointCount(0, cleaned.length))))
    }

    /** Parse quoted parameters with escapes; duplicate names and malformed syntax are invalid. */
    private fun parameters(value: String): Map<String, String>? {
        if (value.length > MAX_INPUT || value.any { it == '\r' || it == '\n' }) return null
        var index = 0
        fun whitespace() { while (index < value.length && value[index] in " \t") index++ }
        fun token(): String {
            val start = index
            while (index < value.length && (value[index].isLetterOrDigit() && value[index].code < 128 ||
                    value[index] in "!#$%&'*+-.^_`|~")) index++
            return value.substring(start, index)
        }
        whitespace()
        if (token().isEmpty()) return null
        whitespace()
        val result = mutableMapOf<String, String>()
        while (index < value.length) {
            if (value[index++] != ';') return null
            whitespace()
            val name = token().lowercase(Locale.ROOT)
            whitespace()
            if (name.isEmpty() || index >= value.length || value[index++] != '=') return null
            whitespace()
            val parameter = if (index < value.length && value[index] == '"') {
                index++
                val decoded = StringBuilder()
                var ended = false
                while (index < value.length) {
                    val c = value[index++]
                    if (c == '"') { ended = true; break }
                    if (c == '\\') {
                        if (index >= value.length) return null
                        decoded.append(value[index++])
                    } else decoded.append(c)
                }
                if (!ended) return null
                decoded.toString()
            } else token().takeIf { it.isNotEmpty() } ?: return null
            if (result.put(name, parameter) != null) return null
            whitespace()
        }
        return result
    }
}
