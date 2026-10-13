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
                val closingStart = if (name == "plaintext") -1 else rawClosingStart(html, end, requireNotNull(name))
                if (closingStart < 0) {
                    // Unmatched raw elements are retained as one opaque tail, even for a remover.
                    position = start
                    break
                }
                val closingEnd = html.indexOf('>', closingStart) + 1
                if (name !in removeElements &&
                    (!appendTag(start, end) || !append(end, closingStart) || !appendTag(closingStart, closingEnd))) return html
                position = closingEnd
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

    private fun rawClosingStart(html: String, start: Int, name: String): Int {
        var position = start
        while (position < html.length) {
            val candidate = html.indexOf("</", position)
            if (candidate < 0) return -1
            var end = candidate + 2 + name.length
            if (html.regionMatches(candidate + 2, name, 0, name.length, ignoreCase = true)) {
                while (end < html.length && html[end].isWhitespace()) end++
                if (end < html.length && html[end] == '>') return candidate
            }
            position = candidate + 2
        }
        return -1
    }
}
