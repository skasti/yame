package no.skasti.yame.ppp.tcp
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.ip.Ipv4Packet
import no.skasti.yame.ppp.tcp.TcpConnectionState
import no.skasti.yame.ppp.tcp.TcpFlowEvent
import no.skasti.yame.ppp.tcp.TcpFlowKey
import no.skasti.yame.ppp.tcp.TcpFlowResult
import no.skasti.yame.ppp.tcp.TcpFlowTable
import no.skasti.yame.ppp.tcp.TcpPacket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TcpLayerIntegrationTest {
    private val peerAddress = Ipv4Address.parse("10.64.0.2")
    private val remoteAddress = Ipv4Address.parse("203.0.113.42")
    private val key = TcpFlowKey(
        peerAddress = peerAddress,
        peerPort = 2048,
        remoteAddress = remoteAddress,
        remotePort = 80,
    )

    @Test
    fun `IPv4 and TCP layers complete handshake and exchange data with valid wire checksums`() {
        val table = table()

        val syn = receiveFromPeer(
            table,
            peerPacket(
                sequence = 1000u,
                flags = TcpPacket.SYN,
                options = byteArrayOf(2, 4, 5, 0xb4.toByte()),
            ),
        )
        assertIs<TcpFlowEvent.ConnectionRequested>(syn.flowResult.events.single())

        val (synAckIp, synAck) = parseFromRemote(syn.encodedResponses.single())
        assertEquals(remoteAddress, synAckIp.source)
        assertEquals(peerAddress, synAckIp.destination)
        assertEquals(Ipv4Packet.TCP_PROTOCOL, synAckIp.protocol)
        assertEquals(TcpPacket.SYN or TcpPacket.ACK, synAck.flags)
        assertEquals(5000u, synAck.sequenceNumber)
        assertEquals(1001u, synAck.acknowledgmentNumber)

        val established = receiveFromPeer(
            table,
            peerPacket(
                sequence = 1001u,
                acknowledgment = 5001u,
                flags = TcpPacket.ACK,
            ),
        )
        assertIs<TcpFlowEvent.Established>(established.flowResult.events.single())

        val inbound = receiveFromPeer(
            table,
            peerPacket(
                sequence = 1001u,
                acknowledgment = 5001u,
                flags = TcpPacket.ACK or TcpPacket.PSH,
                payload = "hello".encodeToByteArray(),
            ),
        )
        val payloadEvent = assertIs<TcpFlowEvent.PayloadReceived>(inbound.flowResult.events.single())
        assertContentEquals("hello".encodeToByteArray(), payloadEvent.payload)

        val (_, payloadAck) = parseFromRemote(inbound.encodedResponses.single())
        assertEquals(TcpPacket.ACK, payloadAck.flags)
        assertEquals(5001u, payloadAck.sequenceNumber)
        assertEquals(1006u, payloadAck.acknowledgmentNumber)

        val outbound = requireNotNull(table.send(key, "world".encodeToByteArray()))
        val outboundWire = encodeFromRemote(outbound)
        val (_, parsedOutbound) = parseFromRemote(outboundWire)
        assertEquals(TcpPacket.ACK or TcpPacket.PSH, parsedOutbound.flags)
        assertEquals(5001u, parsedOutbound.sequenceNumber)
        assertEquals(1006u, parsedOutbound.acknowledgmentNumber)
        assertContentEquals("world".encodeToByteArray(), parsedOutbound.payload)

        receiveFromPeer(
            table,
            peerPacket(
                sequence = 1006u,
                acknowledgment = 5006u,
                flags = TcpPacket.ACK,
            ),
        )
        assertEquals(TcpConnectionState.ESTABLISHED, table.snapshot(key)?.state)
        assertEquals(5006u, table.snapshot(key)?.localAcknowledgedSequence)
    }

    @Test
    fun `active FIN close survives IPv4 TCP encode and parse boundaries`() {
        val table = table()
        establish(table)

        val localFin = requireNotNull(table.close(key))
        val (_, parsedLocalFin) = parseFromRemote(encodeFromRemote(localFin))
        assertEquals(TcpPacket.FIN or TcpPacket.ACK, parsedLocalFin.flags)
        assertEquals(5001u, parsedLocalFin.sequenceNumber)
        assertEquals(1001u, parsedLocalFin.acknowledgmentNumber)
        assertEquals(TcpConnectionState.FIN_WAIT_1, table.snapshot(key)?.state)

        receiveFromPeer(
            table,
            peerPacket(
                sequence = 1001u,
                acknowledgment = 5002u,
                flags = TcpPacket.ACK,
            ),
        )
        assertEquals(TcpConnectionState.FIN_WAIT_2, table.snapshot(key)?.state)

        val peerFin = receiveFromPeer(
            table,
            peerPacket(
                sequence = 1001u,
                acknowledgment = 5002u,
                flags = TcpPacket.FIN or TcpPacket.ACK,
            ),
        )
        val (_, finalAck) = parseFromRemote(peerFin.encodedResponses.single())
        assertEquals(TcpPacket.ACK, finalAck.flags)
        assertEquals(5002u, finalAck.sequenceNumber)
        assertEquals(1002u, finalAck.acknowledgmentNumber)
        assertIs<TcpFlowEvent.PeerClosed>(peerFin.flowResult.events[0])
        assertIs<TcpFlowEvent.Closed>(peerFin.flowResult.events[1])
        assertNull(table.snapshot(key))
    }

    @Test
    fun `invalid TCP checksum never reaches flow table and unknown flow produces wire-valid RST`() {
        val table = table()
        val validSyn = Ipv4Packet(
            protocol = Ipv4Packet.TCP_PROTOCOL,
            source = peerAddress,
            destination = remoteAddress,
            payload = peerPacket(
                sequence = 1000u,
                flags = TcpPacket.SYN,
            ).encode(peerAddress, remoteAddress),
        ).encode()

        val parsedIp = requireNotNull(Ipv4Packet.parse(validSyn))
        val corruptedTcp = parsedIp.payload.copyOf()
        corruptedTcp[4] = (corruptedTcp[4].toInt() xor 1).toByte()

        assertNull(TcpPacket.parse(corruptedTcp, parsedIp.source, parsedIp.destination))
        assertEquals(0, table.size())

        val unknown = receiveFromPeer(
            table,
            peerPacket(
                sequence = 2000u,
                acknowledgment = 777u,
                flags = TcpPacket.ACK,
            ),
        )
        assertTrue(unknown.flowResult.events.isEmpty())

        val (rstIp, rst) = parseFromRemote(unknown.encodedResponses.single())
        assertEquals(remoteAddress, rstIp.source)
        assertEquals(peerAddress, rstIp.destination)
        assertEquals(TcpPacket.RST, rst.flags)
        assertEquals(777u, rst.sequenceNumber)
        assertEquals(0u, rst.acknowledgmentNumber)
        assertEquals(0, table.size())
    }

    private fun table(): TcpFlowTable =
        TcpFlowTable(
            initialSequenceNumber = { 5000u },
            receiveWindow = 4096,
        )

    private fun establish(table: TcpFlowTable) {
        receiveFromPeer(
            table,
            peerPacket(
                sequence = 1000u,
                flags = TcpPacket.SYN,
            ),
        )
        val established = receiveFromPeer(
            table,
            peerPacket(
                sequence = 1001u,
                acknowledgment = 5001u,
                flags = TcpPacket.ACK,
            ),
        )
        assertIs<TcpFlowEvent.Established>(established.flowResult.events.single())
        assertEquals(TcpConnectionState.ESTABLISHED, table.snapshot(key)?.state)
    }

    private fun receiveFromPeer(
        table: TcpFlowTable,
        packet: TcpPacket,
    ): WireFlowResult {
        val encodedIpv4 = Ipv4Packet(
            protocol = Ipv4Packet.TCP_PROTOCOL,
            source = peerAddress,
            destination = remoteAddress,
            payload = packet.encode(peerAddress, remoteAddress),
        ).encode()

        val parsedIpv4 = requireNotNull(Ipv4Packet.parse(encodedIpv4))
        assertEquals(Ipv4Packet.TCP_PROTOCOL, parsedIpv4.protocol)
        val parsedTcp = requireNotNull(
            TcpPacket.parse(
                parsedIpv4.payload,
                parsedIpv4.source,
                parsedIpv4.destination,
            ),
        )

        val parsedKey = TcpFlowKey(
            peerAddress = parsedIpv4.source,
            peerPort = parsedTcp.sourcePort,
            remoteAddress = parsedIpv4.destination,
            remotePort = parsedTcp.destinationPort,
        )
        val result = table.receive(parsedKey, parsedTcp)

        return WireFlowResult(
            flowResult = result,
            encodedResponses = result.responses.map(::encodeFromRemote),
        )
    }

    private fun encodeFromRemote(packet: TcpPacket): ByteArray =
        Ipv4Packet(
            protocol = Ipv4Packet.TCP_PROTOCOL,
            source = remoteAddress,
            destination = peerAddress,
            payload = packet.encode(remoteAddress, peerAddress),
        ).encode()

    private fun parseFromRemote(encodedIpv4: ByteArray): Pair<Ipv4Packet, TcpPacket> {
        val ipv4 = requireNotNull(Ipv4Packet.parse(encodedIpv4))
        val tcp = requireNotNull(
            TcpPacket.parse(
                ipv4.payload,
                ipv4.source,
                ipv4.destination,
            ),
        )
        return ipv4 to tcp
    }

    private fun peerPacket(
        sequence: UInt,
        acknowledgment: UInt = 0u,
        flags: Int,
        options: ByteArray = ByteArray(0),
        payload: ByteArray = ByteArray(0),
    ): TcpPacket =
        TcpPacket(
            sourcePort = key.peerPort,
            destinationPort = key.remotePort,
            sequenceNumber = sequence,
            acknowledgmentNumber = acknowledgment,
            flags = flags,
            windowSize = 8192,
            options = options,
            payload = payload,
        )

    private data class WireFlowResult(
        val flowResult: TcpFlowResult,
        val encodedResponses: List<ByteArray>,
    )
}
