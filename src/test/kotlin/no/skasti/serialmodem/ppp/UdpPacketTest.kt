package no.skasti.serialmodem.ppp

import no.skasti.serialmodem.ppp.ip.Ipv4Address
import no.skasti.serialmodem.ppp.udp.UdpPacket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UdpPacketTest {
    @Test
    fun `UDP packet round trips with IPv4 pseudo-header checksum`() {
        val source = Ipv4Address.parse("10.0.0.2")
        val destination = Ipv4Address.parse("8.8.8.8")
        val packet = UdpPacket(
            sourcePort = 1037,
            destinationPort = 53,
            payload = byteArrayOf(1, 2, 3, 4, 5),
        )

        val encoded = packet.encode(source, destination)
        val parsed = requireNotNull(UdpPacket.parse(encoded, source, destination))

        assertEquals(1037, parsed.sourcePort)
        assertEquals(53, parsed.destinationPort)
        assertContentEquals(packet.payload, parsed.payload)
    }

    @Test
    fun `IPv4 UDP checksum zero is accepted`() {
        val source = Ipv4Address.parse("10.0.0.2")
        val destination = Ipv4Address.parse("8.8.8.8")
        val encoded = UdpPacket(
            sourcePort = 1037,
            destinationPort = 53,
            payload = byteArrayOf(1, 2, 3),
        ).encode(source, destination, includeChecksum = false)

        val parsed = requireNotNull(UdpPacket.parse(encoded, source, destination))
        assertContentEquals(byteArrayOf(1, 2, 3), parsed.payload)
    }

    @Test
    fun `invalid UDP checksum is rejected`() {
        val source = Ipv4Address.parse("10.0.0.2")
        val destination = Ipv4Address.parse("8.8.8.8")
        val encoded = UdpPacket(
            sourcePort = 1037,
            destinationPort = 53,
            payload = byteArrayOf(1, 2, 3),
        ).encode(source, destination)
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()

        assertNull(UdpPacket.parse(encoded, source, destination))
    }
    @Test
    fun `UDP length must match IPv4 payload length`() {
        val source = Ipv4Address.parse("10.0.0.2")
        val destination = Ipv4Address.parse("8.8.8.8")
        val encoded = UdpPacket(
            sourcePort = 1037,
            destinationPort = 53,
            payload = byteArrayOf(1, 2, 3),
        ).encode(source, destination) + byteArrayOf(0)

        assertNull(UdpPacket.parse(encoded, source, destination))
    }

}
