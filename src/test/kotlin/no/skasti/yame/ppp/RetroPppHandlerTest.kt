package no.skasti.yame.ppp

import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.ip.Ipv4Cidr
import no.skasti.yame.ppp.session.PppSessionEnded
import no.skasti.yame.modem.HayesModem
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.ppp.lcp.LcpPacket
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.ppp.proxy.ResourceState
import no.skasti.yame.ppp.proxy.YameRegistries

import no.skasti.yame.ppp.session.PppSession
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RetroPppHandlerTest : no.skasti.yame.config.InstalledConfigTest() {
    @org.junit.jupiter.api.BeforeEach
    fun configure() = installConfig(no.skasti.yame.config.ConfigStore())

    @Test
    fun `connected waits for peer PPP traffic before starting LCP`() {
        val output = ByteArrayOutputStream()
        val handler = isolatedHandler()
        handler.attachOutput(output)

        handler.connected()

        assertEquals(0, output.size())

        val peerRequest = LcpPacket(
            code = LcpPacket.CONFIGURE_REQUEST,
            identifier = 0x0b,
            data = byteArrayOf(
                0x01, 0x04, 0x02, 0x40,
                0x02, 0x06, 0x00, 0x00, 0x00, 0x00,
                0x05, 0x06, 0x00, 0x02, 0x76, 0xd1.toByte(),
                0x07, 0x02,
                0x08, 0x02,
            ),
        )
        handler.receive(
            PppEncoder().encode(
                PppFrame(
                    protocol = PppSession.LCP_PROTOCOL,
                    payload = peerRequest.encode(),
                ),
            ),
        )

        val replies = decode(output.toByteArray())
        assertEquals(2, replies.size)

        val ack = requireNotNull(LcpPacket.parse(replies[0].payload))
        assertEquals(LcpPacket.CONFIGURE_ACK, ack.code)
        assertEquals(peerRequest.identifier, ack.identifier)
        assertContentEquals(peerRequest.data, ack.data)

        val localRequest = requireNotNull(LcpPacket.parse(replies[1].payload))
        assertEquals(LcpPacket.CONFIGURE_REQUEST, localRequest.code)
    }

    @Test
    fun `each modem connection creates a fresh PPP session that waits for peer traffic`() {
        val output = ByteArrayOutputStream()
        val handler = isolatedHandler()
        handler.attachOutput(output)

        handler.connected()
        assertEquals(0, output.size())
        handler.receive(peerConfigureRequestWire())
        val firstRequest = decode(output.toByteArray())
            .mapNotNull { LcpPacket.parse(it.payload) }
            .single { it.code == LcpPacket.CONFIGURE_REQUEST }

        output.reset()
        handler.connected()
        assertEquals(0, output.size())
        handler.receive(peerConfigureRequestWire())
        val secondRequest = decode(output.toByteArray())
            .mapNotNull { LcpPacket.parse(it.payload) }
            .single { it.code == LcpPacket.CONFIGURE_REQUEST }

        assertEquals(LcpPacket.CONFIGURE_REQUEST, firstRequest.code)
        assertEquals(LcpPacket.CONFIGURE_REQUEST, secondRequest.code)
        assertEquals(firstRequest.identifier, secondRequest.identifier)
        assertContentEquals(firstRequest.data, secondRequest.data)
    }

    @Test
    fun `resource graph registry survives PPP redial`() {
        val registry = NavigationResourceRegistry(maxContexts = 8)
        val root = URI("http://legacy.test/page")
        val original = registry.startNavigation(root)
        val output = ByteArrayOutputStream()
        val handler =
            RetroPppHandler(
                logger = {},
                resourceGraphs = registry,
            )
        handler.attachOutput(output)

        handler.connected()
        handler.connected()

        val revisited = registry.startNavigation(root)
        assertEquals(original.id, revisited.id)
        assertEquals(1, registry.snapshots().size)
        handler.close()
    }

    @Test
    fun `replacement handlers share injected registry and retain graph and fetch fencing`() {
        val registry = NavigationResourceRegistry()
        val firstHandler = RetroPppHandler(logger = {}, resourceGraphs = registry)
        val root = URI("http://handler-replacement-99.test/page")
        val graph = registry.startNavigation(root)
        val resource = URI("http://cdn-handler-replacement-99.test/image.gif")
        val upstream = URI("https://cdn-handler-replacement-99.test/image.gif")
        val oldAttempt = requireNotNull(registry.beginFetch(graph, resource, upstream, upstream))

        val replacementHandler = RetroPppHandler(logger = {}, resourceGraphs = registry)
        val replacementRegistry = replacementHandler.resourceGraphs

        assertSame(registry, replacementRegistry)
        assertEquals(graph.id, replacementRegistry.startNavigation(root).id)

        val newGraph = replacementRegistry.startNavigation(root)
        val currentAttempt = requireNotNull(
            replacementRegistry.beginFetch(newGraph, resource, upstream, upstream),
        )
        assertFalse(
            replacementRegistry.markFetchState(
                newGraph,
                oldAttempt,
                resource,
                upstream,
                upstream,
                ResourceState.FAILED,
            ),
        )
        assertTrue(
            replacementRegistry.markFetchState(
                newGraph,
                currentAttempt,
                resource,
                upstream,
                upstream,
                ResourceState.READY,
            ),
        )
        assertEquals(ResourceState.READY, newGraph.snapshot().nodes.single { it.legacyUri == resource }.state)
        firstHandler.close()
        replacementHandler.close()
    }

    @Test
    fun `default PPP handlers share the lazy process registry`() {
        val firstHandler = RetroPppHandler(logger = {})
        val replacementHandler = RetroPppHandler(logger = {})
        try {
            assertSame(YameRegistries.navigationResources(), firstHandler.resourceGraphs)
            assertSame(firstHandler.resourceGraphs, replacementHandler.resourceGraphs)
        } finally {
            firstHandler.close()
            replacementHandler.close()
        }
    }

    @Test
    fun `invalid PPP traffic does not start LCP`() {
        val output = ByteArrayOutputStream()
        val handler = isolatedHandler()
        handler.attachOutput(output)
        handler.connected()

        handler.receive(byteArrayOf(0x7e, 0x01, 0x02, 0x03, 0x7e))

        assertEquals(0, output.size())
    }

    @Test
    fun `modem acknowledges termination before dropping carrier and supports redial`() {
        val output = ByteArrayOutputStream()
        val carrierDrops = mutableListOf<ByteArray>()
        val ended = mutableListOf<PppSessionEnded>()
        val logs = mutableListOf<String>()
        val handler = RetroPppHandler(
            logger = logs::add,
            diagnosticSink = { if (it is PppSessionEnded) ended += it },
            ipConfig = PppIpConfig(Ipv4Cidr.parse("10.0.0.0/30")),
            resourceGraphs = NavigationResourceRegistry(),
        )
        val modem = HayesModem(
            output, 115200,
            config = HayesModemConfig(toneSimulationEnabled = false),
            logger = {},
            pppHandler = handler,
            setCarrierPresent = { present -> if (!present) carrierDrops += output.toByteArray() },
        )
        try {
            modem.receive("ATE0\rATD123\r".toByteArray())
            assertEquals(HayesModem.State.CONNECTED, modem.state)
            assertTrue(output.toString().contains("CONNECT 115200"))
            output.reset()
            modem.receive(peerConfigureRequestWire())
            val firstNegotiation = decode(output.toByteArray())
            assertEquals(2, firstNegotiation.size)
            assertEquals(HayesModem.State.CONNECTED, modem.state)
            completeIpcp(modem, output, firstNegotiation)
            assertEquals(1, logs.count { it.contains("IPCP open:") })

            output.reset()
            carrierDrops.clear()
            val terminate = terminationWire()
            // A repeated termination and another frame in the same read must not restart LCP.
            modem.receive(terminate + terminate + peerConfigureRequestWire())
            val ackWire = carrierDrops.single()
            val ack = requireNotNull(LcpPacket.parse(decode(ackWire).single().payload))
            assertEquals(LcpPacket.TERMINATE_ACK, ack.code)
            assertEquals(42, ack.identifier)
            assertContentEquals(ackWire + "\r\nNO CARRIER\r\n".toByteArray(), output.toByteArray())
            assertEquals(HayesModem.State.COMMAND, modem.state)
            assertEquals(1, ended.size) // Emitted after session proxy shutdown/quiescence.
            output.reset()
            handler.receive(peerConfigureRequestWire())
            assertEquals(0, output.size()) // Released session cannot process traffic.

            output.reset()
            modem.receive(terminate) // Late binary traffic must not leave a partial AT command.
            modem.receive("AT\r".toByteArray())
            assertEquals("\r\nOK\r\n", output.toString()) // Echo remains disabled.
            output.reset()
            modem.receive("ATD123\r".toByteArray())
            assertEquals("\r\nCONNECT 115200\r\n", output.toString())
            output.reset()
            modem.receive(peerConfigureRequestWire())
            val secondNegotiation = decode(output.toByteArray())
            assertEquals(firstNegotiation.map { it.protocol }, secondNegotiation.map { it.protocol })
            firstNegotiation.zip(secondNegotiation).forEach { (first, second) ->
                assertContentEquals(first.payload, second.payload)
            }
            completeIpcp(modem, output, secondNegotiation)
            assertEquals(2, logs.count { it.contains("IPCP open:") })
            assertEquals(1, ended.size)
        } finally {
            modem.close()
        }
        assertEquals(2, ended.size)
        assertTrue(ended[0].session != ended[1].session)
    }

    @Test
    fun `termination as first frame is acknowledged without starting negotiation`() {
        val output = ByteArrayOutputStream()
        val handler = isolatedHandler()
        handler.attachOutput(output)
        try {
            handler.connected()
            assertFalse(handler.receive(PppEncoder().encode(PppFrame(
                PppSession.LCP_PROTOCOL, byteArrayOf(5, 42, 0, 8),
            ))))
            output.reset()
            assertTrue(handler.receive(terminationWire()))
            val ack = requireNotNull(LcpPacket.parse(decode(output.toByteArray()).single().payload))
            assertEquals(LcpPacket.TERMINATE_ACK, ack.code)
            handler.disconnected()
            handler.disconnected()
            output.reset()
            handler.receive(terminationWire())
            assertEquals(0, output.size())
            handler.connected()
            assertFalse(handler.receive(peerConfigureRequestWire()))
            assertEquals(2, decode(output.toByteArray()).size)
        } finally {
            handler.close()
        }
    }

    private fun completeIpcp(modem: HayesModem, output: ByteArrayOutputStream, lcpFrames: List<PppFrame>) {
        fun send(protocol: Int, packet: PppControlPacket) {
            modem.receive(PppEncoder().encode(PppFrame(protocol, packet.encode())))
        }
        val request = lcpFrames.mapNotNull { LcpPacket.parse(it.payload) }
            .single { it.code == LcpPacket.CONFIGURE_REQUEST }
        output.reset()
        send(PppSession.LCP_PROTOCOL, request.copy(code = LcpPacket.CONFIGURE_ACK))
        val localIpcp = decode(output.toByteArray())
            .single { it.protocol == PppSession.IPCP_PROTOCOL }
            .let { requireNotNull(PppControlPacket.parse(it.payload)) }
        assertEquals(PppControlPacket.CONFIGURE_REQUEST, localIpcp.code)
        output.reset()
        val peerIpcp = PppControlPacket(PppControlPacket.CONFIGURE_REQUEST, 9,
            PppControlOption(3, Ipv4Address.parse("10.0.0.2").toByteArray()).encode() +
                PppControlOption(129, Ipv4Address.parse("10.0.0.1").toByteArray()).encode())
        send(PppSession.IPCP_PROTOCOL, peerIpcp)
        val ack = decode(output.toByteArray()).single { it.protocol == PppSession.IPCP_PROTOCOL }
            .let { requireNotNull(PppControlPacket.parse(it.payload)) }
        assertEquals(PppControlPacket.CONFIGURE_ACK, ack.code)
        assertEquals(peerIpcp.identifier, ack.identifier)
        assertContentEquals(peerIpcp.data, ack.data)
        send(PppSession.IPCP_PROTOCOL, localIpcp.copy(code = PppControlPacket.CONFIGURE_ACK))
    }

    private fun terminationWire(): ByteArray = PppEncoder().encode(PppFrame(
        PppSession.LCP_PROTOCOL,
        LcpPacket(LcpPacket.TERMINATE_REQUEST, 42, byteArrayOf()).encode(),
    ))

    private fun peerConfigureRequestWire(): ByteArray =
        PppEncoder().encode(
            PppFrame(
                protocol = PppSession.LCP_PROTOCOL,
                payload = LcpPacket(
                    code = LcpPacket.CONFIGURE_REQUEST,
                    identifier = 0x0b,
                    data = byteArrayOf(
                        0x01, 0x04, 0x02, 0x40,
                        0x02, 0x06, 0x00, 0x00, 0x00, 0x00,
                        0x05, 0x06, 0x00, 0x02, 0x76, 0xd1.toByte(),
                        0x07, 0x02,
                        0x08, 0x02,
                    ),
                ).encode(),
            ),
        )

    private fun decode(wire: ByteArray): List<PppFrame> {
        val frames = mutableListOf<PppFrame>()
        PppFramer(
            onFrame = frames::add,
            receiveAccm = 0u,
        ).receive(wire)
        return frames
    }

    private fun isolatedHandler() =
        RetroPppHandler(
            logger = {},
            resourceGraphs = NavigationResourceRegistry(),
        )
}
