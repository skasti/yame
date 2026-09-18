package no.skasti.yame.ppp.ip
import no.skasti.yame.ppp.ip.internetChecksum
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.ip.Ipv4Packet
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Ipv4PacketTest {
    @Test
    fun `IPv4 packet round trips with a valid header checksum`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = Ipv4Packet(
            dscpEcn = 0x10,
            identification = 0x1234,
            flagsAndFragmentOffset = 0x4000,
            ttl = 60,
            protocol = Ipv4Packet.ICMP_PROTOCOL,
            source = Ipv4Address.parse("10.0.0.2"),
            destination = Ipv4Address.parse("10.0.0.1"),
            payload = payload,
        )

        val encoded = packet.encode()
        assertEquals(0, internetChecksum(encoded, 0, 20))

        val parsed = requireNotNull(Ipv4Packet.parse(encoded))
        assertEquals(0x10, parsed.dscpEcn)
        assertEquals(0x1234, parsed.identification)
        assertEquals(0x4000, parsed.flagsAndFragmentOffset)
        assertEquals(60, parsed.ttl)
        assertEquals(Ipv4Packet.ICMP_PROTOCOL, parsed.protocol)
        assertEquals(Ipv4Address.parse("10.0.0.2"), parsed.source)
        assertEquals(Ipv4Address.parse("10.0.0.1"), parsed.destination)
        assertContentEquals(payload, parsed.payload)
        assertFalse(parsed.isFragmented)
    }

    @Test
    fun `IPv4 parser rejects an invalid header checksum`() {
        val encoded = Ipv4Packet(
            protocol = Ipv4Packet.ICMP_PROTOCOL,
            source = Ipv4Address.parse("10.0.0.2"),
            destination = Ipv4Address.parse("10.0.0.1"),
            payload = byteArrayOf(1, 2, 3, 4),
        ).encode()

        encoded[8] = (encoded[8].toInt() xor 1).toByte()

        assertNull(Ipv4Packet.parse(encoded))
    }

    @Test
    fun `IPv4 parser exposes fragmentation state`() {
        val parsed = requireNotNull(
            Ipv4Packet.parse(
                Ipv4Packet(
                    flagsAndFragmentOffset = 0x2001,
                    protocol = Ipv4Packet.ICMP_PROTOCOL,
                    source = Ipv4Address.parse("10.0.0.2"),
                    destination = Ipv4Address.parse("10.0.0.1"),
                    payload = byteArrayOf(1, 2, 3, 4),
                ).encode(),
            ),
        )

        assertTrue(parsed.moreFragments)
        assertEquals(1, parsed.fragmentOffset)
        assertTrue(parsed.isFragmented)
    }
}
