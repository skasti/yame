package no.skasti.yame.ppp.icmp

import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.ip.Ipv4Packet
import no.skasti.yame.ppp.ip.internetChecksum

/** An ICMP error quotes an IPv4 header and at least eight payload bytes, not a full packet. */
data class IcmpDestinationUnreachable(
    val code: Int,
    val source: Ipv4Address,
    val destination: Ipv4Address,
    val protocol: Int,
    val sourcePort: Int?,
    val destinationPort: Int?,
) {
    init {
        require(code in REASONS.indices) { "Unsupported Destination Unreachable code" }
    }

    val reason: String
        get() = REASONS[code]

    companion object {
        const val PORT_UNREACHABLE = 3

        private val REASONS = listOf(
            "network unreachable", "host unreachable", "protocol unreachable", "port unreachable",
            "fragmentation needed", "source route failed", "destination network unknown",
            "destination host unknown", "source host isolated", "network administratively prohibited",
            "host administratively prohibited", "network unreachable for TOS", "host unreachable for TOS",
            "communication administratively prohibited", "host precedence violation", "precedence cutoff",
        )

        fun parse(packet: IcmpPacket): IcmpDestinationUnreachable? {
            if (packet.type != IcmpPacket.DESTINATION_UNREACHABLE || packet.code !in REASONS.indices) return null

            // The first four body bytes are unused (or contain the next-hop MTU for code 4).
            val bytes = packet.body
            val offset = 4
            if (bytes.size < offset + 20 + 8) return null
            val version = (bytes[offset].toInt() ushr 4) and 0x0f
            val headerLength = (bytes[offset].toInt() and 0x0f) * 4
            if (version != 4 || headerLength < 20 || bytes.size < offset + headerLength + 8) return null
            if (readU16(bytes, offset + 2) < headerLength + 8) return null
            if (internetChecksum(bytes, offset, headerLength) != 0) return null

            val protocol = bytes[offset + 9].toInt() and 0xff
            val fragmented = readU16(bytes, offset + 6) and 0x3fff != 0
            val hasPorts = !fragmented && (protocol == Ipv4Packet.UDP_PROTOCOL || protocol == Ipv4Packet.TCP_PROTOCOL)
            val transportOffset = offset + headerLength
            if (!fragmented && protocol == Ipv4Packet.UDP_PROTOCOL) {
                val udpLength = readU16(bytes, transportOffset + 4)
                if (udpLength < 8 || udpLength != readU16(bytes, offset + 2) - headerLength) return null
            }

            return IcmpDestinationUnreachable(
                code = packet.code,
                source = Ipv4Address.fromBytes(bytes, offset + 12),
                destination = Ipv4Address.fromBytes(bytes, offset + 16),
                protocol = protocol,
                sourcePort = if (hasPorts) readU16(bytes, transportOffset) else null,
                destinationPort = if (hasPorts) readU16(bytes, transportOffset + 2) else null,
            )
        }

        private fun readU16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }
}
