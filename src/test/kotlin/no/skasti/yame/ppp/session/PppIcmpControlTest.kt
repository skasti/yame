package no.skasti.yame.ppp.session

import no.skasti.yame.observer.YameEvent
import no.skasti.yame.ppp.PppFrame
import no.skasti.yame.ppp.dns.PppDnsConfig
import no.skasti.yame.ppp.icmp.IcmpDestinationUnreachable
import no.skasti.yame.ppp.icmp.IcmpEchoProxy
import no.skasti.yame.ppp.icmp.IcmpPacket
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.ip.Ipv4Packet
import no.skasti.yame.ppp.tcp.TcpProxy
import no.skasti.yame.ppp.tcp.TcpProxyEvent
import no.skasti.yame.ppp.tcp.TcpProxyFlow
import no.skasti.yame.ppp.udp.UdpFlow
import no.skasti.yame.ppp.udp.UdpFlowNamespace
import no.skasti.yame.ppp.udp.UdpPacket
import no.skasti.yame.ppp.udp.UdpProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PppIcmpControlTest {
    private val local = Ipv4Address.parse("10.0.0.1")
    private val peer = Ipv4Address.parse("10.0.0.2")
    private val remote = Ipv4Address.parse("203.0.113.11")
    private val dnsConfig = PppDnsConfig(Ipv4Address.parse("192.0.2.53"), 5353)

    @Test
    fun `all Win98 captures are consumed and DNS port rejections select the translated flow`() {
        Link().use { link ->
            val captures = resource("captures.txt").lineSequence().filter { it.isNotBlank() }.toList()
            assertEquals(13, captures.size)
            for (name in captures) {
                val hex = resource(name).lineSequence().single { it.startsWith("data=") }.removePrefix("data=")
                val raw = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val ip = requireNotNull(Ipv4Packet.parse(raw))
                val icmp = requireNotNull(IcmpPacket.parse(ip.payload))
                val before = link.udp.closed.size
                link.session.receive(PppFrame(PppSession.IPV4_PROTOCOL, raw))
                if (icmp.type == IcmpPacket.DESTINATION_UNREACHABLE) {
                    val error = requireNotNull(IcmpDestinationUnreachable.parse(icmp))
                    assertEquals(3, error.code)
                    assertEquals(53, error.sourcePort)
                    assertEquals(before + 1, link.udp.closed.size)
                    val closed = link.udp.closed.last()
                    assertEquals(error.destinationPort, closed.peerPort)
                    assertEquals(dnsConfig.upstreamServer, closed.destination)
                    assertEquals(dnsConfig.upstreamPort, closed.destinationPort)
                    assertEquals(link.udp.generation, closed.generation)
                    assertEquals(UdpFlowNamespace.LOCAL_DNS, closed.namespace)
                } else {
                    assertEquals(IcmpPacket.ROUTER_SOLICITATION, icmp.type)
                    assertEquals(before, link.udp.closed.size)
                }
            }
            assertTrue(link.frames.isEmpty(), "Control packets must not produce replies")
            assertTrue(link.events.filterIsInstance<YameEvent.UnhandledPacket>().isEmpty())
            assertTrue(link.logs.any { it.contains("port unreachable") })
            assertEquals(3, link.logs.count { it.contains("Router Solicitation") })
        }
    }

    @Test
    fun `external UDP rejection uses reversed quote ports and external namespace`() {
        Link().use { link ->
            val quote = udpReply(remote, peer, 53, 1105)
            link.receive(unreachable(quote), remote)
            assertEquals(
                UdpFlow(1105, remote, 53, link.udp.generation, UdpFlowNamespace.EXTERNAL),
                link.udp.closed.single(),
            )
            assertTrue(link.frames.isEmpty())
        }
    }

    @Test
    fun `foreign quotes malformed errors and soft errors never retire a flow`() {
        Link().use { link ->
            link.receive(unreachable(udpReply(remote, Ipv4Address.parse("10.0.0.3"))), remote)
            link.receive(unreachable(udpReply(remote, peer)), local)
            link.receive(unreachable(udpReply(local, peer, 9999)), local)
            link.receive(unreachable(udpReply(local, peer)).copy(body = ByteArray(12)), local)
            link.receive(unreachable(udpReply(local, peer)).copy(code = 255), local)
            val fragmented = udpReply(remote, peer).copy(flagsAndFragmentOffset = 0x2000)
            link.receive(unreachable(fragmented), remote)
            val tcpQuote = Ipv4Packet(protocol = Ipv4Packet.TCP_PROTOCOL, source = remote,
                destination = peer, payload = ByteArray(8))
            link.receive(unreachable(tcpQuote), remote)
            for (code in 0..15) {
                if (code != IcmpDestinationUnreachable.PORT_UNREACHABLE) {
                    link.receive(unreachable(udpReply(remote, peer)).copy(code = code), remote)
                }
            }
            assertTrue(link.udp.closed.isEmpty())
            assertTrue(link.frames.isEmpty())
            assertTrue(link.events.filterIsInstance<YameEvent.UnhandledPacket>().isEmpty())
        }
    }

    @Test
    fun `router solicitations are consumed locally for unicast broadcast and multicast`() {
        Link().use { link ->
            for (destination in listOf(local, Ipv4Address.parse("224.0.0.2"), Ipv4Address.parse("255.255.255.255"))) {
                // RFC 1256 says receivers ignore the reserved field; Win98 also uses TTL 128.
                link.receive(IcmpPacket(IcmpPacket.ROUTER_SOLICITATION, 0, byteArrayOf(1, 2, 3, 4)), destination)
            }
            link.receive(IcmpPacket(IcmpPacket.ROUTER_SOLICITATION, 1, ByteArray(4)), local)
            link.receive(IcmpPacket(IcmpPacket.ROUTER_SOLICITATION, 0, ByteArray(3)), local)
            assertEquals(3, link.logs.count { it.contains("consumed on PPP link") })
            assertEquals(2, link.logs.count { it.contains("malformed Router Solicitation") })
            assertTrue(link.frames.isEmpty())
            assertTrue(link.udp.closed.isEmpty())
            assertTrue(link.events.filterIsInstance<YameEvent.UnhandledPacket>().isEmpty())
        }
    }

    @Test
    fun `control packets remain gated by IPCP source address and checksums`() {
        Link(open = false).use { link ->
            link.receive(unreachable(udpReply(local, peer)), local)
            assertTrue(link.udp.closed.isEmpty())
        }
        Link().use { link ->
            val valid = Ipv4Packet(protocol = 1, source = peer, destination = local,
                payload = unreachable(udpReply(local, peer)).encode())
            link.session.receive(PppFrame(PppSession.IPV4_PROTOCOL,
                valid.copy(source = Ipv4Address.parse("10.0.0.3")).encode()))
            val invalidChecksum = valid.payload.copyOf().apply { this[2] = (this[2].toInt() xor 1).toByte() }
            link.session.receive(PppFrame(PppSession.IPV4_PROTOCOL, valid.copy(payload = invalidChecksum).encode()))
            assertTrue(link.udp.closed.isEmpty())
            assertTrue(link.frames.isEmpty())
        }
    }

    private fun udpReply(source: Ipv4Address, destination: Ipv4Address, sourcePort: Int = 53, peerPort: Int = 1105) =
        Ipv4Packet(protocol = Ipv4Packet.UDP_PROTOCOL, source = source, destination = destination,
            payload = UdpPacket(sourcePort, peerPort, ByteArray(64)).encode(source, destination))

    private fun unreachable(quote: Ipv4Packet) = IcmpPacket(IcmpPacket.DESTINATION_UNREACHABLE, 3,
        ByteArray(4) + quote.encode().copyOf(20 + quote.options.size + 8))

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/icmp/$name")).bufferedReader().use { it.readText() }

    private inner class Link(open: Boolean = true) : AutoCloseable {
        val frames = mutableListOf<PppFrame>()
        val logs = mutableListOf<String>()
        val events = mutableListOf<YameEvent>()
        val udp = RecordingUdpProxy()
        val session = PppSession(sendFrame = frames::add, logger = logs::add, eventSink = events::add,
            dnsConfig = dnsConfig, udpProxy = udp,
            icmpEchoProxy = object : IcmpEchoProxy {
                override fun echo(destination: Ipv4Address, timeoutMillis: Long, callback: (Result<Boolean>) -> Unit) =
                    error("ICMP control messages must not trigger a host ping")
            },
            tcpProxy = object : TcpProxy {
                override fun connect(flow: TcpProxyFlow, onEvent: (TcpProxyEvent) -> Unit) = error("Unexpected TCP connect")
                override fun send(flow: TcpProxyFlow, payload: ByteArray): Result<Unit> = error("Unexpected TCP send")
                override fun shutdownOutput(flow: TcpProxyFlow): Result<Unit> = error("Unexpected TCP shutdown")
                override fun closeFlow(flow: TcpProxyFlow) = error("ICMP errors must not close TCP flows")
            })

        init {
            if (open) openIpcp(session, frames)
            frames.clear()
        }

        fun receive(icmp: IcmpPacket, destination: Ipv4Address) {
            session.receive(PppFrame(PppSession.IPV4_PROTOCOL,
                Ipv4Packet(ttl = 128, protocol = 1, source = peer, destination = destination, payload = icmp.encode()).encode()))
        }

        override fun close() = session.close()
    }

    private class RecordingUdpProxy : UdpProxy {
        val closed = mutableListOf<UdpFlow>()
        var generation = 0L
        override fun send(flow: UdpFlow, payload: ByteArray, onReply: (Result<ByteArray>) -> Unit) =
            error("ICMP control messages must not send UDP upstream")
        override fun invalidateBefore(generation: Long) { this.generation = generation }
        override fun closeFlow(flow: UdpFlow): Result<Unit> {
            closed += flow
            return Result.success(Unit)
        }
    }
}
