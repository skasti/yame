package no.skasti.yame.ppp

import java.io.ByteArrayOutputStream

class PppFramer(
    private val onFrame: (PppFrame) -> Unit,
    private val onInvalidFrame: (ByteArray) -> Unit = {},
    var receiveAccm: UInt = DEFAULT_RECEIVE_ACCM,
) {
    private enum class State {
        WAITING_FOR_FRAME,
        IN_FRAME,
        ESCAPED,
    }

    private var state = State.WAITING_FOR_FRAME
    private val buffer = ByteArrayOutputStream()

    fun receive(bytes: ByteArray) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            when (state) {
                State.WAITING_FOR_FRAME -> {
                    if (value == FLAG) {
                        buffer.reset()
                        state = State.IN_FRAME
                    }
                }

                State.IN_FRAME -> when {
                    value == FLAG -> finishFrame()
                    value == ESCAPE -> state = State.ESCAPED
                    isMappedControlCharacter(value) -> Unit
                    else -> appendDecodedByte(value)
                }

                State.ESCAPED -> {
                    when {
                        value == FLAG -> {
                            // A Control Escape immediately followed by a Flag Sequence
                            // aborts the current frame. The flag also starts the next one.
                            buffer.reset()
                            state = State.IN_FRAME
                        }

                        isMappedControlCharacter(value) -> {
                            // An ACCM-mapped control octet may have been inserted by
                            // intermediate equipment. Discard it without consuming the
                            // pending escape; the next octet is still the escaped value.
                        }

                        appendDecodedByte(value xor ESCAPE_MASK) -> {
                            state = State.IN_FRAME
                        }
                    }
                }
            }
        }
    }

    private fun isMappedControlCharacter(value: Int): Boolean =
        value < 0x20 && ((receiveAccm shr value) and 1u) != 0u

    private fun appendDecodedByte(value: Int): Boolean {
        if (buffer.size() >= MAX_DECODED_FRAME_SIZE) {
            // No closing flag arrived within a plausible PPP frame size. Drop the
            // partial frame and ignore input until a new flag resynchronizes us.
            buffer.reset()
            state = State.WAITING_FOR_FRAME
            return false
        }

        buffer.write(value)
        return true
    }

    private fun finishFrame() {
        val raw = buffer.toByteArray()
        buffer.reset()
        state = State.IN_FRAME

        if (raw.isEmpty()) return
        if (raw.size < MIN_FRAME_SIZE || !PppFcs.isValid(raw)) {
            onInvalidFrame(raw)
            return
        }

        val withoutFcs = raw.copyOf(raw.size - 2)
        var index = 0

        if (withoutFcs.size >= 2 &&
            (withoutFcs[0].toInt() and 0xff) == ADDRESS &&
            (withoutFcs[1].toInt() and 0xff) == CONTROL
        ) {
            index = 2
        }

        if (index >= withoutFcs.size) {
            onInvalidFrame(raw)
            return
        }

        val firstProtocolByte = withoutFcs[index].toInt() and 0xff
        val protocol: Int
        if ((firstProtocolByte and 0x01) != 0) {
            protocol = firstProtocolByte
            index += 1
        } else {
            if (index + 1 >= withoutFcs.size) {
                onInvalidFrame(raw)
                return
            }
            protocol = (firstProtocolByte shl 8) or (withoutFcs[index + 1].toInt() and 0xff)
            index += 2
        }

        onFrame(
            PppFrame(
                protocol = protocol,
                payload = withoutFcs.copyOfRange(index, withoutFcs.size),
            ),
        )
    }

    companion object {
        private const val FLAG = 0x7e
        private const val ESCAPE = 0x7d
        private const val ESCAPE_MASK = 0x20
        private const val ADDRESS = 0xff
        private const val CONTROL = 0x03
        // RFC 1662 section 4.3 defines frames shorter than four octets as invalid
        // when using the 16-bit FCS, even when header compression is negotiated.
        private const val MIN_FRAME_SIZE = 4

        const val DEFAULT_RECEIVE_ACCM: UInt = 0xffffffffu

        // The negotiated MRU is normally much smaller (1500 by default), but the
        // framing layer should not assume a negotiated value. This limit allows
        // the largest 16-bit information field plus address/control, protocol and FCS.
        internal const val MAX_DECODED_FRAME_SIZE = 65_541
    }
}
