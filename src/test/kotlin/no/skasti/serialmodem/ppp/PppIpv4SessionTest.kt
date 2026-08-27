package no.skasti.serialmodem.ppp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PppIpv4SessionTest {
    @Test
    fun `local ICMP echo request receives IPv4 echo reply over PPP`() {
        val sent = mutableListOf<PppFrame>()
        val session = newSession(sent)
        openIpcp(session, sent)
        sent.clear()

        val echoBody = byteArrayOf(
            0xcc.toByte(), 0xe2.toByte(),
            0x00, 0x03,
            0x55, 0x55, 0x55, 0x55,
        )
        val request = Ipv4Packet(
            identification = 4,
            ttl = 60,
            protocol = Ipv4Packet.ICMP_PROTOCOL,
            source = Ipv4Address.parse("10.0.0.2"),
            destination = Ipv4Address.parse("10.0.0.1"),
            payload = IcmpPacket(
                type = IcmpPacket.ECHO_REQUEST,
                code = 0,
                body = echoBody,
            ).encode(),
        )

        session.receive(
            PppFrame(
                protocol = PppSession.IPV4_PROTOCOL,
                payload = request.encode(),
            ),
        )

        val frame = sent.single()
        assertEquals(PppSession.IPV4_PROTOCOL, frame.protocol)

        val reply = requireNotNull(Ipv4Packet.parse(frame.payload))
        assertEquals(Ipv4Address.parse("10.0.0.1"), reply.source)
        assertEquals(Ipv4Address.parse("10.0.0.2"), reply.destination)
        assertEquals(Ipv4Packet.DEFAULT_TTL, reply.ttl)
        assertEquals(4, reply.identification)
        assertEquals(Ipv4Packet.ICMP_PROTOCOL, reply.protocol)

        val icmp = requireNotNull(IcmpPacket.parse(reply.payload))
        assertEquals(IcmpPacket.ECHO_REPLY, icmp.type)
        assertEquals(0, icmp.code)
        assertEquals(0xcce2, icmp.echoIdentifier())
        assertEquals(3, icmp.echoSequence())
        assertContentEquals(echoBody, icmp.body)

        session.close()
    }

    @Test
    fun `local ICMP echo reply is not sent when it exceeds peer MRU`() {
        val sent = mutableListOf<PppFrame>()
        val logs = mutableListOf<String>()
        val session = newSession(sent, logs::add)
        openIpcp(session, sent, peerMru = 64)
        sent.clear()

        val request = Ipv4Packet(
            protocol = Ipv4Packet.ICMP_PROTOCOL,
            source = Ipv4Address.parse("10.0.0.2"),
            destination = Ipv4Address.parse("10.0.0.1"),
            payload = IcmpPacket(
                type = IcmpPacket.ECHO_REQUEST,
                code = 0,
                body = ByteArray(100) { 0x55 },
            ).encode(),
        )

        session.receive(PppFrame(PppSession.IPV4_PROTOCOL, request.encode()))

        assertTrue(sent.isEmpty())
        assertTrue(logs.any { it.contains("exceeds peer MRU 64") })
        session.close()
    }

    @Test
    fun `IPv4 packet for another destination is left for future forwarding`() {
        val sent = mutableListOf<PppFrame>()
        val session = newSession(sent)
        openIpcp(session, sent)
        sent.clear()

        val request = Ipv4Packet(
            protocol = Ipv4Packet.ICMP_PROTOCOL,
            source = Ipv4Address.parse("10.0.0.2"),
            destination = Ipv4Address.parse("8.8.8.8"),
            payload = IcmpPacket(
                type = IcmpPacket.ECHO_REQUEST,
                code = 0,
                body = byteArrayOf(0, 1, 0, 1),
            ).encode(),
        )

        session.receive(PppFrame(PppSession.IPV4_PROTOCOL, request.encode()))

        assertTrue(sent.isEmpty())
        session.close()
    }

    private fun newSession(
        sent: MutableList<PppFrame>,
        logger: (String) -> Unit = {},
    ): PppSession {
        val addresses = PppAddresses(
            localAddress = Ipv4Address.parse("10.0.0.1"),
            peerAddress = Ipv4Address.parse("10.0.0.2"),
            allocationSubnet = Ipv4Cidr.parse("10.0.0.0/30"),
        )
        return PppSession(
            sendFrame = sent::add,
            logger = logger,
            ipAddresses = addresses,
            selectPeerAddress = { requested ->
                if (requested == Ipv4Address.ZERO) addresses.peerAddress else requested
            },
        )
    }

    private fun openIpcp(
        session: PppSession,
        sent: MutableList<PppFrame>,
        peerMru: Int? = null,
    ) {
        session.start()

        val lcpOptions = buildList {
            if (peerMru != null) {
                add(
                    LcpOption(
                        type = LcpOption.MRU,
                        data = byteArrayOf(
                            (peerMru ushr 8).toByte(),
                            peerMru.toByte(),
                        ),
                    ),
                )
            }
            add(
                LcpOption(
                    type = LcpOption.ACCM,
                    data = byteArrayOf(0x00, 0x00, 0x00, 0x00),
                ),
            )
        }.fold(ByteArray(0)) { bytes, option -> bytes + option.encode() }

        session.receive(
            PppFrame(
                protocol = PppSession.LCP_PROTOCOL,
                payload = LcpPacket(
                    code = LcpPacket.CONFIGURE_REQUEST,
                    identifier = 0x1c,
                    data = lcpOptions,
                ).encode(),
            ),
        )

        val localLcpRequest = sent
            .first { it.protocol == PppSession.LCP_PROTOCOL }
            .let { requireNotNull(LcpPacket.parse(it.payload)) }
        session.receive(
            PppFrame(
                protocol = PppSession.LCP_PROTOCOL,
                payload = LcpPacket(
                    code = LcpPacket.CONFIGURE_ACK,
                    identifier = localLcpRequest.identifier,
                    data = localLcpRequest.data,
                ).encode(),
            ),
        )

        val localIpcpRequest = sent
            .last { it.protocol == PppSession.IPCP_PROTOCOL }
            .let { requireNotNull(PppControlPacket.parse(it.payload)) }
        session.receive(
            PppFrame(
                protocol = PppSession.IPCP_PROTOCOL,
                payload = PppControlPacket(
                    code = PppControlPacket.CONFIGURE_REQUEST,
                    identifier = 9,
                    data = PppControlOption(
                        type = PppControlOption.IPCP_IP_ADDRESS,
                        data = Ipv4Address.parse("10.0.0.2").toByteArray(),
                    ).encode(),
                ).encode(),
            ),
        )
        session.receive(
            PppFrame(
                protocol = PppSession.IPCP_PROTOCOL,
                payload = PppControlPacket(
                    code = PppControlPacket.CONFIGURE_ACK,
                    identifier = localIpcpRequest.identifier,
                    data = localIpcpRequest.data,
                ).encode(),
            ),
        )

        assertTrue(session.ipcpOpen)
    }
}
