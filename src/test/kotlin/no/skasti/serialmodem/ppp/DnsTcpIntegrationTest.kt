package no.skasti.serialmodem.ppp

import no.skasti.serialmodem.ppp.dns.PppDnsConfig
import no.skasti.serialmodem.ppp.ip.Ipv4Address
import no.skasti.serialmodem.ppp.ip.Ipv4Cidr
import no.skasti.serialmodem.ppp.ip.Ipv4Packet
import no.skasti.serialmodem.ppp.session.PppSession
import no.skasti.serialmodem.ppp.tcp.SystemTcpProxy
import no.skasti.serialmodem.ppp.tcp.TcpPacket
import no.skasti.serialmodem.ppp.udp.SystemUdpProxy
import no.skasti.serialmodem.ppp.udp.UdpPacket
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class DnsTcpIntegrationTest {
    @Test
    fun `truncated UDP DNS response can fall back to framed TCP on same YAME DNS address`() {
        DnsUpstreamFixture().use { upstream ->
            val sent = CopyOnWriteArrayList<PppFrame>()
            val addresses = PppAddresses(
                localAddress = YAME,
                peerAddress = PEER,
                allocationSubnet = Ipv4Cidr.parse("10.0.0.0/30"),
            )
            val session = PppSession(
                sendFrame = sent::add,
                logger = {},
                ipAddresses = addresses,
                selectPeerAddress = { requested ->
                    if (requested == Ipv4Address.ZERO) addresses.peerAddress else requested
                },
                udpProxy = SystemUdpProxy(),
                tcpProxy = SystemTcpProxy(),
                dnsConfig = PppDnsConfig(
                    upstreamServer = LOOPBACK,
                    upstreamPort = upstream.port,
                ),
            )

            try {
                openIpcp(session, sent)
                sent.clear()

                val firstQuery = dnsQuery(0x1234)
                session.receive(
                    PppFrame(
                        protocol = PppSession.IPV4_PROTOCOL,
                        payload = Ipv4Packet(
                            protocol = Ipv4Packet.UDP_PROTOCOL,
                            source = PEER,
                            destination = YAME,
                            payload = UdpPacket(
                                sourcePort = 1053,
                                destinationPort = 53,
                                payload = firstQuery,
                            ).encode(PEER, YAME),
                        ).encode(),
                    ),
                )

                val udpReplyIpv4 = waitForIpv4(sent) { ipv4 ->
                    ipv4.protocol == Ipv4Packet.UDP_PROTOCOL
                }
                val udpReply = requireNotNull(
                    UdpPacket.parse(
                        bytes = udpReplyIpv4.payload,
                        source = udpReplyIpv4.source,
                        destination = udpReplyIpv4.destination,
                    ),
                )
                assertEquals(YAME, udpReplyIpv4.source)
                assertEquals(PEER, udpReplyIpv4.destination)
                assertEquals(53, udpReply.sourcePort)
                assertEquals(1053, udpReply.destinationPort)
                assertTrue(udpReply.payload[2].toInt() and 0x02 != 0, "UDP response must set DNS TC=1")

                sent.clear()
                val peerPort = 2053
                var peerSequence = 4000u
                sendTcp(
                    session,
                    TcpPacket(
                        sourcePort = peerPort,
                        destinationPort = 53,
                        sequenceNumber = peerSequence,
                        flags = TcpPacket.SYN,
                        windowSize = 8192,
                    ),
                )
                peerSequence += 1u

                val synAck = waitForTcp(sent) { tcp -> tcp.hasFlag(TcpPacket.SYN) && tcp.hasFlag(TcpPacket.ACK) }
                assertEquals(YAME, synAck.ipv4.source)
                assertEquals(PEER, synAck.ipv4.destination)
                assertEquals(53, synAck.tcp.sourcePort)
                assertEquals(peerPort, synAck.tcp.destinationPort)

                var localAcknowledgment = synAck.tcp.sequenceNumber + 1u
                sendTcp(
                    session,
                    TcpPacket(
                        sourcePort = peerPort,
                        destinationPort = 53,
                        sequenceNumber = peerSequence,
                        acknowledgmentNumber = localAcknowledgment,
                        flags = TcpPacket.ACK,
                        windowSize = 8192,
                    ),
                )

                val firstFrame = dnsTcpFrame(firstQuery)
                sent.clear()
                sendTcp(
                    session,
                    TcpPacket(
                        sourcePort = peerPort,
                        destinationPort = 53,
                        sequenceNumber = peerSequence,
                        acknowledgmentNumber = localAcknowledgment,
                        flags = TcpPacket.ACK or TcpPacket.PSH,
                        windowSize = 8192,
                        payload = firstFrame,
                    ),
                )
                peerSequence += firstFrame.size.toUInt()

                val firstTcpReply = waitForTcp(sent) { tcp -> tcp.payload.isNotEmpty() }
                assertDnsTcpResponse(firstQuery, firstTcpReply.tcp.payload)
                localAcknowledgment = firstTcpReply.tcp.sequenceNumber + firstTcpReply.tcp.payload.size.toUInt()

                val secondQuery = dnsQuery(0x5678)
                val secondFrame = dnsTcpFrame(secondQuery)
                sent.clear()
                sendTcp(
                    session,
                    TcpPacket(
                        sourcePort = peerPort,
                        destinationPort = 53,
                        sequenceNumber = peerSequence,
                        acknowledgmentNumber = localAcknowledgment,
                        flags = TcpPacket.ACK or TcpPacket.PSH,
                        windowSize = 8192,
                        payload = secondFrame,
                    ),
                )
                peerSequence += secondFrame.size.toUInt()

                val secondTcpReply = waitForTcp(sent) { tcp -> tcp.payload.isNotEmpty() }
                assertDnsTcpResponse(secondQuery, secondTcpReply.tcp.payload)

                waitUntil("two DNS requests at upstream") { upstream.tcpQueries.size == 2 }
                assertContentEquals(firstQuery, upstream.tcpQueries[0])
                assertContentEquals(secondQuery, upstream.tcpQueries[1])
                assertEquals(1, upstream.tcpConnections)
                upstream.failure.get()?.let { throw AssertionError("DNS fixture failed", it) }
            } finally {
                session.close()
            }
        }
    }

    private fun assertDnsTcpResponse(query: ByteArray, framed: ByteArray) {
        assertTrue(framed.size >= 2)
        val length = ((framed[0].toInt() and 0xff) shl 8) or (framed[1].toInt() and 0xff)
        assertEquals(framed.size - 2, length)
        val response = framed.copyOfRange(2, framed.size)
        assertEquals(query[0], response[0])
        assertEquals(query[1], response[1])
        assertFalse(response[2].toInt() and 0x02 != 0, "TCP response must not be truncated")
    }

    private fun sendTcp(session: PppSession, tcp: TcpPacket) {
        session.receive(
            PppFrame(
                protocol = PppSession.IPV4_PROTOCOL,
                payload = Ipv4Packet(
                    protocol = Ipv4Packet.TCP_PROTOCOL,
                    source = PEER,
                    destination = YAME,
                    payload = tcp.encode(PEER, YAME),
                ).encode(),
            ),
        )
    }

    private data class ParsedTcp(
        val ipv4: Ipv4Packet,
        val tcp: TcpPacket,
    )

    private fun waitForTcp(
        sent: List<PppFrame>,
        predicate: (TcpPacket) -> Boolean,
    ): ParsedTcp {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            sent.forEach { frame ->
                if (frame.protocol != PppSession.IPV4_PROTOCOL) return@forEach
                val ipv4 = Ipv4Packet.parse(frame.payload) ?: return@forEach
                if (ipv4.protocol != Ipv4Packet.TCP_PROTOCOL) return@forEach
                val tcp = TcpPacket.parse(ipv4.payload, ipv4.source, ipv4.destination) ?: return@forEach
                if (predicate(tcp)) return ParsedTcp(ipv4, tcp)
            }
            Thread.sleep(5)
        }
        fail("Timed out waiting for TCP packet")
    }

    private fun waitForIpv4(
        sent: List<PppFrame>,
        predicate: (Ipv4Packet) -> Boolean,
    ): Ipv4Packet {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            sent.forEach { frame ->
                if (frame.protocol != PppSession.IPV4_PROTOCOL) return@forEach
                val ipv4 = Ipv4Packet.parse(frame.payload) ?: return@forEach
                if (predicate(ipv4)) return ipv4
            }
            Thread.sleep(5)
        }
        fail("Timed out waiting for IPv4 packet")
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for $description")
    }

    private fun openIpcp(session: PppSession, sent: MutableList<PppFrame>) {
        session.start()
        session.receive(
            PppFrame(
                protocol = PppSession.LCP_PROTOCOL,
                payload = LcpPacket(
                    code = LcpPacket.CONFIGURE_REQUEST,
                    identifier = 0x1c,
                    data = LcpOption(
                        type = LcpOptionType.ACCM,
                        data = byteArrayOf(0, 0, 0, 0),
                    ).encode(),
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
                            type = IpcpOptionType.IPCP_IP_ADDRESS,
                            data = PEER.toByteArray(),
                        ).encode() +
                                PppControlOption(
                                    type = IpcpOptionType.IPCP_PRIMARY_DNS,
                                    data = YAME.toByteArray(),
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

    private class DnsUpstreamFixture : AutoCloseable {
        private val loopback = InetAddress.getByName("127.0.0.1")
        private val udpSocket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(loopback, 0))
            soTimeout = 5_000
        }
        private val tcpSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(loopback, udpSocket.localPort))
            soTimeout = 5_000
        }

        val port: Int = udpSocket.localPort
        val tcpQueries = CopyOnWriteArrayList<ByteArray>()
        val failure = AtomicReference<Throwable?>()
        @Volatile var tcpConnections: Int = 0
            private set

        private val udpThread = Thread({
            runCatching {
                val buffer = ByteArray(4096)
                val request = DatagramPacket(buffer, buffer.size)
                udpSocket.receive(request)
                val query = request.data.copyOfRange(request.offset, request.offset + request.length)
                val response = dnsResponse(query, truncated = true)
                udpSocket.send(DatagramPacket(response, response.size, request.socketAddress))
            }.onFailure { error -> failure.compareAndSet(null, error) }
        }, "dns-tcp-test-udp").apply {
            isDaemon = true
            start()
        }

        private val tcpThread = Thread({
            runCatching {
                tcpSocket.accept().use { socket ->
                    tcpConnections++
                    socket.soTimeout = 5_000
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    repeat(2) {
                        val length = input.readUnsignedShort()
                        require(length in 1..0xffff)
                        val query = ByteArray(length)
                        input.readFully(query)
                        tcpQueries += query
                        val response = dnsResponse(query, truncated = false)
                        output.write(dnsTcpFrame(response))
                        output.flush()
                    }
                }
            }.onFailure { error -> failure.compareAndSet(null, error) }
        }, "dns-tcp-test-tcp").apply {
            isDaemon = true
            start()
        }

        override fun close() {
            runCatching { udpSocket.close() }
            runCatching { tcpSocket.close() }
            runCatching { udpThread.join(1_000) }
            runCatching { tcpThread.join(1_000) }
        }
    }

    companion object {
        private val PEER = Ipv4Address.parse("10.0.0.2")
        private val YAME = Ipv4Address.parse("10.0.0.1")
        private val LOOPBACK = Ipv4Address.parse("127.0.0.1")

        private fun dnsQuery(identifier: Int): ByteArray = byteArrayOf(
            (identifier ushr 8).toByte(), identifier.toByte(),
            0x01, 0x00,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x00,
            0x00, 0x01,
            0x00, 0x01,
        )

        private fun dnsTcpFrame(message: ByteArray): ByteArray =
            byteArrayOf((message.size ushr 8).toByte(), message.size.toByte()) + message

        private fun dnsResponse(query: ByteArray, truncated: Boolean): ByteArray =
            query.copyOf().also { response ->
                response[2] = if (truncated) 0x83.toByte() else 0x81.toByte()
                response[3] = 0x80.toByte()
            }
    }
}
