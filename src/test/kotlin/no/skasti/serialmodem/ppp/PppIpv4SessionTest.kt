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
    fun `external ICMP echo request is proxied through the host`() {
        val sent = mutableListOf<PppFrame>()
        val proxy = FakeIcmpEchoProxy(reachable = true)
        val session = newSession(sent, icmpEchoProxy = proxy)
        openIpcp(session, sent)
        sent.clear()

        val echoBody = byteArrayOf(
            0x12, 0x34,
            0x00, 0x07,
            0x55, 0x55, 0x55, 0x55,
        )
        val request = Ipv4Packet(
            identification = 17,
            ttl = 60,
            protocol = Ipv4Packet.ICMP_PROTOCOL,
            source = Ipv4Address.parse("10.0.0.2"),
            destination = Ipv4Address.parse("8.8.8.8"),
            payload = IcmpPacket(
                type = IcmpPacket.ECHO_REQUEST,
                code = 0,
                body = echoBody,
            ).encode(),
        )

        session.receive(PppFrame(PppSession.IPV4_PROTOCOL, request.encode()))

        assertEquals(listOf(Ipv4Address.parse("8.8.8.8")), proxy.destinations)
        val frame = sent.single()
        assertEquals(PppSession.IPV4_PROTOCOL, frame.protocol)

        val reply = requireNotNull(Ipv4Packet.parse(frame.payload))
        assertEquals(Ipv4Address.parse("8.8.8.8"), reply.source)
        assertEquals(Ipv4Address.parse("10.0.0.2"), reply.destination)
        assertEquals(17, reply.identification)

        val icmp = requireNotNull(IcmpPacket.parse(reply.payload))
        assertEquals(IcmpPacket.ECHO_REPLY, icmp.type)
        assertEquals(0x1234, icmp.echoIdentifier())
        assertEquals(7, icmp.echoSequence())
        assertContentEquals(echoBody, icmp.body)

        session.close()
    }

    @Test
    fun `external ICMP echo timeout produces no PPP reply`() {
        val sent = mutableListOf<PppFrame>()
        val logs = mutableListOf<String>()
        val proxy = FakeIcmpEchoProxy(reachable = false)
        val session = newSession(sent, logs::add, proxy)
        openIpcp(session, sent)
        sent.clear()

        val request = Ipv4Packet(
            protocol = Ipv4Packet.ICMP_PROTOCOL,
            source = Ipv4Address.parse("10.0.0.2"),
            destination = Ipv4Address.parse("203.0.113.1"),
            payload = IcmpPacket(
                type = IcmpPacket.ECHO_REQUEST,
                code = 0,
                body = byteArrayOf(0, 1, 0, 1),
            ).encode(),
        )

        session.receive(PppFrame(PppSession.IPV4_PROTOCOL, request.encode()))

        assertTrue(sent.isEmpty())
        assertEquals(listOf(Ipv4Address.parse("203.0.113.1")), proxy.destinations)
        assertTrue(logs.any { it.contains("timed out") })

        session.close()
    }

    @Test
    fun `external ICMP reply is dropped after IPCP renegotiates`() {
        val sent = mutableListOf<PppFrame>()
        val proxy = DeferredIcmpEchoProxy()
        val session = newSession(sent, icmpEchoProxy = proxy)
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

        session.receive(
            PppFrame(
                protocol = PppSession.IPCP_PROTOCOL,
                payload = PppControlPacket(
                    code = PppControlPacket.CONFIGURE_REQUEST,
                    identifier = 10,
                    data = PppControlOption(
                        type = PppControlOption.IPCP_IP_ADDRESS,
                        data = Ipv4Address.parse("10.0.0.2").toByteArray(),
                    ).encode(),
                ).encode(),
            ),
        )

        val renegotiationRequest = sent
            .asSequence()
            .filter { it.protocol == PppSession.IPCP_PROTOCOL }
            .mapNotNull { PppControlPacket.parse(it.payload) }
            .first { it.code == PppControlPacket.CONFIGURE_REQUEST }
        session.receive(
            PppFrame(
                protocol = PppSession.IPCP_PROTOCOL,
                payload = PppControlPacket(
                    code = PppControlPacket.CONFIGURE_ACK,
                    identifier = renegotiationRequest.identifier,
                    data = renegotiationRequest.data,
                ).encode(),
            ),
        )
        assertTrue(session.ipcpOpen)

        sent.clear()
        proxy.complete(reachable = true)

        assertTrue(sent.isEmpty())
        session.close()
    }

    @Test
    fun `external ICMP reply is dropped after session closes`() {
        val sent = mutableListOf<PppFrame>()
        val proxy = DeferredIcmpEchoProxy()
        val session = newSession(sent, icmpEchoProxy = proxy)
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
        session.close()
        proxy.complete(reachable = true)

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `external UDP datagram is proxied and reply is returned over PPP`() {
        val sent = mutableListOf<PppFrame>()
        val udpProxy = FakeUdpProxy(replyPayload = byteArrayOf(0x42, 0x43))
        val session = newSession(sent, udpProxy = udpProxy)
        openIpcp(session, sent)
        sent.clear()

        val source = Ipv4Address.parse("10.0.0.2")
        val destination = Ipv4Address.parse("8.8.8.8")
        val udp = UdpPacket(
            sourcePort = 1037,
            destinationPort = 53,
            payload = byteArrayOf(0x12, 0x34, 0x01, 0x00),
        )
        val request = Ipv4Packet(
            protocol = Ipv4Packet.UDP_PROTOCOL,
            source = source,
            destination = destination,
            payload = udp.encode(source, destination),
        )

        session.receive(PppFrame(PppSession.IPV4_PROTOCOL, request.encode()))

        val sentFlow = udpProxy.sent.single()
        assertEquals(1037, sentFlow.flow.peerPort)
        assertEquals(destination, sentFlow.flow.destination)
        assertEquals(53, sentFlow.flow.destinationPort)
        assertContentEquals(byteArrayOf(0x12, 0x34, 0x01, 0x00), sentFlow.payload)

        val frame = sent.single()
        val reply = requireNotNull(Ipv4Packet.parse(frame.payload))
        assertEquals(Ipv4Packet.UDP_PROTOCOL, reply.protocol)
        assertEquals(destination, reply.source)
        assertEquals(source, reply.destination)

        val replyUdp = requireNotNull(
            UdpPacket.parse(
                bytes = reply.payload,
                source = reply.source,
                destination = reply.destination,
            ),
        )
        assertEquals(53, replyUdp.sourcePort)
        assertEquals(1037, replyUdp.destinationPort)
        assertContentEquals(byteArrayOf(0x42, 0x43), replyUdp.payload)

        session.close()
    }

    @Test
    fun `invalid external UDP checksum is rejected`() {
        val sent = mutableListOf<PppFrame>()
        val logs = mutableListOf<String>()
        val udpProxy = FakeUdpProxy()
        val session = newSession(sent, logs::add, udpProxy = udpProxy)
        openIpcp(session, sent)
        sent.clear()

        val source = Ipv4Address.parse("10.0.0.2")
        val destination = Ipv4Address.parse("8.8.8.8")
        val encodedUdp = UdpPacket(
            sourcePort = 1037,
            destinationPort = 53,
            payload = byteArrayOf(1, 2, 3, 4),
        ).encode(source, destination)
        encodedUdp[encodedUdp.lastIndex] = (encodedUdp.last().toInt() xor 0x01).toByte()

        val request = Ipv4Packet(
            protocol = Ipv4Packet.UDP_PROTOCOL,
            source = source,
            destination = destination,
            payload = encodedUdp,
        )
        session.receive(PppFrame(PppSession.IPV4_PROTOCOL, request.encode()))

        assertTrue(udpProxy.sent.isEmpty())
        assertTrue(sent.isEmpty())
        assertTrue(logs.any { it.contains("invalid checksum") })
        session.close()
    }

    @Test
    fun `external UDP reply is dropped after IPCP renegotiates`() {
        val sent = mutableListOf<PppFrame>()
        val udpProxy = DeferredUdpProxy()
        val session = newSession(sent, udpProxy = udpProxy)
        openIpcp(session, sent)
        sent.clear()

        val source = Ipv4Address.parse("10.0.0.2")
        val destination = Ipv4Address.parse("8.8.8.8")
        val request = Ipv4Packet(
            protocol = Ipv4Packet.UDP_PROTOCOL,
            source = source,
            destination = destination,
            payload = UdpPacket(
                sourcePort = 1037,
                destinationPort = 53,
                payload = byteArrayOf(1, 2),
            ).encode(source, destination),
        )
        session.receive(PppFrame(PppSession.IPV4_PROTOCOL, request.encode()))

        session.receive(
            PppFrame(
                protocol = PppSession.IPCP_PROTOCOL,
                payload = PppControlPacket(
                    code = PppControlPacket.CONFIGURE_REQUEST,
                    identifier = 10,
                    data = PppControlOption(
                        type = PppControlOption.IPCP_IP_ADDRESS,
                        data = source.toByteArray(),
                    ).encode(),
                ).encode(),
            ),
        )
        val renegotiationRequest = sent
            .asSequence()
            .filter { it.protocol == PppSession.IPCP_PROTOCOL }
            .mapNotNull { PppControlPacket.parse(it.payload) }
            .first { it.code == PppControlPacket.CONFIGURE_REQUEST }
        session.receive(
            PppFrame(
                protocol = PppSession.IPCP_PROTOCOL,
                payload = PppControlPacket(
                    code = PppControlPacket.CONFIGURE_ACK,
                    identifier = renegotiationRequest.identifier,
                    data = renegotiationRequest.data,
                ).encode(),
            ),
        )
        assertTrue(session.ipcpOpen)

        sent.clear()
        udpProxy.reply(byteArrayOf(9, 9))

        assertTrue(sent.isEmpty())
        session.close()
    }

    private fun newSession(
        sent: MutableList<PppFrame>,
        logger: (String) -> Unit = {},
        icmpEchoProxy: IcmpEchoProxy = FakeIcmpEchoProxy(),
        udpProxy: UdpProxy = FakeUdpProxy(),
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
            icmpEchoProxy = icmpEchoProxy,
            udpProxy = udpProxy,
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
    private class DeferredIcmpEchoProxy : IcmpEchoProxy {
        private var callback: ((Result<Boolean>) -> Unit)? = null

        override fun echo(
            destination: Ipv4Address,
            timeoutMillis: Long,
            callback: (Result<Boolean>) -> Unit,
        ) {
            this.callback = callback
        }

        fun complete(reachable: Boolean) {
            val callback = requireNotNull(callback)
            this.callback = null
            callback(Result.success(reachable))
        }
    }

    private class FakeIcmpEchoProxy(
        private val reachable: Boolean = true,
    ) : IcmpEchoProxy {
        val destinations = mutableListOf<Ipv4Address>()

        override fun echo(
            destination: Ipv4Address,
            timeoutMillis: Long,
            callback: (Result<Boolean>) -> Unit,
        ) {
            destinations += destination
            callback(Result.success(reachable))
        }
    }

    private data class SentUdp(
        val flow: UdpFlow,
        val payload: ByteArray,
    )

    private class FakeUdpProxy(
        private val replyPayload: ByteArray? = null,
    ) : UdpProxy {
        val sent = mutableListOf<SentUdp>()

        override fun send(
            flow: UdpFlow,
            payload: ByteArray,
            onReply: (Result<ByteArray>) -> Unit,
        ) {
            sent += SentUdp(flow, payload.copyOf())
            replyPayload?.let { onReply(Result.success(it.copyOf())) }
        }
    }

    private class DeferredUdpProxy : UdpProxy {
        private var onReply: ((Result<ByteArray>) -> Unit)? = null

        override fun send(
            flow: UdpFlow,
            payload: ByteArray,
            onReply: (Result<ByteArray>) -> Unit,
        ) {
            this.onReply = onReply
        }

        fun reply(payload: ByteArray) {
            requireNotNull(onReply)(Result.success(payload.copyOf()))
        }
    }

}
