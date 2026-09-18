package no.skasti.yame.ppp.ipcp

import no.skasti.yame.ppp.lcp.LcpPacket

import no.skasti.yame.ppp.PppAddresses
import no.skasti.yame.ppp.PppControlOption
import no.skasti.yame.ppp.PppControlPacket
import no.skasti.yame.ppp.PppFrame
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.ip.Ipv4Cidr
import no.skasti.yame.ppp.session.PppSession
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
        assertEquals(IpcpOptionType.IP_ADDRESS, option.type)
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
    fun `peer reconfiguration after IPCP open restarts local negotiation`() {
        val sent = mutableListOf<PppFrame>()
        val session = newSession(sent)
        openLcp(session, sent)

        val firstLocalRequest = sent
            .last { it.protocol == PppSession.IPCP_PROTOCOL }
            .let { requireNotNull(PppControlPacket.parse(it.payload)) }

        session.receive(ipcpConfigureRequest(9, Ipv4Address.parse("10.0.0.2")))
        session.receive(
            PppFrame(
                protocol = PppSession.IPCP_PROTOCOL,
                payload = PppControlPacket(
                    code = PppControlPacket.CONFIGURE_ACK,
                    identifier = firstLocalRequest.identifier,
                    data = firstLocalRequest.data,
                ).encode(),
            ),
        )
        assertTrue(session.ipcpOpen)

        val sentBeforeReconfigure = sent.size
        session.receive(ipcpConfigureRequest(10, Ipv4Address.parse("10.0.0.2")))

        assertFalse(session.ipcpOpen)
        val reconfigurePackets = sent.drop(sentBeforeReconfigure)
            .filter { it.protocol == PppSession.IPCP_PROTOCOL }
            .map { requireNotNull(PppControlPacket.parse(it.payload)) }
        val secondLocalRequest = reconfigurePackets
            .single { it.code == PppControlPacket.CONFIGURE_REQUEST }
        assertTrue(secondLocalRequest.identifier != firstLocalRequest.identifier)
        assertTrue(reconfigurePackets.any {
            it.code == PppControlPacket.CONFIGURE_ACK && it.identifier == 10
        })

        session.receive(
            PppFrame(
                protocol = PppSession.IPCP_PROTOCOL,
                payload = PppControlPacket(
                    code = PppControlPacket.CONFIGURE_ACK,
                    identifier = firstLocalRequest.identifier,
                    data = firstLocalRequest.data,
                ).encode(),
            ),
        )
        assertFalse(session.ipcpOpen)

        session.receive(
            PppFrame(
                protocol = PppSession.IPCP_PROTOCOL,
                payload = PppControlPacket(
                    code = PppControlPacket.CONFIGURE_ACK,
                    identifier = secondLocalRequest.identifier,
                    data = secondLocalRequest.data,
                ).encode(),
            ),
        )

        assertTrue(session.ipcpOpen)
        session.close()
    }

    @Test
    fun `zero primary DNS is nacked with YAME local address`() {
        val sent = mutableListOf<PppFrame>()
        val session = newSession(sent)
        openLcp(session, sent)

        session.receive(
            ipcpConfigureRequest(
                identifier = 10,
                address = Ipv4Address.parse("10.0.0.2"),
                dnsAddress = Ipv4Address.ZERO,
            ),
        )

        val nak = sent.last().let { requireNotNull(PppControlPacket.parse(it.payload)) }
        assertEquals(PppControlPacket.CONFIGURE_NAK, nak.code)
        val dns = requireNotNull(PppControlOption.parseAll(nak.data)).single()
        assertEquals(IpcpOptionType.PRIMARY_DNS, dns.type)
        assertEquals(Ipv4Address.parse("10.0.0.1"), Ipv4Address.fromBytes(dns.data))
        session.close()
    }

    @Test
    fun `primary and secondary DNS are independently nacked to YAME address`() {
        val sent = mutableListOf<PppFrame>()
        val session = newSession(sent)
        openLcp(session, sent)

        val data =
            PppControlOption(
                type = IpcpOptionType.IP_ADDRESS,
                data = Ipv4Address.parse("10.0.0.2").toByteArray(),
            ).encode() +
                PppControlOption(
                    type = IpcpOptionType.PRIMARY_DNS,
                    data = Ipv4Address.ZERO.toByteArray(),
                ).encode() +
                PppControlOption(
                    type = IpcpOptionType.SECONDARY_DNS,
                    data = Ipv4Address.parse("8.8.8.8").toByteArray(),
                ).encode()
        session.receive(
            PppFrame(
                protocol = PppSession.IPCP_PROTOCOL,
                payload = PppControlPacket(
                    code = PppControlPacket.CONFIGURE_REQUEST,
                    identifier = 11,
                    data = data,
                ).encode(),
            ),
        )

        val nak = sent.last().let { requireNotNull(PppControlPacket.parse(it.payload)) }
        val options = requireNotNull(PppControlOption.parseAll(nak.data))
        assertEquals(
            listOf(IpcpOptionType.PRIMARY_DNS, IpcpOptionType.SECONDARY_DNS),
            options.map { it.type },
        )
        assertTrue(options.all { Ipv4Address.fromBytes(it.data) == Ipv4Address.parse("10.0.0.1") })
        session.close()
    }

    @Test
    fun `missing primary DNS is suggested once without blocking an old client`() {
        val sent = mutableListOf<PppFrame>()
        val session = newSession(sent)
        openLcp(session, sent)

        session.receive(
            ipcpConfigureRequest(
                identifier = 12,
                address = Ipv4Address.parse("10.0.0.2"),
                dnsAddress = null,
            ),
        )
        val nak = sent.last().let { requireNotNull(PppControlPacket.parse(it.payload)) }
        assertEquals(PppControlPacket.CONFIGURE_NAK, nak.code)
        val suggested = requireNotNull(PppControlOption.parseAll(nak.data)).single()
        assertEquals(IpcpOptionType.PRIMARY_DNS, suggested.type)
        assertEquals(Ipv4Address.parse("10.0.0.1"), Ipv4Address.fromBytes(suggested.data))

        session.receive(
            ipcpConfigureRequest(
                identifier = 13,
                address = Ipv4Address.parse("10.0.0.2"),
                dnsAddress = null,
            ),
        )
        val ack = sent.last().let { requireNotNull(PppControlPacket.parse(it.payload)) }
        assertEquals(PppControlPacket.CONFIGURE_ACK, ack.code)
        assertEquals(13, ack.identifier)
        session.close()
    }

    @Test
    fun `unsupported IPCP options are rejected`() {
        val sent = mutableListOf<PppFrame>()
        val session = newSession(sent)
        openLcp(session, sent)

        val compression = PppControlOption(
            type = IpcpOptionType.IP_COMPRESSION_PROTOCOL,
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

    private fun ipcpConfigureRequest(
        identifier: Int,
        address: Ipv4Address,
        dnsAddress: Ipv4Address? = Ipv4Address.parse("10.0.0.1"),
    ): PppFrame {
        val data =
            PppControlOption(
                type = IpcpOptionType.IP_ADDRESS,
                data = address.toByteArray(),
            ).encode() +
                (dnsAddress?.let {
                    PppControlOption(
                        type = IpcpOptionType.PRIMARY_DNS,
                        data = it.toByteArray(),
                    ).encode()
                } ?: ByteArray(0))

        return PppFrame(
            protocol = PppSession.IPCP_PROTOCOL,
            payload = PppControlPacket(
                code = PppControlPacket.CONFIGURE_REQUEST,
                identifier = identifier,
                data = data,
            ).encode(),
        )
    }
}
