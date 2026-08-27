package no.skasti.serialmodem.ppp

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RetroPppHandlerTest {
    @Test
    fun `connected starts LCP and incoming configure request is acknowledged`() {
        val output = ByteArrayOutputStream()
        val handler = RetroPppHandler(logger = {})
        handler.attachOutput(output)

        handler.connected()

        val initialFrames = decode(output.toByteArray())
        assertEquals(1, initialFrames.size)

        val localRequest = requireNotNull(LcpPacket.parse(initialFrames.single().payload))
        assertEquals(LcpPacket.CONFIGURE_REQUEST, localRequest.code)

        output.reset()

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
        assertEquals(1, replies.size)

        val ack = requireNotNull(LcpPacket.parse(replies.single().payload))
        assertEquals(LcpPacket.CONFIGURE_ACK, ack.code)
        assertEquals(peerRequest.identifier, ack.identifier)
        assertContentEquals(peerRequest.data, ack.data)
    }

    @Test
    fun `each modem connection starts a fresh PPP session`() {
        val output = ByteArrayOutputStream()
        val handler = RetroPppHandler(logger = {})
        handler.attachOutput(output)

        handler.connected()
        val firstRequest = requireNotNull(LcpPacket.parse(decode(output.toByteArray()).single().payload))

        output.reset()
        handler.connected()
        val secondRequest = requireNotNull(LcpPacket.parse(decode(output.toByteArray()).single().payload))

        assertEquals(LcpPacket.CONFIGURE_REQUEST, firstRequest.code)
        assertEquals(LcpPacket.CONFIGURE_REQUEST, secondRequest.code)
        assertEquals(firstRequest.identifier, secondRequest.identifier)
        assertContentEquals(firstRequest.data, secondRequest.data)
    }

    private fun decode(wire: ByteArray): List<PppFrame> {
        val frames = mutableListOf<PppFrame>()
        PppFramer(frames::add).receive(wire)
        return frames
    }
}
