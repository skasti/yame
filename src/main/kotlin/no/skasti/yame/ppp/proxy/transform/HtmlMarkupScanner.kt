package no.skasti.yame.ppp.proxy.transform

/** Iterative markup walk. Retained raw text and comments are opaque to tag transformations. */
internal object HtmlMarkupScanner {
    private val rawElements = setOf("script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes", "plaintext")

    fun rewrite(
        html: String,
        removeElements: Set<String> = emptySet(),
        maxOutputLength: Int = Int.MAX_VALUE,
        transformTag: (String) -> String = { it },
    ): String {
        val result = StringBuilder(minOf(html.length, maxOutputLength))
        var position = 0
        fun append(start: Int, end: Int): Boolean {
            if (end - start > maxOutputLength - result.length) return false
            result.append(html, start, end)
            return true
        }
        fun appendTag(start: Int, end: Int): Boolean {
            val replacement = transformTag(html.substring(start, end))
            if (replacement.length > maxOutputLength - result.length) return false
            result.append(replacement)
            return true
        }
        while (position < html.length) {
            val start = html.indexOf('<', position)
            if (start < 0) break
            if (!append(position, start)) return html
            if (html.startsWith("<!--", start)) {
                val end = html.indexOf("-->", start + 4).let { if (it < 0) html.length else it + 3 }
                if (!append(start, end)) return html
                position = end
                continue
            }
            val end = tagEnd(html, start + 1)
            if (end < 0) {
                // Scan an unterminated tag only once, retaining the remainder unchanged.
                position = start
                break
            }
            val tag = html.substring(start, end)
            val name = HtmlCleanupMarkup.name(tag)
            if (!HtmlCleanupMarkup.closing(tag) && name in rawElements) {
                val closingTag = if (name == "plaintext") null else rawClosingTag(html, end, requireNotNull(name))
                if (closingTag == null) {
                    // Unmatched raw elements are retained as one opaque tail, even for a remover.
                    position = start
                    break
                }
                if (name !in removeElements &&
                    (!appendTag(start, end) || !append(end, closingTag.start) ||
                        !appendTag(closingTag.start, closingTag.end))) return html
                position = closingTag.end
            } else {
                if (!appendTag(start, end)) return html
                position = end
            }
        }
        if (!append(position, html.length)) return html
        return result.toString()
    }

    /** Quoted delimiters never end a tag. Each character is examined at most once. */
    private fun tagEnd(html: String, start: Int): Int {
        var quote: Char? = null
        for (index in start until html.length) {
            val char = html[index]
            if (quote != null) {
                if (char == quote) quote = null
            } else when (char) {
                '\'', '"' -> quote = char
                '>' -> return index + 1
            }
        }
        return -1
    }

    private fun rawClosingTag(html: String, start: Int, name: String): RawClosingTag? {
        var position = start
        while (position < html.length) {
            val candidate = html.indexOf("</", position)
            if (candidate < 0) return null
            val nameEnd = candidate + 2 + name.length
            if (html.regionMatches(candidate + 2, name, 0, name.length, ignoreCase = true)) {
                val delimiter = html.getOrNull(nameEnd)
                if (delimiter == '>' || delimiter == '/' || delimiter?.isWhitespace() == true) {
                    // Browsers ignore attributes and a trailing slash on raw-text end tags.
                    // Use the quote-aware bounded tag scan so '>' inside a value is not the end.
                    val end = tagEnd(html, nameEnd)
                    return if (end < 0) null else RawClosingTag(candidate, end)
                }
            }
            position = candidate + 2
        }
        return null
    }

    private data class RawClosingTag(val start: Int, val end: Int)
}
