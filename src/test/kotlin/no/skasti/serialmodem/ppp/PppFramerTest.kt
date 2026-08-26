package no.skasti.serialmodem.ppp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PppFramerTest {
    private val trumpetLcpFrame = byteArrayOf(
        0x7e,
        0xff.toByte(), 0x7d, 0x23, 0xc0.toByte(), 0x21,
        0x7d, 0x21, 0x7d, 0x30, 0x7d, 0x20, 0x7d, 0x38,
        0x7d, 0x21, 0x7d, 0x24, 0x7d, 0x22, 0x40,
        0x7d, 0x22, 0x7d, 0x26, 0x7d, 0x20, 0x7d, 0x20,
        0x7d, 0x20, 0x7d, 0x20, 0x7d, 0x25, 0x7d, 0x26,
        0x7d, 0x20, 0x7d, 0x29, 0x28, 0x7d, 0x27,
        0x7d, 0x27, 0x7d, 0x22, 0x7d, 0x28, 0x7d, 0x22,
        0x28, 0xfa.toByte(),
        0x7e,
    )

    @Test
    fun `decodes Trumpet Winsock LCP frame byte by byte`() {
        val frames = mutableListOf<PppFrame>()
        val invalid = mutableListOf<ByteArray>()
        val framer = PppFramer(frames::add, invalid::add)

        trumpetLcpFrame.forEach { framer.receive(byteArrayOf(it)) }

        assertEquals(0, invalid.size)
        assertEquals(1, frames.size)
        assertEquals(0xc021, frames.single().protocol)
        assertContentEquals(
            byteArrayOf(
                0x01, 0x10, 0x00, 0x18,
                0x01, 0x04, 0x02, 0x40,
                0x02, 0x06, 0x00, 0x00, 0x00, 0x00,
                0x05, 0x06, 0x00, 0x09, 0x28, 0x07,
                0x07, 0x02,
                0x08, 0x02,
            ),
            frames.single().payload,
        )
    }

    @Test
    fun `decodes multiple frames from one receive call and ignores repeated flags`() {
        val frames = mutableListOf<PppFrame>()
        val framer = PppFramer(frames::add)

        framer.receive(
            byteArrayOf(0x7e, 0x7e) +
                trumpetLcpFrame +
                trumpetLcpFrame,
        )

        assertEquals(2, frames.size)
    }

    @Test
    fun `rejects frame with invalid FCS`() {
        val frames = mutableListOf<PppFrame>()
        val invalid = mutableListOf<ByteArray>()
        val framer = PppFramer(frames::add, invalid::add)
        val corrupted = trumpetLcpFrame.copyOf()
        corrupted[corrupted.lastIndex - 1] = 0x00

        framer.receive(corrupted)

        assertEquals(0, frames.size)
        assertEquals(1, invalid.size)
    }
}
