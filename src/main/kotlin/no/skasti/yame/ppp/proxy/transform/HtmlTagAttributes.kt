package no.skasti.yame.ppp.proxy.transform

internal data class HtmlTagAttribute(val name: String, val value: String?, val start: Int, val end: Int)

/** Walk real attributes, consuming quoted values in full before examining the next name. */
internal object HtmlTagAttributes {
    fun scan(tag: String): Sequence<HtmlTagAttribute> = sequence {
        var position = 1
        while (position < tag.length && tag[position].isWhitespace()) position++
        if (position < tag.length && tag[position] == '/') position++
        while (position < tag.length && tag[position].isWhitespace()) position++
        while (position < tag.length && nameCharacter(tag[position])) position++

        while (position < tag.length) {
            val start = position
            while (position < tag.length && tag[position].isWhitespace()) position++
            if (position >= tag.length || tag[position] == '>' || tag[position] == '/') break
            val nameStart = position
            while (position < tag.length && nameCharacter(tag[position])) position++
            if (position == nameStart) {
                // Invalid syntax: leave the rest opaque instead of finding names inside quotes.
                break
            }
            val nameEnd = position
            var equalsPosition = position
            while (equalsPosition < tag.length && tag[equalsPosition].isWhitespace()) equalsPosition++
            var value: String? = null
            if (equalsPosition < tag.length && tag[equalsPosition] == '=') {
                position = equalsPosition + 1
                while (position < tag.length && tag[position].isWhitespace()) position++
                if (position < tag.length && (tag[position] == '\'' || tag[position] == '"')) {
                    val quote = tag[position++]
                    val valueStart = position
                    while (position < tag.length && tag[position] != quote) position++
                    if (position >= tag.length) break
                    value = tag.substring(valueStart, position++)
                } else {
                    val valueStart = position
                    while (position < tag.length && !tag[position].isWhitespace() && tag[position] != '>') position++
                    value = tag.substring(valueStart, position)
                }
            }
            yield(HtmlTagAttribute(tag.substring(nameStart, nameEnd), value, start, position))
        }
    }

    fun remove(tag: String, predicate: (HtmlTagAttribute) -> Boolean): String {
        var position = 0
        val result = StringBuilder(tag.length)
        for (attribute in scan(tag)) {
            if (predicate(attribute)) {
                result.append(tag, position, attribute.start)
                position = attribute.end
            }
        }
        return if (position == 0) tag else result.append(tag, position, tag.length).toString()
    }

    private fun nameCharacter(char: Char): Boolean =
        !char.isWhitespace() && char !in "=/>\"'<"
}
