package no.skasti.serialmodem.ppp

import java.io.ByteArrayOutputStream

class PppEncoder(
    var transmitAccm: UInt = DEFAULT_TRANSMIT_ACCM,
    var protocolFieldCompression: Boolean = false,
    var addressControlFieldCompression: Boolean = false,
) {
    fun encode(frame: PppFrame): ByteArray {
        val decoded = ByteArrayOutputStream()

        if (!addressControlFieldCompression || frame.protocol == LCP_PROTOCOL) {
            decoded.write(ADDRESS)
            decoded.write(CONTROL)
        }

        if (protocolFieldCompression && frame.protocol <= 0xff && (frame.protocol and 0x01) != 0) {
            decoded.write(frame.protocol)
        } else {
            decoded.write((frame.protocol ushr 8) and 0xff)
            decoded.write(frame.protocol and 0xff)
        }

        decoded.write(frame.payload)

        val withoutFcs = decoded.toByteArray()
        val fcs = PppFcs.calculate(withoutFcs) xor 0xffff
        decoded.write(fcs and 0xff)
        decoded.write((fcs ushr 8) and 0xff)

        val wire = ByteArrayOutputStream()
        wire.write(FLAG)
        for (byte in decoded.toByteArray()) {
            val value = byte.toInt() and 0xff
            if (shouldEscape(value)) {
                wire.write(ESCAPE)
                wire.write(value xor ESCAPE_MASK)
            } else {
                wire.write(value)
            }
        }
        wire.write(FLAG)
        return wire.toByteArray()
    }

    private fun shouldEscape(value: Int): Boolean =
        value == FLAG ||
            value == ESCAPE ||
            (value < 0x20 && ((transmitAccm shr value) and 1u) != 0u)

    companion object {
        private const val FLAG = 0x7e
        private const val ESCAPE = 0x7d
        private const val ESCAPE_MASK = 0x20
        private const val ADDRESS = 0xff
        private const val CONTROL = 0x03
        private const val LCP_PROTOCOL = 0xc021

        const val DEFAULT_TRANSMIT_ACCM: UInt = 0xffffffffu
    }
}
