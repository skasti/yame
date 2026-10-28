package no.skasti.yame.config.ini

import java.io.StringReader
import java.io.StringWriter
import java.util.Locale
import java.util.Properties

/** Syntax-only INI document. Unchanged lines and unclaimed entries survive a patch verbatim. */
class IniDocument internal constructor(
    private val lines: MutableList<Line>,
    private val newline: String,
    private val terminalNewline: Boolean,
) {
    internal sealed class Line {
        data class Section(val name: String, val raw: String) : Line()
        data class Property(val section: String, val name: String, var value: String, var raw: String) : Line()
        data class Other(val raw: String) : Line()
    }

    fun sections(): List<String> = lines.filterIsInstance<Line.Section>().map { it.name }
    fun properties(section: String): Map<String, String> = lines.filterIsInstance<Line.Property>()
        .filter { it.section == section }.associateTo(linkedMapOf()) { it.name to it.value }
    fun get(section: String, property: String): String? = properties(section)[property]

    fun put(section: String, property: String, value: String) {
        require(property.isNotBlank() && !property.contains('\n') && !property.contains('\r'))
        require(!value.contains('\n') && !value.contains('\r'))
        val index = lines.indexOfLast { it is Line.Property && it.section == section && it.name == property }
        if (index >= 0) {
            val old = lines[index] as Line.Property
            if (old.value != value) { old.value = value; old.raw = renderProperty(section, property, value) }
            return
        }
        val entry = Line.Property(section, property, value, renderProperty(section, property, value))
        if (section.isEmpty()) {
            // Keep root entries before any comments belonging to the first section.
            val last = lines.indexOfLast { it is Line.Property && it.section.isEmpty() }
            val first = lines.indexOfFirst { it is Line.Section }
            val insertion = if (last >= 0) last + 1 else if (first < 0) lines.size else sectionBlockStart(first)
            lines.add(insertion, entry)
        } else {
            val start = lines.indexOfFirst { it is Line.Section && it.name == section }
            if (start < 0) {
                if (lines.isNotEmpty()) {
                    // Only new section boundaries get the preferred two empty lines.
                    var blanks = lines.asReversed().takeWhile { it == Line.Other("") }.size
                    while (blanks++ < 2) lines.add(Line.Other(""))
                }
                lines.add(Line.Section(section, "[$section]"))
                lines.add(entry)
            } else {
                val next = (start + 1 until lines.size).firstOrNull { lines[it] is Line.Section } ?: lines.size
                val last = (start + 1 until next).lastOrNull { lines[it] is Line.Property }
                lines.add((last ?: start) + 1, entry)
            }
        }
    }

    /** Replace only an unambiguous marked block directly attached to this section. */
    fun synchronizeSectionDescription(section: String, description: String?) {
        val header = lines.indexOfFirst { it is Line.Section && it.name == section }
        if (header < 0) return
        val start = sectionBlockStart(header)
        val replacement = markedComments(section, description)
        lines.subList(start, header).clear()
        lines.addAll(start, replacement)
    }

    private fun sectionBlockStart(header: Int): Int {
        val section = (lines[header] as Line.Section).name
        var index = header - 1
        while (index >= 0) {
            val raw = (lines[index] as? Line.Other)?.raw ?: break
            if (raw == "##### @yame $section") return index
            // Marker-like content is ambiguous and must never be consumed.
            if (raw.trimStart().startsWith("#####") || !(raw.trimStart().startsWith("#") || raw.trimStart().startsWith(";"))) break
            index--
        }
        return header
    }

    /** The file block is anchored at byte zero and ends at a blank line or EOF. */
    fun synchronizeFileDescription(description: String?) {
        var end = 0
        if (lines.firstOrNull() == Line.Other("##### @yame file")) {
            var candidate = 1
            while (candidate < lines.size) {
                val raw = (lines[candidate] as? Line.Other)?.raw ?: break
                if (!(raw.trimStart().startsWith("#") || raw.trimStart().startsWith(";")) || raw.trimStart().startsWith("#####")) break
                candidate++
            }
            if (candidate == lines.size || lines[candidate] == Line.Other("")) {
                end = candidate
            }
        }
        val replacement = markedComments("file", description)
        if (end > 0) {
            lines.subList(0, end).clear()
            // The separating blank belongs to the file block, not the next section.
            if (replacement.isEmpty() && lines.firstOrNull() == Line.Other("")) lines.removeAt(0)
        }
        if (replacement.isNotEmpty()) {
            lines.addAll(0, replacement + if (end == 0) listOf(Line.Other("")) else emptyList())
        }
    }

    private fun markedComments(id: String, description: String?): List<Line.Other> =
        if (description.isNullOrBlank()) emptyList() else
            listOf(Line.Other("##### @yame $id")) + description.lineSequence().map {
                Line.Other(if (it.isEmpty()) "#" else "# $it")
            }.toList()

    fun remove(section: String, property: String) {
        lines.removeAll { it is Line.Property && it.section == section && it.name == property }
    }
    fun removeSection(section: String) {
        synchronizeSectionDescription(section, null)
        val start = lines.indexOfFirst { it is Line.Section && it.name == section }
        if (start < 0) return
        val end = (start + 1 until lines.size).firstOrNull { lines[it] is Line.Section } ?: lines.size
        // Trailing comments may introduce the following section. Only the
        // removed header/properties and its explicitly owned block are ours.
        for (index in end - 1 downTo start) {
            if (lines[index] !is Line.Other) lines.removeAt(index)
        }
    }
    fun render(): String = lines.joinToString(newline) { when (it) {
        is Line.Other -> it.raw
        is Line.Section -> it.raw
        is Line.Property -> it.raw
    } } + if (lines.isNotEmpty() && terminalNewline) newline else ""

    private fun renderProperty(section: String, property: String, value: String): String =
        if (section.isNotEmpty()) "$property=$value" else Properties().apply { setProperty(property, value) }
            .let { props -> StringWriter().also { props.store(it, null) }.toString().lineSequence().first { !it.startsWith('#') && it.isNotBlank() } }
}

