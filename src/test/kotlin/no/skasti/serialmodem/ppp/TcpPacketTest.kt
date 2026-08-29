package no.skasti.serialmodem.ppp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TcpPacketTest {
    private val source = Ipv4Address.parse("10.0.0.2")
    private val destination = Ipv4Address.parse("93.184.216.34")

    @Test
    fun `TCP packet round trips with options payload and pseudo-header checksum`() {
        val packet = TcpPacket(
            sourcePort = 1025,
            destinationPort = 80,
            sequenceNumber = 0x12345678u,
            acknowledgmentNumber = 0x90abcdefu,
            flags = TcpPacket.ACK or TcpPacket.PSH,
            windowSize = 8192,
            urgentPointer = 0,
            options = byteArrayOf(1, 1, 1, 0),
            payload = "GET".encodeToByteArray(),
        )

        val encoded = packet.encode(source, destination)
        val parsed = requireNotNull(TcpPacket.parse(encoded, source, destination))

        assertEquals(packet.sourcePort, parsed.sourcePort)
        assertEquals(packet.destinationPort, parsed.destinationPort)
        assertEquals(packet.sequenceNumber, parsed.sequenceNumber)
        assertEquals(packet.acknowledgmentNumber, parsed.acknowledgmentNumber)
        assertEquals(packet.flags, parsed.flags)
        assertEquals(packet.windowSize, parsed.windowSize)
        assertEquals(packet.urgentPointer, parsed.urgentPointer)
        assertContentEquals(packet.options, parsed.options)
        assertContentEquals(packet.payload, parsed.payload)
    }

    @Test
    fun `TCP pseudo-header checksum covers IPv4 addresses`() {
        val encoded = TcpPacket(
            sourcePort = 1025,
            destinationPort = 80,
            sequenceNumber = 100u,
            flags = TcpPacket.SYN,
        ).encode(source, destination)

        assertNull(
            TcpPacket.parse(
                encoded,
                Ipv4Address.parse("10.0.0.3"),
                destination,
            ),
        )
        assertNull(
            TcpPacket.parse(
                encoded,
                source,
                Ipv4Address.parse("93.184.216.35"),
            ),
        )
    }

    @Test
    fun `known TCP checksum matches independently calculated value`() {
        val encoded = TcpPacket(
            sourcePort = 1025,
            destinationPort = 80,
            sequenceNumber = 0x12345678u,
            flags = TcpPacket.SYN,
            windowSize = 4096,
            options = byteArrayOf(2, 4, 5, 0xb4.toByte()),
        ).encode(source, destination)

        assertEquals(0xdb, encoded[16].toInt() and 0xff)
        assertEquals(0x4c, encoded[17].toInt() and 0xff)
    }

    @Test
    fun `maximum segment size option is parsed through padding and NOPs`() {
        val packet = TcpPacket(
            sourcePort = 1025,
            destinationPort = 80,
            sequenceNumber = 100u,
            flags = TcpPacket.SYN,
            options = byteArrayOf(
                1,
                2, 4, 5, 0xb4.toByte(),
                1,
                0,
                0,
            ),
        )

        assertEquals(1460, packet.maximumSegmentSizeOption())
    }

    @Test
    fun `missing or malformed maximum segment size option returns null`() {
        assertNull(
            TcpPacket(
                sourcePort = 1025,
                destinationPort = 80,
                sequenceNumber = 100u,
                flags = TcpPacket.SYN,
                options = byteArrayOf(1, 1, 0, 0),
            ).maximumSegmentSizeOption(),
        )
        assertNull(
            TcpPacket(
                sourcePort = 1025,
                destinationPort = 80,
                sequenceNumber = 100u,
                flags = TcpPacket.SYN,
                options = byteArrayOf(2, 5, 5, 0xb4.toByte()),
            ).maximumSegmentSizeOption(),
        )
    }

    @Test
    fun `invalid TCP checksum is rejected`() {
        val encoded = TcpPacket(
            sourcePort = 1025,
            destinationPort = 80,
            sequenceNumber = 100u,
            acknowledgmentNumber = 200u,
            flags = TcpPacket.ACK,
            payload = byteArrayOf(1, 2, 3),
        ).encode(source, destination)

        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()

        assertNull(TcpPacket.parse(encoded, source, destination))
    }

    @Test
    fun `invalid TCP data offset is rejected`() {
        val encoded = TcpPacket(
            sourcePort = 1025,
            destinationPort = 80,
            sequenceNumber = 100u,
            flags = TcpPacket.SYN,
        ).encode(source, destination)

        encoded[12] = ((4 shl 4) or (encoded[12].toInt() and 0x0f)).toByte()

        assertNull(TcpPacket.parse(encoded, source, destination))
    }

    @Test
    fun `SYN FIN and payload each consume TCP sequence space`() {
        val packet = TcpPacket(
            sourcePort = 1025,
            destinationPort = 80,
            sequenceNumber = 100u,
            flags = TcpPacket.SYN or TcpPacket.FIN,
            payload = byteArrayOf(1, 2, 3),
        )

        assertEquals(5, packet.sequenceSpaceLength)
    }
}
