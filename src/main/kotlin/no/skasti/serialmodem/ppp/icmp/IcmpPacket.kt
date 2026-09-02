package no.skasti.serialmodem.ppp.icmp

data class IcmpPacket(
    val type: Int,
    val code: Int,
    val body: ByteArray,
) {
    fun encode(): ByteArray {
        require(type in 0..0xff)
        require(code in 0..0xff)

        val bytes = ByteArray(HEADER_LENGTH + body.size)
        bytes[0] = type.toByte()
        bytes[1] = code.toByte()
        body.copyInto(bytes, HEADER_LENGTH)

        val checksum = internetChecksum(bytes)
        bytes[2] = (checksum ushr 8).toByte()
        bytes[3] = checksum.toByte()
        return bytes
    }

    fun echoIdentifier(): Int? =
        if (body.size >= ECHO_HEADER_LENGTH) readU16(body, 0) else null

    fun echoSequence(): Int? =
        if (body.size >= ECHO_HEADER_LENGTH) readU16(body, 2) else null

    fun toEchoReply(): IcmpPacket? =
        if (type == ECHO_REQUEST && code == 0 && body.size >= ECHO_HEADER_LENGTH) {
            IcmpPacket(
                type = ECHO_REPLY,
                code = 0,
                body = body.copyOf(),
            )
        } else {
            null
        }

    companion object {
        const val ECHO_REPLY = 0
        const val ECHO_REQUEST = 8

        private const val HEADER_LENGTH = 4
        private const val ECHO_HEADER_LENGTH = 4

        fun parse(bytes: ByteArray): IcmpPacket? {
            if (bytes.size < HEADER_LENGTH || internetChecksum(bytes) != 0) return null

            return IcmpPacket(
                type = bytes[0].toInt() and 0xff,
                code = bytes[1].toInt() and 0xff,
                body = bytes.copyOfRange(HEADER_LENGTH, bytes.size),
            )
        }

        private fun readU16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 8) or
                (bytes[offset + 1].toInt() and 0xff)
    }
}