object IniParser {
    const val MAX_BYTES = 32 * 1024
    fun parse(text: String, filename: String = "configuration.ini"): IniDocument {
        val lines = mutableListOf<IniDocument.Line>()
        val newline = if ("\r\n" in text) "\r\n" else "\n"
        val terminalNewline = text.isEmpty() || text.endsWith('\n') || text.endsWith('\r')
        var section = ""
        val seen = mutableSetOf<Pair<String, String>>()
        val sections = mutableSetOf<String>()
        val physicalLines = text.removePrefix("\uFEFF").lineSequence().toList()
        var index = 0
        while (index < physicalLines.size) {
            val start = index
            var raw = physicalLines[index]
            val trimmed = raw.trim()
            fun invalid(message: String): Nothing = throw IllegalArgumentException("$filename line ${start + 1}: $message")
            if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith(';') ||
                (section.isEmpty() && trimmed.startsWith('!'))) {
                lines.add(IniDocument.Line.Other(raw))
            } else if (trimmed.startsWith('[')) {
                if (!trimmed.endsWith(']')) invalid("invalid section")
                section = trimmed.substring(1, trimmed.length - 1).trim().lowercase(Locale.ROOT)
                if (section.isEmpty() || !sections.add(section)) invalid("duplicate or empty section [$section]")
                if (sections.size > 256) invalid("at most 256 sections are supported")
                lines.add(IniDocument.Line.Section(section, raw))
            } else {
                if (section.isEmpty()) {
                    // Preserve the complete physical record, but decode it with the
                    // same Java Properties grammar used by the original yame.ini reader.
                    while (physicalLines[index].countTrailingBackslashes() % 2 != 0 && index + 1 < physicalLines.size) {
                        raw += newline + physicalLines[++index]
                    }
                    val properties = try { Properties().apply { load(StringReader(raw)) } }
                    catch (error: IllegalArgumentException) { invalid("invalid property: ${error.message}") }
                    val name = properties.stringPropertyNames().singleOrNull() ?: invalid("invalid property")
                    lines.add(IniDocument.Line.Property(section, name, properties.getProperty(name), raw))
                } else {
                    val sep = raw.indexOfFirstUnescapedEquals()
                    if (sep < 0) invalid("expected key=value")
                    val name = raw.substring(0, sep).trim().lowercase(Locale.ROOT)
                    if (name.isEmpty()) invalid("expected key=value")
                    if (!seen.add(section to name)) invalid("duplicate setting '$name' in [$section]")
                    // The value starts immediately after the separator. Whitespace,
                    // extra '=' characters, backslashes and comment markers are data.
                    lines.add(IniDocument.Line.Property(section, name, raw.substring(sep + 1), raw))
                }
            }
            index++
        }
        // lineSequence includes the final empty line; render restores the original terminator.
        if (lines.lastOrNull() == IniDocument.Line.Other("")) lines.removeAt(lines.lastIndex)
        return IniDocument(lines, newline, terminalNewline)
    }

    private fun String.countTrailingBackslashes(): Int = length - trimEnd('\\').length

    private fun String.indexOfFirstUnescapedEquals(): Int {
        var backslashes = 0
        for (index in indices) {
            when (this[index]) {
                '\\' -> backslashes++
                '=' -> if (backslashes % 2 == 0) return index else backslashes = 0
                else -> backslashes = 0
            }
        }
        return -1
    }
}

object IniWriter { fun write(document: IniDocument): String = document.render() }
