package no.skasti.serialmodem.ppp.ip

import no.skasti.serialmodem.ppp.icmp.internetChecksum

data class Packets(
    val dscpEcn: Int = 0,
    val identification: Int = 0,
    val flagsAndFragmentOffset: Int = 0,
    val ttl: Int = DEFAULT_TTL,
    val protocol: Int,
    val source: Ipv4Address,
    val destination: Ipv4Address,
    val options: ByteArray = ByteArray(0),
    val payload: ByteArray,
) {
    val fragmentOffset: Int
        get() = flagsAndFragmentOffset and 0x1fff

    val moreFragments: Boolean
        get() = flagsAndFragmentOffset and MORE_FRAGMENTS_FLAG != 0

    val isFragmented: Boolean
        get() = moreFragments || fragmentOffset != 0

    fun encode(): ByteArray {
        require(dscpEcn in 0..0xff)
        require(identification in 0..0xffff)
        require(flagsAndFragmentOffset in 0..0xffff)
        require(ttl in 0..0xff)
        require(protocol in 0..0xff)
        require(options.size <= MAX_OPTIONS_LENGTH && options.size % 4 == 0) {
            "IPv4 options must be 0..40 bytes and padded to a 32-bit boundary"
        }

        val headerLength = MIN_HEADER_LENGTH + options.size
        val totalLength = headerLength + payload.size
        require(totalLength <= 0xffff) { "IPv4 packet exceeds maximum length" }

        val bytes = ByteArray(totalLength)
        bytes[0] = ((VERSION shl 4) or (headerLength / 4)).toByte()
        bytes[1] = dscpEcn.toByte()
        writeU16(bytes, 2, totalLength)
        writeU16(bytes, 4, identification)
        writeU16(bytes, 6, flagsAndFragmentOffset)
        bytes[8] = ttl.toByte()
        bytes[9] = protocol.toByte()
        source.toByteArray().copyInto(bytes, 12)
        destination.toByteArray().copyInto(bytes, 16)
        options.copyInto(bytes, MIN_HEADER_LENGTH)
        payload.copyInto(bytes, headerLength)

        val checksum = internetChecksum(bytes, 0, headerLength)
        writeU16(bytes, 10, checksum)
        return bytes
    }

    companion object {
        const val ICMP_PROTOCOL = 1
        const val TCP_PROTOCOL = 6
        const val UDP_PROTOCOL = 17
        const val DEFAULT_TTL = 64

        private const val VERSION = 4
        private const val MIN_HEADER_LENGTH = 20
        private const val MAX_OPTIONS_LENGTH = 40
        private const val MORE_FRAGMENTS_FLAG = 0x2000

        fun parse(bytes: ByteArray): Packets? {
            if (bytes.size < MIN_HEADER_LENGTH) return null

            val version = (bytes[0].toInt() ushr 4) and 0x0f
            val headerLength = (bytes[0].toInt() and 0x0f) * 4
            if (version != VERSION || headerLength < MIN_HEADER_LENGTH || headerLength > bytes.size) {
                return null
            }

            val totalLength = readU16(bytes, 2)
            if (totalLength < headerLength || totalLength > bytes.size) return null
            if (internetChecksum(bytes, 0, headerLength) != 0) return null

            return Packets(
                dscpEcn = bytes[1].toInt() and 0xff,
                identification = readU16(bytes, 4),
                flagsAndFragmentOffset = readU16(bytes, 6),
                ttl = bytes[8].toInt() and 0xff,
                protocol = bytes[9].toInt() and 0xff,
                source = Ipv4Address.Companion.fromBytes(bytes, 12),
                destination = Ipv4Address.Companion.fromBytes(bytes, 16),
                options = bytes.copyOfRange(MIN_HEADER_LENGTH, headerLength),
                payload = bytes.copyOfRange(headerLength, totalLength),
            )
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