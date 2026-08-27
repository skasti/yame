package no.skasti.serialmodem.ppp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PppSessionTest {
    @Test
    fun `starts by sending LCP configure request`() {
        val sent = mutableListOf<PppFrame>()
        val session = PppSession(sendFrame = sent::add, logger = {})

        session.start()

        assertEquals(1, sent.size)
        assertEquals(PppSession.LCP_PROTOCOL, sent.single().protocol)

        val packet = requireNotNull(LcpPacket.parse(sent.single().payload))
        assertEquals(LcpPacket.CONFIGURE_REQUEST, packet.code)

        val options = requireNotNull(LcpOption.parseAll(packet.data))
        assertEquals(1, options.size)
        assertEquals(LcpOption.ACCM, options.single().type)
        assertContentEquals(byteArrayOf(0, 0, 0, 0), options.single().data)
    }

    @Test
    fun `acks trumpet configure request and applies peer transmit options`() {
        val sent = mutableListOf<PppFrame>()
        val session = PppSession(sendFrame = sent::add, logger = {})
        session.start()

        val request = trumpetConfigureRequest(identifier = 0x0b)
        session.receive(
            PppFrame(
                protocol = PppSession.LCP_PROTOCOL,
                payload = request.encode(),
            ),
        )

        assertEquals(2, sent.size)
        val ack = requireNotNull(LcpPacket.parse(sent.last().payload))
        assertEquals(LcpPacket.CONFIGURE_ACK, ack.code)
        assertEquals(request.identifier, ack.identifier)
        assertContentEquals(request.data, ack.data)

        assertEquals(576, session.transmitMru)
        assertEquals(0u, session.transmitAccm)
        assertTrue(session.transmitProtocolFieldCompression)
        assertTrue(session.transmitAddressControlFieldCompression)
        assertFalse(session.lcpOpen)
    }

    @Test
    fun `LCP opens after both directions are configured`() {
        val sent = mutableListOf<PppFrame>()
        val session = PppSession(sendFrame = sent::add, logger = {})
        session.start()

        session.receive(
            PppFrame(
                protocol = PppSession.LCP_PROTOCOL,
                payload = trumpetConfigureRequest(identifier = 0x0b).encode(),
            ),
        )

        val localRequest = requireNotNull(LcpPacket.parse(sent.first().payload))
        session.receive(
            PppFrame(
                protocol = PppSession.LCP_PROTOCOL,
                payload = LcpPacket(
                    code = LcpPacket.CONFIGURE_ACK,
                    identifier = localRequest.identifier,
                    data = localRequest.data,
                ).encode(),
            ),
        )

        assertEquals(0u, session.receiveAccm)
        assertTrue(session.lcpOpen)
    }

    @Test
    fun `rejects unsupported peer options without opening peer side`() {
        val sent = mutableListOf<PppFrame>()
        val session = PppSession(sendFrame = sent::add, logger = {})
        session.start()

        val unsupported = LcpOption(
            type = LcpOption.AUTHENTICATION_PROTOCOL,
            data = byteArrayOf(0xc0.toByte(), 0x23),
        )
        session.receive(
            PppFrame(
                protocol = PppSession.LCP_PROTOCOL,
                payload = LcpPacket(
                    code = LcpPacket.CONFIGURE_REQUEST,
                    identifier = 7,
                    data = unsupported.encode(),
                ).encode(),
            ),
        )

        val reject = requireNotNull(LcpPacket.parse(sent.last().payload))
        assertEquals(LcpPacket.CONFIGURE_REJECT, reject.code)
        assertEquals(7, reject.identifier)
        assertContentEquals(unsupported.encode(), reject.data)
        assertFalse(session.lcpOpen)
    }

    private fun trumpetConfigureRequest(identifier: Int): LcpPacket =
        LcpPacket(
            code = LcpPacket.CONFIGURE_REQUEST,
            identifier = identifier,
            data = byteArrayOf(
                0x01, 0x04, 0x02, 0x40,
                0x02, 0x06, 0x00, 0x00, 0x00, 0x00,
                0x05, 0x06, 0x00, 0x02, 0x76, 0xd1.toByte(),
                0x07, 0x02,
                0x08, 0x02,
            ),
        )
}
