package no.skasti.serialmodem.ppp

import no.skasti.serialmodem.observer.TransferState
import no.skasti.serialmodem.observer.YameEvent
import no.skasti.serialmodem.ppp.dns.PppDnsConfig
import no.skasti.serialmodem.ppp.icmp.IcmpEchoProxy
import no.skasti.serialmodem.ppp.icmp.IcmpPacket
import no.skasti.serialmodem.ppp.ip.Ipv4Address
import no.skasti.serialmodem.ppp.ip.Ipv4Cidr
import no.skasti.serialmodem.ppp.ip.Ipv4Packet
import no.skasti.serialmodem.ppp.session.PppSession
import no.skasti.serialmodem.ppp.tcp.TcpPacket
import no.skasti.serialmodem.ppp.tcp.TcpProxy
import no.skasti.serialmodem.ppp.tcp.TcpProxyEvent
import no.skasti.serialmodem.ppp.tcp.TcpProxyFlow
import no.skasti.serialmodem.ppp.udp.UdpFlow
import no.skasti.serialmodem.ppp.udp.UdpPacket
import no.skasti.serialmodem.ppp.udp.UdpProxy
import java.util.concurrent.CopyOnWriteArrayList
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
                    data =
                        PppControlOption(
                            type = PppControlOption.IPCP_IP_ADDRESS,
                            data = Ipv4Address.parse("10.0.0.2").toByteArray(),
                        ).encode() +
                                PppControlOption(
                                    type = PppControlOption.IPCP_PRIMARY_DNS,
                                    data = Ipv4Address.parse("10.0.0.1").toByteArray(),
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
    fun `local DNS datagram is proxied to configured upstream and returned from YAME address`() {
        val sent = mutableListOf<PppFrame>()
        val responsePayload = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte())
        val udpProxy = FakeUdpProxy(replyPayload = responsePayload)
        val session = newSession(
            sent = sent,
            udpProxy = udpProxy,
            dnsConfig = PppDnsConfig(
                upstreamServer = Ipv4Address.parse("1.1.1.1"),
            ),
        )
        openIpcp(session, sent)
        sent.clear()

        val peer = Ipv4Address.parse("10.0.0.2")
        val yame = Ipv4Address.parse("10.0.0.1")
        val queryPayload = byteArrayOf(0x12, 0x34, 0x01, 0x00)
        val request = Ipv4Packet(
            protocol = Ipv4Packet.UDP_PROTOCOL,
            source = peer,
            destination = yame,
            payload = UdpPacket(
                sourcePort = 1025,
                destinationPort = 53,
                payload = queryPayload,
            ).encode(peer, yame),
        )

        session.receive(PppFrame(PppSession.IPV4_PROTOCOL, request.encode()))

        val forwarded = udpProxy.sent.single()
        assertEquals(Ipv4Address.parse("1.1.1.1"), forwarded.flow.destination)
        assertEquals(53, forwarded.flow.destinationPort)
        assertEquals(1025, forwarded.flow.peerPort)
        assertContentEquals(queryPayload, forwarded.payload)

        val frame = sent.single()
        val reply = requireNotNull(Ipv4Packet.parse(frame.payload))
        assertEquals(yame, reply.source)
        assertEquals(peer, reply.destination)
        val udp = requireNotNull(
            UdpPacket.parse(
                bytes = reply.payload,
                source = reply.source,
                destination = reply.destination,
            ),
        )
        assertEquals(53, udp.sourcePort)
        assertEquals(1025, udp.destinationPort)
        assertContentEquals(responsePayload, udp.payload)
        session.close()
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
                    data =
                        PppControlOption(
                            type = PppControlOption.IPCP_IP_ADDRESS,
                            data = source.toByteArray(),
                        ).encode() +
                                PppControlOption(
                                    type = PppControlOption.IPCP_PRIMARY_DNS,
                                    data = Ipv4Address.parse("10.0.0.1").toByteArray(),
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

    @Test
    fun `external TCP host reads pause at peer window and resume after acknowledgments`() {
        val sent = CopyOnWriteArrayList<PppFrame>()
        val tcpProxy = FakeTcpProxy()
        val session = newSession(
            sent = sent,
            tcpProxy = tcpProxy,
        )
        openIpcp(session, sent)
        sent.clear()

        val peer = Ipv4Address.parse("10.0.0.2")
        val remote = Ipv4Address.parse("203.0.113.10")
        val peerPort = 2048
        val remotePort = 80

        fun receiveTcp(packet: TcpPacket) {
            val ipv4 = Ipv4Packet(
                protocol = Ipv4Packet.TCP_PROTOCOL,
                source = peer,
                destination = remote,
                payload = packet.encode(peer, remote),
            )
            session.receive(PppFrame(PppSession.IPV4_PROTOCOL, ipv4.encode()))
        }

        receiveTcp(
            TcpPacket(
                sourcePort = peerPort,
                destinationPort = remotePort,
                sequenceNumber = 1000u,
                flags = TcpPacket.SYN,
                windowSize = 4,
            ),
        )

        assertEquals(1, tcpProxy.pauseCount)
        val synAckIpv4 = requireNotNull(Ipv4Packet.parse(sent.single().payload))
        val synAck = requireNotNull(
            TcpPacket.parse(
                synAckIpv4.payload,
                synAckIpv4.source,
                synAckIpv4.destination,
            ),
        )

        receiveTcp(
            TcpPacket(
                sourcePort = peerPort,
                destinationPort = remotePort,
                sequenceNumber = 1001u,
                acknowledgmentNumber = synAck.sequenceNumber + 1u,
                flags = TcpPacket.ACK,
                windowSize = 4,
            ),
        )
        assertEquals(1, tcpProxy.resumeCount)

        sent.clear()
        tcpProxy.emitPayload(ByteArray(8) { it.toByte() })

        assertEquals(1, sent.size)
        val firstIpv4 = requireNotNull(Ipv4Packet.parse(sent.single().payload))
        val firstPayload = requireNotNull(
            TcpPacket.parse(
                firstIpv4.payload,
                firstIpv4.source,
                firstIpv4.destination,
            ),
        )
        assertEquals(4, firstPayload.payload.size)
        assertEquals(2, tcpProxy.pauseCount)

        sent.clear()
        receiveTcp(
            TcpPacket(
                sourcePort = peerPort,
                destinationPort = remotePort,
                sequenceNumber = 1001u,
                acknowledgmentNumber = synAck.sequenceNumber + 5u,
                flags = TcpPacket.ACK,
                windowSize = 4,
            ),
        )

        assertEquals(1, sent.size)
        val secondIpv4 = requireNotNull(Ipv4Packet.parse(sent.single().payload))
        val secondPayload = requireNotNull(
            TcpPacket.parse(
                secondIpv4.payload,
                secondIpv4.source,
                secondIpv4.destination,
            ),
        )
        assertEquals(4, secondPayload.payload.size)

        sent.clear()
        receiveTcp(
            TcpPacket(
                sourcePort = peerPort,
                destinationPort = remotePort,
                sequenceNumber = 1001u,
                acknowledgmentNumber = synAck.sequenceNumber + 9u,
                flags = TcpPacket.ACK,
                windowSize = 4,
            ),
        )
        assertEquals(2, tcpProxy.resumeCount)

        session.close()
    }

    @Test
    fun `external TCP host connection expires when peer never completes handshake`() {
        val sent = CopyOnWriteArrayList<PppFrame>()
        val logs = CopyOnWriteArrayList<String>()
        val tcpProxy = FakeTcpProxy()
        val session = newSession(
            sent = sent,
            logger = logs::add,
            tcpProxy = tcpProxy,
            tcpHandshakeTimeoutMillis = 50,
        )
        openIpcp(session, sent)
        sent.clear()

        val peer = Ipv4Address.parse("10.0.0.2")
        val remote = Ipv4Address.parse("203.0.113.11")
        val syn = TcpPacket(
            sourcePort = 2049,
            destinationPort = 80,
            sequenceNumber = 3000u,
            flags = TcpPacket.SYN,
            windowSize = 8192,
        )
        session.receive(
            PppFrame(
                protocol = PppSession.IPV4_PROTOCOL,
                payload = Ipv4Packet(
                    protocol = Ipv4Packet.TCP_PROTOCOL,
                    source = peer,
                    destination = remote,
                    payload = syn.encode(peer, remote),
                ).encode(),
            ),
        )

        val deadline = System.nanoTime() + 2_000_000_000L
        while (tcpProxy.closedFlows.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }

        assertTrue(tcpProxy.closedFlows.isNotEmpty())
        assertTrue(logs.any { it.contains("did not complete handshake") })

        val tcpReplies = sent
            .filter { it.protocol == PppSession.IPV4_PROTOCOL }
            .mapNotNull { frame ->
                Ipv4Packet.parse(frame.payload)?.let { ipv4 ->
                    if (ipv4.protocol != Ipv4Packet.TCP_PROTOCOL) {
                        null
                    } else {
                        TcpPacket.parse(ipv4.payload, ipv4.source, ipv4.destination)
                    }
                }
            }
        assertTrue(tcpReplies.any { it.hasFlag(TcpPacket.RST) })

        session.close()
    }

    @Test
    fun `external TCP retransmits unacknowledged host payload after timeout`() {
        val sent = CopyOnWriteArrayList<PppFrame>()
        val tcpProxy = FakeTcpProxy()
        val session = newSession(
            sent = sent,
            tcpProxy = tcpProxy,
            tcpRetransmitTimeoutMillis = 60,
        )
        openIpcp(session, sent)
        sent.clear()

        val peer = Ipv4Address.parse("10.0.0.2")
        val remote = Ipv4Address.parse("203.0.113.12")
        val peerPort = 2050
        val remotePort = 80

        fun receiveTcp(packet: TcpPacket) {
            session.receive(
                PppFrame(
                    protocol = PppSession.IPV4_PROTOCOL,
                    payload = Ipv4Packet(
                        protocol = Ipv4Packet.TCP_PROTOCOL,
                        source = peer,
                        destination = remote,
                        payload = packet.encode(peer, remote),
                    ).encode(),
                ),
            )
        }

        receiveTcp(
            TcpPacket(
                sourcePort = peerPort,
                destinationPort = remotePort,
                sequenceNumber = 4000u,
                flags = TcpPacket.SYN,
                windowSize = 8192,
                options = byteArrayOf(2, 4, 5, 0xb4.toByte()),
            ),
        )
        val synAckIpv4 = requireNotNull(Ipv4Packet.parse(sent.single().payload))
        val synAck = requireNotNull(
            TcpPacket.parse(
                synAckIpv4.payload,
                synAckIpv4.source,
                synAckIpv4.destination,
            ),
        )
        receiveTcp(
            TcpPacket(
                sourcePort = peerPort,
                destinationPort = remotePort,
                sequenceNumber = 4001u,
                acknowledgmentNumber = synAck.sequenceNumber + 1u,
                flags = TcpPacket.ACK,
                windowSize = 8192,
            ),
        )

        sent.clear()
        tcpProxy.emitPayload("lost-once".encodeToByteArray())
        val firstIpv4 = requireNotNull(Ipv4Packet.parse(sent.single().payload))
        val first = requireNotNull(
            TcpPacket.parse(firstIpv4.payload, firstIpv4.source, firstIpv4.destination),
        )
        assertContentEquals("lost-once".encodeToByteArray(), first.payload)

        val deadline = System.nanoTime() + 2_000_000_000L
        while (sent.size < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }

        assertTrue(sent.size >= 2)
        val retransmittedIpv4 = requireNotNull(Ipv4Packet.parse(sent[1].payload))
        val retransmitted = requireNotNull(
            TcpPacket.parse(
                retransmittedIpv4.payload,
                retransmittedIpv4.source,
                retransmittedIpv4.destination,
            ),
        )
        assertEquals(first.sequenceNumber, retransmitted.sequenceNumber)
        assertContentEquals(first.payload, retransmitted.payload)

        receiveTcp(
            TcpPacket(
                sourcePort = peerPort,
                destinationPort = remotePort,
                sequenceNumber = 4001u,
                acknowledgmentNumber = first.sequenceNumber + first.payload.size.toUInt(),
                flags = TcpPacket.ACK,
                windowSize = 8192,
            ),
        )

        val countAfterAck = sent.size
        Thread.sleep(150)
        assertEquals(countAfterAck, sent.size)
        session.close()
    }

    @Test
    fun `external TCP shrinks receive window when host write buffer fills and reopens it on completion`() {
        val sent = CopyOnWriteArrayList<PppFrame>()
        val tcpProxy = FakeTcpProxy(initialWriteCapacity = 4)
        val session = newSession(sent = sent, tcpProxy = tcpProxy)
        openIpcp(session, sent)
        sent.clear()

        val peer = Ipv4Address.parse("10.0.0.2")
        val remote = Ipv4Address.parse("203.0.113.13")
        val peerPort = 2051
        val remotePort = 80

        fun receiveTcp(packet: TcpPacket) {
            session.receive(
                PppFrame(
                    protocol = PppSession.IPV4_PROTOCOL,
                    payload = Ipv4Packet(
                        protocol = Ipv4Packet.TCP_PROTOCOL,
                        source = peer,
                        destination = remote,
                        payload = packet.encode(peer, remote),
                    ).encode(),
                ),
            )
        }

        receiveTcp(
            TcpPacket(
                sourcePort = peerPort,
                destinationPort = remotePort,
                sequenceNumber = 5000u,
                flags = TcpPacket.SYN,
                windowSize = 8192,
            ),
        )
        val synAckIpv4 = requireNotNull(Ipv4Packet.parse(sent.single().payload))
        val synAck = requireNotNull(
            TcpPacket.parse(synAckIpv4.payload, synAckIpv4.source, synAckIpv4.destination),
        )
        assertEquals(4, synAck.windowSize)

        receiveTcp(
            TcpPacket(
                sourcePort = peerPort,
                destinationPort = remotePort,
                sequenceNumber = 5001u,
                acknowledgmentNumber = synAck.sequenceNumber + 1u,
                flags = TcpPacket.ACK,
                windowSize = 8192,
            ),
        )

        sent.clear()
        receiveTcp(
            TcpPacket(
                sourcePort = peerPort,
                destinationPort = remotePort,
                sequenceNumber = 5001u,
                acknowledgmentNumber = synAck.sequenceNumber + 1u,
                flags = TcpPacket.ACK or TcpPacket.PSH,
                windowSize = 8192,
                payload = byteArrayOf(1, 2, 3, 4),
            ),
        )

        assertEquals(1, tcpProxy.sentPayloads.size)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), tcpProxy.sentPayloads.single())
        val zeroWindowIpv4 = requireNotNull(Ipv4Packet.parse(sent.single().payload))
        val zeroWindowAck = requireNotNull(
            TcpPacket.parse(
                zeroWindowIpv4.payload,
                zeroWindowIpv4.source,
                zeroWindowIpv4.destination,
            ),
        )
        assertEquals(0, zeroWindowAck.windowSize)
        assertEquals(5005u, zeroWindowAck.acknowledgmentNumber)

        sent.clear()
        receiveTcp(
            TcpPacket(
                sourcePort = peerPort,
                destinationPort = remotePort,
                sequenceNumber = 5005u,
                acknowledgmentNumber = synAck.sequenceNumber + 1u,
                flags = TcpPacket.ACK or TcpPacket.PSH,
                windowSize = 8192,
                payload = byteArrayOf(5, 6, 7, 8),
            ),
        )
        assertEquals(1, tcpProxy.sentPayloads.size)
        val rejectedIpv4 = requireNotNull(Ipv4Packet.parse(sent.single().payload))
        val rejectedAck = requireNotNull(
            TcpPacket.parse(rejectedIpv4.payload, rejectedIpv4.source, rejectedIpv4.destination),
        )
        assertEquals(0, rejectedAck.windowSize)
        assertEquals(5005u, rejectedAck.acknowledgmentNumber)

        sent.clear()
        tcpProxy.completeWrite(4)
        val reopenedIpv4 = requireNotNull(Ipv4Packet.parse(sent.single().payload))
        val reopenedAck = requireNotNull(
            TcpPacket.parse(reopenedIpv4.payload, reopenedIpv4.source, reopenedIpv4.destination),
        )
        assertEquals(4, reopenedAck.windowSize)
        assertEquals(5005u, reopenedAck.acknowledgmentNumber)

        sent.clear()
        receiveTcp(
            TcpPacket(
                sourcePort = peerPort,
                destinationPort = remotePort,
                sequenceNumber = 5005u,
                acknowledgmentNumber = synAck.sequenceNumber + 1u,
                flags = TcpPacket.ACK or TcpPacket.PSH,
                windowSize = 8192,
                payload = byteArrayOf(5, 6, 7, 8),
            ),
        )
        assertEquals(2, tcpProxy.sentPayloads.size)
        assertContentEquals(byteArrayOf(5, 6, 7, 8), tcpProxy.sentPayloads.last())
        session.close()
    }

    @Test
    fun `IPCP renegotiation closes active TCP transfer event`() {
        val sent = mutableListOf<PppFrame>()
        val events = mutableListOf<YameEvent>()
        val tcpProxy = FakeTcpProxy()
        val session = newSession(
            sent = sent,
            tcpProxy = tcpProxy,
            eventSink = events::add,
        )
        openIpcp(session, sent)
        sent.clear()

        val peer = Ipv4Address.parse("10.0.0.2")
        val remote = Ipv4Address.parse("203.0.113.20")
        val peerPort = 2060
        val remotePort = 80

        fun receiveTcp(packet: TcpPacket) {
            session.receive(
                PppFrame(
                    protocol = PppSession.IPV4_PROTOCOL,
                    payload = Ipv4Packet(
                        protocol = Ipv4Packet.TCP_PROTOCOL,
                        source = peer,
                        destination = remote,
                        payload = packet.encode(peer, remote),
                    ).encode(),
                ),
            )
        }

        receiveTcp(
            TcpPacket(
                sourcePort = peerPort,
                destinationPort = remotePort,
                sequenceNumber = 6000u,
                flags = TcpPacket.SYN,
                windowSize = 8192,
            ),
        )
        val synAckIpv4 = requireNotNull(Ipv4Packet.parse(sent.single().payload))
        val synAck = requireNotNull(
            TcpPacket.parse(synAckIpv4.payload, synAckIpv4.source, synAckIpv4.destination),
        )
        receiveTcp(
            TcpPacket(
                sourcePort = peerPort,
                destinationPort = remotePort,
                sequenceNumber = 6001u,
                acknowledgmentNumber = synAck.sequenceNumber + 1u,
                flags = TcpPacket.ACK,
                windowSize = 8192,
            ),
        )

        events.clear()
        session.receive(
            PppFrame(
                protocol = PppSession.IPCP_PROTOCOL,
                payload = PppControlPacket(
                    code = PppControlPacket.CONFIGURE_REQUEST,
                    identifier = 11,
                    data =
                        PppControlOption(
                            type = PppControlOption.IPCP_IP_ADDRESS,
                            data = peer.toByteArray(),
                        ).encode() +
                                PppControlOption(
                                    type = PppControlOption.IPCP_PRIMARY_DNS,
                                    data = Ipv4Address.parse("10.0.0.1").toByteArray(),
                                ).encode(),
                ).encode(),
            ),
        )

        val closed = events
            .filterIsInstance<YameEvent.TransferStateChanged>()
            .single { it.state == TransferState.CLOSED }
        assertEquals("10.0.0.2:2060->203.0.113.20:80", closed.flowId)
        assertEquals("IPCP renegotiated", closed.detail)

        session.close()
    }

    private fun newSession(
        sent: MutableList<PppFrame>,
        logger: (String) -> Unit = {},
        icmpEchoProxy: IcmpEchoProxy = FakeIcmpEchoProxy(),
        udpProxy: UdpProxy = FakeUdpProxy(),
        tcpProxy: TcpProxy = FakeTcpProxy(),
        tcpHandshakeTimeoutMillis: Long = 10_000,
        tcpRetransmitTimeoutMillis: Long = 3_000,
        dnsConfig: PppDnsConfig = PppDnsConfig(),
        eventSink: (YameEvent) -> Unit = {},
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
            tcpProxy = tcpProxy,
            tcpHandshakeTimeoutMillis = tcpHandshakeTimeoutMillis,
            tcpRetransmitTimeoutMillis = tcpRetransmitTimeoutMillis,
            dnsConfig = dnsConfig,
            eventSink = eventSink,
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
                    data =
                        PppControlOption(
                            type = PppControlOption.IPCP_IP_ADDRESS,
                            data = Ipv4Address.parse("10.0.0.2").toByteArray(),
                        ).encode() +
                                PppControlOption(
                                    type = PppControlOption.IPCP_PRIMARY_DNS,
                                    data = Ipv4Address.parse("10.0.0.1").toByteArray(),
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

    private class FakeTcpProxy(
        initialWriteCapacity: Int = 0xffff,
    ) : TcpProxy {
        private var callback: ((TcpProxyEvent) -> Unit)? = null
        private var flow: TcpProxyFlow? = null
        private var writeCapacity: Int = initialWriteCapacity

        var pauseCount: Int = 0
            private set
        var resumeCount: Int = 0
            private set
        val closedFlows = CopyOnWriteArrayList<TcpProxyFlow>()
        val sentPayloads = CopyOnWriteArrayList<ByteArray>()

        override fun connect(
            flow: TcpProxyFlow,
            onEvent: (TcpProxyEvent) -> Unit,
        ) {
            this.flow = flow
            callback = onEvent
            onEvent(TcpProxyEvent.Connected)
        }

        override fun send(
            flow: TcpProxyFlow,
            payload: ByteArray,
        ): Result<Unit> {
            if (payload.size > writeCapacity) {
                return Result.failure(IllegalStateException("synthetic host write buffer full"))
            }
            writeCapacity -= payload.size
            sentPayloads += payload.copyOf()
            return Result.success(Unit)
        }

        override fun availableWriteCapacity(flow: TcpProxyFlow): Int = writeCapacity

        override fun shutdownOutput(flow: TcpProxyFlow): Result<Unit> =
            Result.success(Unit)

        override fun pauseReads(flow: TcpProxyFlow) {
            pauseCount++
        }

        override fun resumeReads(flow: TcpProxyFlow) {
            resumeCount++
        }

        override fun closeFlow(flow: TcpProxyFlow) {
            closedFlows += flow
        }

        fun emitPayload(payload: ByteArray) {
            requireNotNull(callback)(TcpProxyEvent.Payload(payload.copyOf()))
        }

        fun completeWrite(bytes: Int) {
            writeCapacity = (writeCapacity + bytes).coerceAtMost(0xffff)
            requireNotNull(callback)(TcpProxyEvent.WriteCompleted(bytes))
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
