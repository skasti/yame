package no.skasti.serialmodem.ppp

data class UdpPacket(
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
) {
    fun encode(
        source: Ipv4Address,
        destination: Ipv4Address,
        includeChecksum: Boolean = true,
    ): ByteArray {
        require(sourcePort in 0..0xffff) { "UDP source port must be 0..65535" }
        require(destinationPort in 0..0xffff) { "UDP destination port must be 0..65535" }
        require(payload.size <= MAX_PAYLOAD_LENGTH) { "UDP payload exceeds maximum length" }

        val length = HEADER_LENGTH + payload.size
        val bytes = ByteArray(length)
        writeU16(bytes, 0, sourcePort)
        writeU16(bytes, 2, destinationPort)
        writeU16(bytes, 4, length)
        payload.copyInto(bytes, HEADER_LENGTH)

        if (includeChecksum) {
            val checksum = udpChecksum(source, destination, bytes)
            writeU16(bytes, 6, if (checksum == 0) 0xffff else checksum)
        }
        return bytes
    }

    companion object {
        const val HEADER_LENGTH = 8
        private const val MAX_PAYLOAD_LENGTH = 0xffff - HEADER_LENGTH

        fun parse(
            bytes: ByteArray,
            source: Ipv4Address,
            destination: Ipv4Address,
        ): UdpPacket? {
            if (bytes.size < HEADER_LENGTH) return null

            val length = readU16(bytes, 4)
            if (length < HEADER_LENGTH || length != bytes.size) return null

            val datagram = bytes
            val checksum = readU16(datagram, 6)
            if (checksum != 0 && udpChecksum(source, destination, datagram) != 0) {
                return null
            }

            return UdpPacket(
                sourcePort = readU16(datagram, 0),
                destinationPort = readU16(datagram, 2),
                payload = datagram.copyOfRange(HEADER_LENGTH, datagram.size),
            )
        }

        private fun udpChecksum(
            source: Ipv4Address,
            destination: Ipv4Address,
            datagram: ByteArray,
        ): Int {
            val pseudoHeader = ByteArray(12 + datagram.size)
            source.toByteArray().copyInto(pseudoHeader, 0)
            destination.toByteArray().copyInto(pseudoHeader, 4)
            pseudoHeader[9] = Ipv4Packet.UDP_PROTOCOL.toByte()
            writeU16(pseudoHeader, 10, datagram.size)
            datagram.copyInto(pseudoHeader, 12)
            return internetChecksum(pseudoHeader)
        }

        private fun readU16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 8) or
                (bytes[offset + 1].toInt() and 0xff)

        private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 8).toByte()
            bytes[offset + 1] = value.toByte()
        }
    }
}
