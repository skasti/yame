package no.skasti.serialmodem.ui

import org.jline.utils.NonBlockingPumpReader
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
    fun `partial escape sequence times out without blocking later input`() {
        val reader = NonBlockingPumpReader()
        val decoder = decoder(reader)

        reader.writer.write("\u001b[")

        assertEquals("Escape", decoder.readKey(100))

        reader.writer.write("q")
        assertEquals("q", decoder.readKey(100))

        reader.close()
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
