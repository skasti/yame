package no.skasti.serialmodem.ppp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TcpFlowTableTest {
    private val peerAddress = Ipv4Address.parse("10.0.0.2")
    private val remoteAddress = Ipv4Address.parse("93.184.216.34")
    private val key = TcpFlowKey(
        peerAddress = peerAddress,
        peerPort = 1025,
        remoteAddress = remoteAddress,
        remotePort = 80,
    )

    @Test
    fun `three-way handshake creates and establishes a flow`() {
        val table = table()

        val syn = table.receive(key, peerPacket(sequence = 1000u, flags = TcpPacket.SYN))
        val synAck = syn.responses.single()

        assertEquals(TcpPacket.SYN or TcpPacket.ACK, synAck.flags)
        assertEquals(80, synAck.sourcePort)
        assertEquals(1025, synAck.destinationPort)
        assertEquals(5000u, synAck.sequenceNumber)
        assertEquals(1001u, synAck.acknowledgmentNumber)
        assertEquals(4096, synAck.windowSize)
        assertIs<TcpFlowEvent.ConnectionRequested>(syn.events.single())
        assertEquals(TcpConnectionState.SYN_RECEIVED, table.snapshot(key)?.state)

        val duplicateSyn = table.receive(key, peerPacket(sequence = 1000u, flags = TcpPacket.SYN))
        assertEquals(5000u, duplicateSyn.responses.single().sequenceNumber)
        assertEquals(1001u, duplicateSyn.responses.single().acknowledgmentNumber)
        assertTrue(duplicateSyn.events.isEmpty())

        val ack = table.receive(
            key,
            peerPacket(
                sequence = 1001u,
                acknowledgment = 5001u,
                flags = TcpPacket.ACK,
            ),
        )

        assertTrue(ack.responses.isEmpty())
        assertIs<TcpFlowEvent.Established>(ack.events.single())
        assertEquals(TcpConnectionState.ESTABLISHED, table.snapshot(key)?.state)
        assertEquals(1001u, table.snapshot(key)?.peerNextSequence)
        assertEquals(5001u, table.snapshot(key)?.localNextSequence)
        assertEquals(5001u, table.snapshot(key)?.localAcknowledgedSequence)
    }

    @Test
    fun `payload advances peer sequence and outbound data advances local sequence`() {
        val table = table()
        establish(table)

        val inbound = table.receive(
            key,
            peerPacket(
                sequence = 1001u,
                acknowledgment = 5001u,
                flags = TcpPacket.ACK or TcpPacket.PSH,
                payload = byteArrayOf(1, 2, 3),
            ),
        )

        val received = assertIs<TcpFlowEvent.PayloadReceived>(inbound.events.single())
        assertContentEquals(byteArrayOf(1, 2, 3), received.payload)
        val ack = inbound.responses.single()
        assertEquals(TcpPacket.ACK, ack.flags)
        assertEquals(5001u, ack.sequenceNumber)
        assertEquals(1004u, ack.acknowledgmentNumber)

        val outbound = requireNotNull(table.send(key, byteArrayOf(9, 8)))
        assertEquals(TcpPacket.ACK or TcpPacket.PSH, outbound.flags)
        assertEquals(5001u, outbound.sequenceNumber)
        assertEquals(1004u, outbound.acknowledgmentNumber)
        assertContentEquals(byteArrayOf(9, 8), outbound.payload)
        assertEquals(5003u, table.snapshot(key)?.localNextSequence)

        table.receive(
            key,
            peerPacket(
                sequence = 1004u,
                acknowledgment = 5003u,
                flags = TcpPacket.ACK,
            ),
        )
        assertEquals(5003u, table.snapshot(key)?.localAcknowledgedSequence)

        val outOfOrder = table.receive(
            key,
            peerPacket(
                sequence = 1010u,
                acknowledgment = 5003u,
                flags = TcpPacket.ACK or TcpPacket.PSH,
                payload = byteArrayOf(4),
            ),
        )
        assertTrue(outOfOrder.events.isEmpty())
        assertEquals(1004u, outOfOrder.responses.single().acknowledgmentNumber)
        assertEquals(1004u, table.snapshot(key)?.peerNextSequence)
    }

    @Test
    fun `outbound data is limited by latest peer receive window and cumulative ACKs`() {
        val table = table()

        table.receive(
            key,
            peerPacket(
                sequence = 1000u,
                flags = TcpPacket.SYN,
                windowSize = 4,
            ),
        )
        table.receive(
            key,
            peerPacket(
                sequence = 1001u,
                acknowledgment = 5001u,
                flags = TcpPacket.ACK,
                windowSize = 4,
            ),
        )

        assertEquals(4, table.snapshot(key)?.peerWindowSize)
        assertEquals(4, table.snapshot(key)?.availableSendWindow)

        val first = requireNotNull(table.send(key, byteArrayOf(1, 2, 3, 4)))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), first.payload)
        assertEquals(0, table.snapshot(key)?.availableSendWindow)
        assertNull(table.send(key, byteArrayOf(5)))

        table.receive(
            key,
            peerPacket(
                sequence = 1001u,
                acknowledgment = 5003u,
                flags = TcpPacket.ACK,
                windowSize = 4,
            ),
        )

        assertEquals(5003u, table.snapshot(key)?.localAcknowledgedSequence)
        assertEquals(2, table.snapshot(key)?.availableSendWindow)
        assertContentEquals(
            byteArrayOf(5, 6),
            requireNotNull(table.send(key, byteArrayOf(5, 6))).payload,
        )
        assertEquals(0, table.snapshot(key)?.availableSendWindow)

        table.receive(
            key,
            peerPacket(
                sequence = 1001u,
                acknowledgment = 5005u,
                flags = TcpPacket.ACK,
                windowSize = 0,
            ),
        )

        assertEquals(0, table.snapshot(key)?.peerWindowSize)
        assertEquals(0, table.snapshot(key)?.availableSendWindow)
        assertNull(table.send(key, byteArrayOf(7)))

        table.receive(
            key,
            peerPacket(
                sequence = 1001u,
                acknowledgment = 5007u,
                flags = TcpPacket.ACK,
                windowSize = 3,
            ),
        )

        assertEquals(3, table.snapshot(key)?.availableSendWindow)
        assertContentEquals(
            byteArrayOf(7, 8, 9),
            requireNotNull(table.send(key, byteArrayOf(7, 8, 9))).payload,
        )
    }

    @Test
    fun `peer FIN enters close-wait and local FIN completes last-ack`() {
        val table = table()
        establish(table)

        val peerFin = table.receive(
            key,
            peerPacket(
                sequence = 1001u,
                acknowledgment = 5001u,
                flags = TcpPacket.FIN or TcpPacket.ACK,
            ),
        )

        assertIs<TcpFlowEvent.PeerClosed>(peerFin.events.single())
        assertEquals(1002u, peerFin.responses.single().acknowledgmentNumber)
        assertEquals(TcpConnectionState.CLOSE_WAIT, table.snapshot(key)?.state)

        val localFin = requireNotNull(table.close(key))
        assertEquals(TcpPacket.FIN or TcpPacket.ACK, localFin.flags)
        assertEquals(5001u, localFin.sequenceNumber)
        assertEquals(1002u, localFin.acknowledgmentNumber)
        assertEquals(TcpConnectionState.LAST_ACK, table.snapshot(key)?.state)

        val finalAck = table.receive(
            key,
            peerPacket(
                sequence = 1002u,
                acknowledgment = 5002u,
                flags = TcpPacket.ACK,
            ),
        )

        assertIs<TcpFlowEvent.Closed>(finalAck.events.single())
        assertNull(table.snapshot(key))
        assertEquals(0, table.size())
    }

    @Test
    fun `active FIN passes through fin-wait states until peer closes`() {
        val table = table()
        establish(table)

        val localFin = requireNotNull(table.close(key))
        assertEquals(5001u, localFin.sequenceNumber)
        assertEquals(TcpConnectionState.FIN_WAIT_1, table.snapshot(key)?.state)

        table.receive(
            key,
            peerPacket(
                sequence = 1001u,
                acknowledgment = 5002u,
                flags = TcpPacket.ACK,
            ),
        )
        assertEquals(TcpConnectionState.FIN_WAIT_2, table.snapshot(key)?.state)

        val peerFin = table.receive(
            key,
            peerPacket(
                sequence = 1001u,
                acknowledgment = 5002u,
                flags = TcpPacket.FIN or TcpPacket.ACK,
            ),
        )

        assertEquals(1002u, peerFin.responses.single().acknowledgmentNumber)
        assertIs<TcpFlowEvent.PeerClosed>(peerFin.events[0])
        assertIs<TcpFlowEvent.Closed>(peerFin.events[1])
        assertNull(table.snapshot(key))
    }

    @Test
    fun `RST tears down a known flow and unknown segments get RFC-style reset`() {
        val table = table()
        establish(table)

        val reset = table.receive(
            key,
            peerPacket(
                sequence = 1001u,
                acknowledgment = 5001u,
                flags = TcpPacket.RST or TcpPacket.ACK,
            ),
        )

        assertTrue(reset.responses.isEmpty())
        assertIs<TcpFlowEvent.Reset>(reset.events.single())
        assertNull(table.snapshot(key))

        val unknownAck = table.receive(
            key,
            peerPacket(
                sequence = 2000u,
                acknowledgment = 777u,
                flags = TcpPacket.ACK,
            ),
        ).responses.single()
        assertEquals(TcpPacket.RST, unknownAck.flags)
        assertEquals(777u, unknownAck.sequenceNumber)
        assertEquals(0u, unknownAck.acknowledgmentNumber)

        val unknownFin = table.receive(
            key,
            peerPacket(
                sequence = 3000u,
                flags = TcpPacket.FIN,
            ),
        ).responses.single()
        assertEquals(TcpPacket.RST or TcpPacket.ACK, unknownFin.flags)
        assertEquals(3001u, unknownFin.acknowledgmentNumber)
    }

    @Test
    fun `sequence numbers wrap at 32 bits during handshake`() {
        val table = table()

        val synAck = table.receive(
            key,
            peerPacket(
                sequence = UInt.MAX_VALUE,
                flags = TcpPacket.SYN,
            ),
        ).responses.single()

        assertEquals(0u, synAck.acknowledgmentNumber)

        table.receive(
            key,
            peerPacket(
                sequence = 0u,
                acknowledgment = 5001u,
                flags = TcpPacket.ACK,
            ),
        )

        assertEquals(TcpConnectionState.ESTABLISHED, table.snapshot(key)?.state)
        assertEquals(0u, table.snapshot(key)?.peerNextSequence)
    }

    private fun table(): TcpFlowTable =
        TcpFlowTable(
            initialSequenceNumber = { 5000u },
            receiveWindow = 4096,
        )

    private fun establish(table: TcpFlowTable) {
        table.receive(key, peerPacket(sequence = 1000u, flags = TcpPacket.SYN))
        table.receive(
            key,
            peerPacket(
                sequence = 1001u,
                acknowledgment = 5001u,
                flags = TcpPacket.ACK,
            ),
        )
        assertEquals(TcpConnectionState.ESTABLISHED, table.snapshot(key)?.state)
    }

    private fun peerPacket(
        sequence: UInt,
        acknowledgment: UInt = 0u,
        flags: Int,
        windowSize: Int = 8192,
        payload: ByteArray = ByteArray(0),
    ): TcpPacket =
        TcpPacket(
            sourcePort = key.peerPort,
            destinationPort = key.remotePort,
            sequenceNumber = sequence,
            acknowledgmentNumber = acknowledgment,
            flags = flags,
            windowSize = windowSize,
            payload = payload,
        )
}
