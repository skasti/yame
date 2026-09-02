package no.skasti.serialmodem.ppp.proxy

import no.skasti.serialmodem.ppp.PppAddresses
import no.skasti.serialmodem.ppp.PppControlOption
import no.skasti.serialmodem.ppp.PppControlPacket
import no.skasti.serialmodem.ppp.PppFrame
import no.skasti.serialmodem.ppp.ip.Ipv4Address
import no.skasti.serialmodem.ppp.ip.Ipv4Cidr
import no.skasti.serialmodem.ppp.ip.Ipv4Packet
import no.skasti.serialmodem.ppp.proxy.PppHttpCompatibilityConfig
import no.skasti.serialmodem.ppp.proxy.SystemRoutingTcpProxy
import no.skasti.serialmodem.ppp.session.PppSession
import no.skasti.serialmodem.ppp.tcp.TcpPacket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class HttpCompatibilityPppIntegrationTest {
    @Test
    fun `legacy HTTP request follows real HTTPS redirect through PPP TCP stack`() {
        val sent = CopyOnWriteArrayList<PppFrame>()
        val logs = CopyOnWriteArrayList<String>()
        val addresses = PppAddresses(
            localAddress = YAME,
            peerAddress = PEER,
            allocationSubnet = Ipv4Cidr.parse("10.0.0.0/30"),
        )
        val session = PppSession(
            sendFrame = sent::add,
            logger = logs::add,
            ipAddresses = addresses,
            selectPeerAddress = { requested ->
                if (requested == Ipv4Address.ZERO) addresses.peerAddress else requested
            },
            tcpProxy = SystemRoutingTcpProxy(
                httpConfig = PppHttpCompatibilityConfig(
                    enabled = true,
                    requestTimeoutMillis = 15_000,
                    maxResponseBytes = 2 * 1024 * 1024,
                ),
                logger = logs::add,
            ),
        )

        try {
            openIpcp(session, sent)
            sent.clear()

            val peerPort = 2301
            var peerSequence = 7000u
            sendTcp(
                session,
                TcpPacket(
                    sourcePort = peerPort,
                    destinationPort = 80,
                    sequenceNumber = peerSequence,
                    flags = TcpPacket.SYN,
                    windowSize = 0xffff,
                    options = byteArrayOf(2, 4, 5, 0xb4.toByte()),
                ),
            )
            peerSequence += 1u

            val synAck = waitForTcp(sent) { tcp ->
                tcp.hasFlag(TcpPacket.SYN) && tcp.hasFlag(TcpPacket.ACK)
            }
            var yameSequence = synAck.tcp.sequenceNumber + 1u
            sendTcp(
                session,
                TcpPacket(
                    sourcePort = peerPort,
                    destinationPort = 80,
                    sequenceNumber = peerSequence,
                    acknowledgmentNumber = yameSequence,
                    flags = TcpPacket.ACK,
                    windowSize = 0xffff,
                ),
            )

            val request =
                "GET /robots.txt HTTP/1.0\r\n" +
                    "Host: github.com\r\n" +
                    "User-Agent: YAME-PPP-integration\r\n\r\n"
            val requestBytes = request.toByteArray(StandardCharsets.US_ASCII)
            sent.clear()
            sendTcp(
                session,
                TcpPacket(
                    sourcePort = peerPort,
                    destinationPort = 80,
                    sequenceNumber = peerSequence,
                    acknowledgmentNumber = yameSequence,
                    flags = TcpPacket.ACK or TcpPacket.PSH,
                    windowSize = 0xffff,
                    payload = requestBytes,
                ),
            )
            peerSequence += requestBytes.size.toUInt()

            val response = ArrayList<Byte>()
            var processedFrames = 0
            var sawFin = false
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!sawFin && System.nanoTime() < deadline) {
                while (processedFrames < sent.size) {
                    val frame = sent[processedFrames++]
                    if (frame.protocol != PppSession.IPV4_PROTOCOL) continue
                    val ipv4 = Ipv4Packet.parse(frame.payload) ?: continue
                    if (ipv4.protocol != Ipv4Packet.TCP_PROTOCOL) continue
                    val tcp = TcpPacket.parse(ipv4.payload, ipv4.source, ipv4.destination) ?: continue
                    if (tcp.sourcePort != 80 || tcp.destinationPort != peerPort) continue

                    if (tcp.payload.isNotEmpty()) {
                        if (tcp.sequenceNumber == yameSequence) {
                            tcp.payload.forEach(response::add)
                            yameSequence += tcp.payload.size.toUInt()
                        }
                        sendTcp(
                            session,
                            TcpPacket(
                                sourcePort = peerPort,
                                destinationPort = 80,
                                sequenceNumber = peerSequence,
                                acknowledgmentNumber = yameSequence,
                                flags = TcpPacket.ACK,
                                windowSize = 0xffff,
                            ),
                        )
                    }

                    if (tcp.hasFlag(TcpPacket.FIN) && tcp.sequenceNumber == yameSequence) {
                        yameSequence += 1u
                        sawFin = true
                        sendTcp(
                            session,
                            TcpPacket(
                                sourcePort = peerPort,
                                destinationPort = 80,
                                sequenceNumber = peerSequence,
                                acknowledgmentNumber = yameSequence,
                                flags = TcpPacket.ACK,
                                windowSize = 0xffff,
                            ),
                        )
                    }
                }
                if (!sawFin) Thread.sleep(5)
            }

            if (!sawFin) fail("Timed out waiting for compatibility proxy response FIN")
            val text = response.toByteArray().toString(StandardCharsets.ISO_8859_1)
            assertTrue(text.startsWith("HTTP/1.0 200 OK\r\n"), text.take(300))
            assertTrue(text.contains("Content-Length:"), text.take(500))
            assertTrue(
                logs.any { it.contains("redirect") && it.contains("-> https://") },
                "Expected YAME to follow an HTTP -> HTTPS redirect, logs were: ${logs.joinToString(" | ")}",
            )
            assertTrue(
                logs.any { it.contains("TLS hidden from peer") },
                "Expected final HTTPS response to be returned as plain HTTP",
            )
        } finally {
            session.close()
        }
    }

    private fun sendTcp(session: PppSession, tcp: TcpPacket) {
        session.receive(
            PppFrame(
                protocol = PppSession.IPV4_PROTOCOL,
                payload = Ipv4Packet(
                    protocol = Ipv4Packet.TCP_PROTOCOL,
                    source = PEER,
                    destination = REMOTE,
                    payload = tcp.encode(PEER, REMOTE),
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

    companion object {
        private val PEER = Ipv4Address.parse("10.0.0.2")
        private val YAME = Ipv4Address.parse("10.0.0.1")
        private val REMOTE = Ipv4Address.parse("203.0.113.80")
    }
}
