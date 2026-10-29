package no.skasti.yame.ppp.session

import no.skasti.yame.observer.YameEvent
import no.skasti.yame.ppp.PppFrame
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.ip.Ipv4Packet
import no.skasti.yame.ppp.tcp.*
import kotlin.test.*

class TransferIdentityTest {
    @Test fun `PPP transfer events share proxy identity and ignore callbacks from a reused tuple`() {
        val sent = mutableListOf<PppFrame>()
        val observations = mutableListOf<YameEvent>()
        val proxy = RecordingProxy()
        PppSession(sendFrame = sent::add, logger = {}, diagnosticSink = observations::add, tcpProxy = proxy).use { session ->
            openIpcp(session, sent)
            fun send(flags: Int, sequence: UInt, ack: UInt = 0u, body: ByteArray = byteArrayOf()) {
                val peer = Ipv4Address.parse("10.0.0.2")
                val remote = Ipv4Address.parse("192.0.2.1")
                val tcp = TcpPacket(2345, 80, sequence, ack, flags = flags, payload = body)
                session.receive(PppFrame(PppSession.IPV4_PROTOCOL,
                    Ipv4Packet(protocol = Ipv4Packet.TCP_PROTOCOL, source = peer, destination = remote,
                        payload = tcp.encode(peer, remote)).encode()))
            }
            fun connect(sequence: UInt) {
                sent.clear()
                send(TcpPacket.SYN, sequence)
                val syn = sent.mapNotNull { Ipv4Packet.parse(it.payload) }
                    .mapNotNull { TcpPacket.parse(it.payload, it.source, it.destination) }
                    .single { it.hasFlag(TcpPacket.SYN) }
                send(TcpPacket.ACK, sequence + 1u, syn.sequenceNumber + 1u)
                send(TcpPacket.ACK or TcpPacket.PSH, sequence + 1u, syn.sequenceNumber + 1u, byteArrayOf(1, 2, 3))
            }
            connect(1000u)
            proxy.callbacks[0](TcpProxyEvent.Payload(byteArrayOf(4, 5)))
            send(TcpPacket.RST, 1004u)
            connect(2000u)
            val starts = observations.filterIsInstance<TransferStarted>()
            assertEquals(2, starts.size)
            assertEquals(starts[0].flowId, starts[1].flowId)
            assertNotEquals(starts[0].transferIdentity, starts[1].transferIdentity)
            assertEquals(proxy.flows.map { it.transferIdentity }, starts.map { it.transferIdentity })
            val before = observations.filterIsInstance<TransferBytes>().size
            proxy.callbacks[0](TcpProxyEvent.Payload(byteArrayOf(99)))
            assertEquals(before, observations.filterIsInstance<TransferBytes>().size)
            proxy.callbacks[1](TcpProxyEvent.Payload(byteArrayOf(6)))
            val bytes = observations.filterIsInstance<TransferBytes>()
            assertEquals(starts[0].transferIdentity, bytes.first().transferIdentity)
            assertEquals(starts[1].transferIdentity, bytes.last().transferIdentity)
            assertTrue(observations.filterIsInstance<TransferStateChanged>().all { it.transferIdentity != null })
        }
    }

    private class RecordingProxy : TcpProxy {
        val flows = mutableListOf<TcpProxyFlow>()
        val callbacks = mutableListOf<(TcpProxyEvent) -> Unit>()
        override fun connect(flow: TcpProxyFlow, onEvent: (TcpProxyEvent) -> Unit) {
            flows += flow
            callbacks += onEvent
            onEvent(TcpProxyEvent.Connected)
        }
        override fun send(flow: TcpProxyFlow, payload: ByteArray) = Result.success(Unit)
        override fun shutdownOutput(flow: TcpProxyFlow) = Result.success(Unit)
        override fun closeFlow(flow: TcpProxyFlow) = Unit
    }
}
