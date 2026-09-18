package no.skasti.yame.ppp

import no.skasti.yame.ppp.lcp.LcpPacket
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry

import no.skasti.yame.ppp.session.PppSession
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RetroPppHandlerTest {
    @Test
    fun `connected waits for peer PPP traffic before starting LCP`() {
        val output = ByteArrayOutputStream()
        val handler = RetroPppHandler(logger = {})
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
        val handler = RetroPppHandler(logger = {})
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
    fun `invalid PPP traffic does not start LCP`() {
        val output = ByteArrayOutputStream()
        val handler = RetroPppHandler(logger = {})
        handler.attachOutput(output)
        handler.connected()

        handler.receive(byteArrayOf(0x7e, 0x01, 0x02, 0x03, 0x7e))

        assertEquals(0, output.size())
    }

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
}
