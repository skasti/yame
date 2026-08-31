package no.skasti.serialmodem.ui

import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.NonBlockingReader

internal class JLineKeyboardInput private constructor(
    private val terminal: Terminal,
    private val originalAttributes: Attributes,
    private val reader: NonBlockingReader,
) : AutoCloseable {
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
        val second = reader.read(ESCAPE_SEQUENCE_TIMEOUT_MILLIS)
        if (second == NonBlockingReader.READ_EXPIRED || second < 0) {
            return "Escape"
        }
        if (second != '['.code) {
            return "Escape"
        }

        return when (reader.read(ESCAPE_SEQUENCE_TIMEOUT_MILLIS)) {
            'A'.code -> "ArrowUp"
            'B'.code -> "ArrowDown"
            'C'.code -> "ArrowRight"
            'D'.code -> "ArrowLeft"
            else -> "Escape"
        }
    }

    override fun close() {
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
            return JLineKeyboardInput(
                terminal = terminal,
                originalAttributes = originalAttributes,
                reader = terminal.reader(),
            )
        }

        private const val CTRL_C = 3
        private const val BACKSPACE = 8
        private const val ENTER_LF = 10
        private const val ENTER_CR = 13
        private const val ESCAPE = 27
        private const val DELETE = 127
        private const val ESCAPE_SEQUENCE_TIMEOUT_MILLIS = 30L
    }
}
