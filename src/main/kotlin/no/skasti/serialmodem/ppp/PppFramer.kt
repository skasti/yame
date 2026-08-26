package no.skasti.serialmodem.ppp

import java.io.ByteArrayOutputStream

class PppFramer(
    private val onFrame: (PppFrame) -> Unit,
    private val onInvalidFrame: (ByteArray) -> Unit = {},
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

                State.IN_FRAME -> when (value) {
                    FLAG -> finishFrame()
                    ESCAPE -> state = State.ESCAPED
                    else -> buffer.write(value)
                }

                State.ESCAPED -> {
                    buffer.write(value xor ESCAPE_MASK)
                    state = State.IN_FRAME
                }
            }
        }
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
        private const val MIN_FRAME_SIZE = 4
    }
}
