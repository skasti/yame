package no.skasti.serialmodem.ppp

data class TcpPacket(
    val sourcePort: Int,
    val destinationPort: Int,
    val sequenceNumber: UInt,
    val acknowledgmentNumber: UInt = 0u,
    val flags: Int,
    val windowSize: Int = DEFAULT_WINDOW_SIZE,
    val urgentPointer: Int = 0,
    val options: ByteArray = ByteArray(0),
    val payload: ByteArray = ByteArray(0),
) {
    val sequenceSpaceLength: Int
        get() = payload.size + (if (hasFlag(SYN)) 1 else 0) + (if (hasFlag(FIN)) 1 else 0)

    fun hasFlag(flag: Int): Boolean = flags and flag != 0

    fun maximumSegmentSizeOption(): Int? {
        var offset = 0
        while (offset < options.size) {
            when (val kind = options[offset].toInt() and 0xff) {
                OPTION_END_OF_LIST -> return null
                OPTION_NO_OPERATION -> offset++
                else -> {
                    if (offset + 1 >= options.size) return null
                    val length = options[offset + 1].toInt() and 0xff
                    if (length < 2 || offset + length > options.size) return null
                    if (kind == OPTION_MAXIMUM_SEGMENT_SIZE && length == 4) {
                        return ((options[offset + 2].toInt() and 0xff) shl 8) or
                            (options[offset + 3].toInt() and 0xff)
                    }
                    offset += length
                }
            }
        }
        return null
    }

    fun encode(
        source: Ipv4Address,
        destination: Ipv4Address,
    ): ByteArray {
        require(sourcePort in 0..0xffff) { "TCP source port must be 0..65535" }
        require(destinationPort in 0..0xffff) { "TCP destination port must be 0..65535" }
        require(flags in 0..ALL_FLAGS) { "TCP flags contain unsupported bits" }
        require(windowSize in 0..0xffff) { "TCP window size must be 0..65535" }
        require(urgentPointer in 0..0xffff) { "TCP urgent pointer must be 0..65535" }
        require(options.size <= MAX_OPTIONS_LENGTH && options.size % 4 == 0) {
            "TCP options must be 0..40 bytes and padded to a 32-bit boundary"
        }

        val headerLength = MIN_HEADER_LENGTH + options.size
        val segmentLength = headerLength + payload.size
        require(segmentLength <= 0xffff) { "TCP segment exceeds IPv4 maximum length" }

        val bytes = ByteArray(segmentLength)
        writeU16(bytes, 0, sourcePort)
        writeU16(bytes, 2, destinationPort)
        writeU32(bytes, 4, sequenceNumber)
        writeU32(bytes, 8, acknowledgmentNumber)
        bytes[12] = (((headerLength / 4) shl 4) or ((flags ushr 8) and 0x01)).toByte()
        bytes[13] = flags.toByte()
        writeU16(bytes, 14, windowSize)
        writeU16(bytes, 18, urgentPointer)
        options.copyInto(bytes, MIN_HEADER_LENGTH)
        payload.copyInto(bytes, headerLength)

        writeU16(bytes, 16, tcpChecksum(source, destination, bytes))
        return bytes
    }

    companion object {
        const val FIN = 0x001
        const val SYN = 0x002
        const val RST = 0x004
        const val PSH = 0x008
        const val ACK = 0x010
        const val URG = 0x020
        const val ECE = 0x040
        const val CWR = 0x080
        const val NS = 0x100
        const val ALL_FLAGS = 0x1ff
        const val DEFAULT_WINDOW_SIZE = 0xffff
        const val MIN_HEADER_LENGTH = 20
        const val DEFAULT_IPV4_MAXIMUM_SEGMENT_SIZE = 536
        private const val OPTION_END_OF_LIST = 0
        private const val OPTION_NO_OPERATION = 1
        private const val OPTION_MAXIMUM_SEGMENT_SIZE = 2
        private const val MAX_OPTIONS_LENGTH = 40

        fun parse(
            bytes: ByteArray,
            source: Ipv4Address,
            destination: Ipv4Address,
        ): TcpPacket? {
            if (bytes.size < MIN_HEADER_LENGTH || bytes.size > 0xffff) return null

            val headerLength = ((bytes[12].toInt() ushr 4) and 0x0f) * 4
            if (
                headerLength < MIN_HEADER_LENGTH ||
                headerLength > MIN_HEADER_LENGTH + MAX_OPTIONS_LENGTH ||
                headerLength > bytes.size
            ) {
                return null
            }

            if (tcpChecksum(source, destination, bytes) != 0) return null

            val flags = ((bytes[12].toInt() and 0x01) shl 8) or (bytes[13].toInt() and 0xff)
            return TcpPacket(
                sourcePort = readU16(bytes, 0),
                destinationPort = readU16(bytes, 2),
                sequenceNumber = readU32(bytes, 4),
                acknowledgmentNumber = readU32(bytes, 8),
                flags = flags,
                windowSize = readU16(bytes, 14),
                urgentPointer = readU16(bytes, 18),
                options = bytes.copyOfRange(MIN_HEADER_LENGTH, headerLength),
                payload = bytes.copyOfRange(headerLength, bytes.size),
            )
        }

        private fun tcpChecksum(
            source: Ipv4Address,
            destination: Ipv4Address,
            segment: ByteArray,
        ): Int {
            val pseudoHeader = ByteArray(12 + segment.size)
            source.toByteArray().copyInto(pseudoHeader, 0)
            destination.toByteArray().copyInto(pseudoHeader, 4)
            pseudoHeader[9] = Ipv4Packet.TCP_PROTOCOL.toByte()
            writeU16(pseudoHeader, 10, segment.size)
            segment.copyInto(pseudoHeader, 12)
            return internetChecksum(pseudoHeader)
        }

        private fun readU16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 8) or
                (bytes[offset + 1].toInt() and 0xff)

        private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 8).toByte()
            bytes[offset + 1] = value.toByte()
        }

        private fun readU32(bytes: ByteArray, offset: Int): UInt =
            ((bytes[offset].toUInt() and 0xffu) shl 24) or
                ((bytes[offset + 1].toUInt() and 0xffu) shl 16) or
                ((bytes[offset + 2].toUInt() and 0xffu) shl 8) or
                (bytes[offset + 3].toUInt() and 0xffu)

        private fun writeU32(bytes: ByteArray, offset: Int, value: UInt) {
            bytes[offset] = (value shr 24).toByte()
            bytes[offset + 1] = (value shr 16).toByte()
            bytes[offset + 2] = (value shr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
    }
}
