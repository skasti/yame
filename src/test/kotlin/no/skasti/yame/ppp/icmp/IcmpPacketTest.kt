package no.skasti.yame.ppp.icmp
import no.skasti.yame.ppp.icmp.IcmpPacket
import no.skasti.yame.ppp.ip.internetChecksum
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IcmpPacketTest {
    @Test
    fun `ICMP echo request parses and produces checksum-valid reply`() {
        val body = byteArrayOf(0x12, 0x34, 0x00, 0x07, 0x55, 0x55, 0x55)
        val requestBytes = IcmpPacket(
            type = IcmpPacket.ECHO_REQUEST,
            code = 0,
            body = body,
        ).encode()

        assertEquals(0, internetChecksum(requestBytes))

        val request = requireNotNull(IcmpPacket.parse(requestBytes))
        assertEquals(0x1234, request.echoIdentifier())
        assertEquals(7, request.echoSequence())

        val reply = requireNotNull(request.toEchoReply())
        val replyBytes = reply.encode()
        assertEquals(0, internetChecksum(replyBytes))

        val parsedReply = requireNotNull(IcmpPacket.parse(replyBytes))
        assertEquals(IcmpPacket.ECHO_REPLY, parsedReply.type)
        assertEquals(0, parsedReply.code)
        assertContentEquals(body, parsedReply.body)
    }

    @Test
    fun `ICMP parser rejects an invalid checksum`() {
        val encoded = IcmpPacket(
            type = IcmpPacket.ECHO_REQUEST,
            code = 0,
            body = byteArrayOf(0, 1, 0, 2, 0x55),
        ).encode()

        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()

        assertNull(IcmpPacket.parse(encoded))
    }
}
