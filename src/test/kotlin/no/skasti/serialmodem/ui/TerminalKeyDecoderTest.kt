package no.skasti.serialmodem.ui

import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.utils.InfoCmp.Capability
import org.jline.utils.NonBlockingPumpReader
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalKeyDecoderTest {
    @Test
    fun `decodes CSI and SS3 arrow keys`() {
        val reader = NonBlockingPumpReader()
        val decoder = decoder(reader)

        reader.writer.write("\u001b[A\u001bOB")

        assertEquals("ArrowUp", decoder.readKey(100))
        assertEquals("ArrowDown", decoder.readKey(100))

        reader.close()
    }

    @Test
    fun `terminal cleanup exits application keypad mode before restoring shell`() {
        val calls = mutableListOf<String>()
        val terminal =
            Proxy.newProxyInstance(
                Terminal::class.java.classLoader,
                arrayOf(Terminal::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "puts" -> {
                        calls += "puts:${args?.firstOrNull()}"
                        true
                    }
                    "flush" -> {
                        calls += "flush"
                        null
                    }
                    "setAttributes" -> {
                        calls += "setAttributes"
                        null
                    }
                    "close" -> {
                        calls += "close"
                        null
                    }
                    "toString" -> "fake-terminal"
                    "hashCode" -> System.identityHashCode(terminalProxySentinel)
                    "equals" -> false
                    else -> defaultReturnValue(method.returnType)
                }
            } as Terminal

        restoreTerminalState(terminal, Attributes(), keypadModeEntered = true)

        assertEquals(
            listOf(
                "puts:${Capability.keypad_local}",
                "flush",
                "setAttributes",
                "close",
            ),
            calls,
        )
    }

    @Test
    fun `partial escape sequence times out without blocking later input`() {
        val reader = NonBlockingPumpReader()
        val decoder = decoder(reader)

        reader.writer.write("\u001b[")

        assertEquals("Escape", decoder.readKey(100))

        reader.writer.write("q")
        assertEquals("q", decoder.readKey(100))

        reader.close()
    }

    private val terminalProxySentinel = Any()

    private fun defaultReturnValue(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }

    private fun decoder(reader: NonBlockingPumpReader) =
        TerminalKeyDecoder(
            reader = reader,
            escapeBindings = mapOf(
                "\u001b" to "Escape",
                "\u001b[A" to "ArrowUp",
                "\u001b[B" to "ArrowDown",
                "\u001bOA" to "ArrowUp",
                "\u001bOB" to "ArrowDown",
            ),
        )
}
