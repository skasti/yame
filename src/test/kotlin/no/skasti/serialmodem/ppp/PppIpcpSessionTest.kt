package no.skasti.serialmodem.ppp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PppIpcpSessionTest {
    @Test
    fun `LCP open starts IPCP with YAME local address`() {
        val sent = mutableListOf<PppFrame>()
        val session = newSession(sent)

        openLcp(session, sent)

        val request = sent
            .last { it.protocol == PppSession.IPCP_PROTOCOL }
            .let { requireNotNull(PppControlPacket.parse(it.payload)) }
        assertEquals(PppControlPacket.CONFIGURE_REQUEST, request.code)

        val option = requireNotNull(PppControlOption.parseAll(request.data)).single()
        assertEquals(PppControlOption.IPCP_IP_ADDRESS, option.type)
        assertEquals(Ipv4Address.parse("10.0.0.1"), Ipv4Address.fromBytes(option.data))
        assertFalse(session.ipcpOpen)
        session.close()
    }

    @Test
    fun `zero peer address is nacked with assigned address and then acked`() {
        val sent = mutableListOf<PppFrame>()
        val session = newSession(sent)
        openLcp(session, sent)

        session.receive(ipcpConfigureRequest(7, Ipv4Address.ZERO))

        val nak = sent.last().let { requireNotNull(PppControlPacket.parse(it.payload)) }
        assertEquals(PppControlPacket.CONFIGURE_NAK, nak.code)
        assertEquals(7, nak.identifier)
        val suggested = requireNotNull(PppControlOption.parseAll(nak.data)).single()
        assertEquals(Ipv4Address.parse("10.0.0.2"), Ipv4Address.fromBytes(suggested.data))

        val acceptedRequest = ipcpConfigureRequest(8, Ipv4Address.parse("10.0.0.2"))
        session.receive(acceptedRequest)

        val ack = sent.last().let { requireNotNull(PppControlPacket.parse(it.payload)) }
        val requestPacket = requireNotNull(PppControlPacket.parse(acceptedRequest.payload))
        assertEquals(PppControlPacket.CONFIGURE_ACK, ack.code)
        assertEquals(8, ack.identifier)
        assertContentEquals(requestPacket.data, ack.data)
        assertEquals(Ipv4Address.parse("10.0.0.2"), session.peerIpAddress)
        session.close()
    }

    @Test
    fun `IPCP opens after both directions are configured`() {
        val sent = mutableListOf<PppFrame>()
        val session = newSession(sent)
        openLcp(session, sent)

        val localRequest = sent
            .last { it.protocol == PppSession.IPCP_PROTOCOL }
            .let { requireNotNull(PppControlPacket.parse(it.payload)) }

        session.receive(ipcpConfigureRequest(9, Ipv4Address.parse("10.0.0.2")))
        session.receive(
            PppFrame(
                protocol = PppSession.IPCP_PROTOCOL,
                payload = PppControlPacket(
                    code = PppControlPacket.CONFIGURE_ACK,
                    identifier = localRequest.identifier,
                    data = localRequest.data,
                ).encode(),
            ),
        )

        assertTrue(session.ipcpOpen)
        assertEquals(Ipv4Address.parse("10.0.0.1"), session.localIpAddress)
        assertEquals(Ipv4Address.parse("10.0.0.2"), session.peerIpAddress)
        session.close()
    }

    @Test
    fun `unsupported IPCP options are rejected`() {
        val sent = mutableListOf<PppFrame>()
        val session = newSession(sent)
        openLcp(session, sent)

        val compression = PppControlOption(
            type = PppControlOption.IPCP_IP_COMPRESSION_PROTOCOL,
            data = byteArrayOf(0x00, 0x2d),
        )
        session.receive(
            PppFrame(
                protocol = PppSession.IPCP_PROTOCOL,
                payload = PppControlPacket(
                    code = PppControlPacket.CONFIGURE_REQUEST,
                    identifier = 10,
                    data = compression.encode(),
                ).encode(),
            ),
        )

        val reject = sent.last().let { requireNotNull(PppControlPacket.parse(it.payload)) }
        assertEquals(PppControlPacket.CONFIGURE_REJECT, reject.code)
        assertContentEquals(compression.encode(), reject.data)
        assertFalse(session.ipcpOpen)
        session.close()
    }

    private fun newSession(sent: MutableList<PppFrame>): PppSession {
        val addresses = PppAddresses(
            localAddress = Ipv4Address.parse("10.0.0.1"),
            peerAddress = Ipv4Address.parse("10.0.0.2"),
            allocationSubnet = Ipv4Cidr.parse("10.0.0.0/30"),
        )
        return PppSession(
            sendFrame = sent::add,
            logger = {},
            ipAddresses = addresses,
            selectPeerAddress = { requested ->
                if (requested == Ipv4Address.ZERO) addresses.peerAddress else requested
            },
        )
    }

    private fun openLcp(session: PppSession, sent: MutableList<PppFrame>) {
        session.start()
        session.receive(
            PppFrame(
                protocol = PppSession.LCP_PROTOCOL,
                payload = LcpPacket(
                    code = LcpPacket.CONFIGURE_REQUEST,
                    identifier = 0x0b,
                    data = byteArrayOf(0x02, 0x06, 0x00, 0x00, 0x00, 0x00),
                ).encode(),
            ),
        )

        val localRequest = sent
            .first { it.protocol == PppSession.LCP_PROTOCOL }
            .let { requireNotNull(LcpPacket.parse(it.payload)) }
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

        assertTrue(session.lcpOpen)
    }

    private fun ipcpConfigureRequest(identifier: Int, address: Ipv4Address): PppFrame =
        PppFrame(
            protocol = PppSession.IPCP_PROTOCOL,
            payload = PppControlPacket(
                code = PppControlPacket.CONFIGURE_REQUEST,
                identifier = identifier,
                data = PppControlOption(
                    type = PppControlOption.IPCP_IP_ADDRESS,
                    data = address.toByteArray(),
                ).encode(),
            ).encode(),
        )
}
