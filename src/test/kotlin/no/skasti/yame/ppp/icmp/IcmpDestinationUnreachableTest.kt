package no.skasti.yame.ppp.icmp

import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.ip.Ipv4Packet
import no.skasti.yame.ppp.udp.UdpPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IcmpDestinationUnreachableTest {
    private val source = Ipv4Address.parse("10.0.0.1")
    private val destination = Ipv4Address.parse("10.0.0.2")

    @Test
    fun `partial quotes accept every IPv4 header size without requiring the original payload`() {
        for (optionsLength in 0..40 step 4) {
            val quote = quote(ByteArray(optionsLength))
            val error = assertNotNull(IcmpDestinationUnreachable.parse(quote))
            assertEquals(source, error.source)
            assertEquals(destination, error.destination)
            assertEquals(53, error.sourcePort)
            assertEquals(1105, error.destinationPort)
            assertEquals("port unreachable", error.reason)
            assertNull(IcmpDestinationUnreachable.parse(quote.copy(body = quote.body.dropLast(1).toByteArray())))
        }
    }

    @Test
    fun `invalid quoted IPv4 checksum version header length and UDP length are rejected`() {
        val valid = quote()
        val mutations = listOf<Pair<Int, Int>>(4 to 0x65, 4 to 0x44, 4 to 0x4f,
            7 to 27, 14 to 1, 29 to 7)
        for ((offset, value) in mutations) {
            val body = valid.body.copyOf().apply { this[offset] = value.toByte() }
            assertNull(IcmpDestinationUnreachable.parse(valid.copy(body = body)), "offset=$offset value=$value")
        }
        assertNull(IcmpDestinationUnreachable.parse(valid.copy(code = 255)))
        assertNull(IcmpDestinationUnreachable.parse(valid.copy(type = IcmpPacket.ECHO_REQUEST)))
        for (length in 0 until valid.body.size) {
            assertNull(IcmpDestinationUnreachable.parse(valid.copy(body = valid.body.copyOf(length))))
        }
    }

    private fun quote(options: ByteArray = ByteArray(0)): IcmpPacket {
        val packet = Ipv4Packet(protocol = Ipv4Packet.UDP_PROTOCOL, source = source, destination = destination,
            options = options, payload = UdpPacket(53, 1105, ByteArray(64)).encode(source, destination))
        return IcmpPacket(IcmpPacket.DESTINATION_UNREACHABLE, 3,
            ByteArray(4) + packet.encode().copyOf(20 + options.size + 8))
    }
}
