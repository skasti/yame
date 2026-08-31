package no.skasti.serialmodem.ui

import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.InfoCmp.Capability
import org.jline.utils.NonBlockingReader

internal class JLineKeyboardInput private constructor(
    private val terminal: Terminal,
    private val originalAttributes: Attributes,
    private val decoder: TerminalKeyDecoder,
) : AutoCloseable {
    fun readKey(timeoutMillis: Long): String? = decoder.readKey(timeoutMillis)

    override fun close() {
        runCatching {
            terminal.puts(Capability.keypad_local)
            terminal.flush()
        }
        runCatching { terminal.setAttributes(originalAttributes) }
        runCatching { terminal.close() }
    }

    companion object {
        fun open(): JLineKeyboardInput {
            val terminal = TerminalBuilder.builder()
                .system(true)
                .provider("jni")
                .build()
            val originalAttributes = terminal.enterRawMode()

            // Terminfo cursor-key capabilities describe application-keypad mode.
            // Enable that mode so those sequences match on Windows and Unix.
            terminal.puts(Capability.keypad_xmit)
            terminal.flush()

            return JLineKeyboardInput(
                terminal = terminal,
                originalAttributes = originalAttributes,
                decoder = TerminalKeyDecoder(
                    reader = terminal.reader(),
                    escapeBindings = escapeBindings(terminal),
                ),
            )
        }

        private fun escapeBindings(terminal: Terminal): Map<String, String> =
            linkedMapOf(
                ESCAPE_STRING to "Escape",
                "\u001b[A" to "ArrowUp",
                "\u001b[B" to "ArrowDown",
                "\u001b[C" to "ArrowRight",
                "\u001b[D" to "ArrowLeft",
                "\u001bOA" to "ArrowUp",
                "\u001bOB" to "ArrowDown",
                "\u001bOC" to "ArrowRight",
                "\u001bOD" to "ArrowLeft",
            ).apply {
                bindCapability(terminal, "ArrowUp", Capability.key_up)
                bindCapability(terminal, "ArrowDown", Capability.key_down)
                bindCapability(terminal, "ArrowRight", Capability.key_right)
                bindCapability(terminal, "ArrowLeft", Capability.key_left)
            }

        private fun MutableMap<String, String>.bindCapability(
            terminal: Terminal,
            binding: String,
            capability: Capability,
        ) {
            terminal.getStringCapability(capability)
                ?.takeIf { it.isNotEmpty() }
                ?.let { sequence -> this[sequence] = binding }
        }

        private const val ESCAPE_STRING = "\u001b"
    }
}

internal class TerminalKeyDecoder(
    private val reader: NonBlockingReader,
    private val escapeBindings: Map<String, String>,
) {
    fun readKey(timeoutMillis: Long): String? {
        val first = reader.read(timeoutMillis)
        if (first == NonBlockingReader.READ_EXPIRED || first < 0) return null

        return when (first) {
            CTRL_C -> "Ctrl+C"
            ENTER_CR, ENTER_LF -> "Enter"
            BACKSPACE, DELETE -> "Backspace"
            ESCAPE -> readEscapeSequence()
            else -> first.toChar().toString()
        }
    }

    private fun readEscapeSequence(): String {
        val sequence = StringBuilder().append(ESCAPE.toChar())

        while (true) {
            val current = sequence.toString()
            val exactBinding = escapeBindings[current]
            val matchingSequences = escapeBindings.keys.filter { it.startsWith(current) }

            if (matchingSequences.isEmpty()) {
                return exactBinding ?: "Escape"
            }

            val hasLongerMatch = matchingSequences.any { it.length > current.length }
            if (exactBinding != null && !hasLongerMatch) {
                return exactBinding
            }

            val next = reader.read(ESCAPE_SEQUENCE_TIMEOUT_MILLIS)
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                return exactBinding ?: "Escape"
            }
            sequence.append(next.toChar())
        }
    }

    private companion object {
        const val CTRL_C = 3
        const val BACKSPACE = 8
        const val ENTER_LF = 10
        const val ENTER_CR = 13
        const val ESCAPE = 27
        const val DELETE = 127
        const val ESCAPE_SEQUENCE_TIMEOUT_MILLIS = 50L
    }
}
