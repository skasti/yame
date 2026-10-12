package no.skasti.yame.ppp.lcp

/** Builds RFC 1661 section 5.7 replies without interpreting the unsupported protocol. */
object LcpProtocolReject {
    const val CODE = 8
    private const val HEADER_AND_PROTOCOL_SIZE = 6

    fun create(protocol: Int, identifier: Int, information: ByteArray, peerMru: Int): LcpPacket? {
        // Keep both the LCP header and the two-octet Rejected-Protocol intact.
        if (peerMru < HEADER_AND_PROTOCOL_SIZE) return null
        val informationSize = minOf(information.size, peerMru - HEADER_AND_PROTOCOL_SIZE)
        return LcpPacket(
            code = CODE,
            identifier = identifier,
            data = byteArrayOf((protocol ushr 8).toByte(), protocol.toByte()) +
                information.copyOf(informationSize),
        )
    }
}
