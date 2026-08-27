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

    @Test
    fun `escape followed by flag aborts partial frame and resynchronizes`() {
        val frames = mutableListOf<PppFrame>()
        val invalid = mutableListOf<ByteArray>()
        val framer = PppFramer(frames::add, invalid::add)

        framer.receive(byteArrayOf(0x7e, 0xff.toByte(), 0x7d) + trumpetLcpFrame)

        assertEquals(0, invalid.size)
        assertEquals(1, frames.size)
        assertEquals(0xc021, frames.single().protocol)
    }

    @Test
    fun `default receive ACCM discards unescaped mapped control characters before FCS`() {
        val frames = mutableListOf<PppFrame>()
        val invalid = mutableListOf<ByteArray>()
        val framer = PppFramer(frames::add, invalid::add)
        val withInsertedXon =
            trumpetLcpFrame.copyOfRange(0, 4) +
                byteArrayOf(0x11) +
                trumpetLcpFrame.copyOfRange(4, trumpetLcpFrame.size)

        framer.receive(withInsertedXon)

        assertEquals(0, invalid.size)
        assertEquals(1, frames.size)
        assertEquals(0xc021, frames.single().protocol)
    }

    @Test
    fun `mapped control byte inside escape sequence is discarded without consuming escape`() {
        val frames = mutableListOf<PppFrame>()
        val invalid = mutableListOf<ByteArray>()
        val framer = PppFramer(frames::add, invalid::add)
        val withInsertedXon =
            trumpetLcpFrame.copyOfRange(0, 3) +
                byteArrayOf(0x11) +
                trumpetLcpFrame.copyOfRange(3, trumpetLcpFrame.size)

        framer.receive(withInsertedXon)

        assertEquals(0, invalid.size)
        assertEquals(1, frames.size)
        assertEquals(0xc021, frames.single().protocol)
    }

    @Test
    fun `three byte frame is invalid even with a compressed protocol field`() {
        val frames = mutableListOf<PppFrame>()
        val invalid = mutableListOf<ByteArray>()
        val framer = PppFramer(frames::add, invalid::add, receiveAccm = 0u)

        // Protocol 0x21 plus a valid 16-bit FCS. RFC 1662 section 4.3 still
        // requires at least four octets between flags.
        framer.receive(
            byteArrayOf(
                0x7e,
                0x21,
                0xf3.toByte(),
                0xc0.toByte(),
                0x7e,
            ),
        )

        assertEquals(0, frames.size)
        assertEquals(1, invalid.size)
    }

    @Test
    fun `oversized partial frame is discarded and next flag resynchronizes`() {
        val frames = mutableListOf<PppFrame>()
        val framer = PppFramer(frames::add)

        framer.receive(
            byteArrayOf(0x7e) +
                ByteArray(PppFramer.MAX_DECODED_FRAME_SIZE + 1) { 0x41 } +
                trumpetLcpFrame,
        )

        assertEquals(1, frames.size)
        assertEquals(0xc021, frames.single().protocol)
    }
}
