package no.skasti.serialmodem.ppp

import no.skasti.serialmodem.ppp.session.PppSession
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PppEncoderTest {
    @Test
    fun `encoded frame round trips through framer`() {
        val frame = PppFrame(
            protocol = PppSession.LCP_PROTOCOL,
            payload = byteArrayOf(0x01, 0x01, 0x00, 0x04),
        )
        val encoded = PppEncoder().encode(frame)
        val decoded = mutableListOf<PppFrame>()

        PppFramer(decoded::add).receive(encoded)

        assertEquals(1, decoded.size)
        assertEquals(frame.protocol, decoded.single().protocol)
        assertContentEquals(frame.payload, decoded.single().payload)
    }

    @Test
    fun `LCP keeps address and control fields even when compression is negotiated`() {
        val frame = PppFrame(
            protocol = PppSession.LCP_PROTOCOL,
            payload = byteArrayOf(0x01, 0x01, 0x00, 0x04),
        )
        val encoder = PppEncoder(
            transmitAccm = 0u,
            protocolFieldCompression = true,
            addressControlFieldCompression = true,
        )

        val encoded = encoder.encode(frame)

        // Flags are first/last; FF 03 must still be present for LCP.
        assertEquals(0xff, encoded[1].toInt() and 0xff)
        assertEquals(0x03, encoded[2].toInt() and 0xff)
    }

    @Test
    fun `encoder supports negotiated address control and protocol compression`() {
        val frame = PppFrame(
            protocol = 0x0021,
            payload = byteArrayOf(0x01, 0x7d, 0x7e, 0x02),
        )
        val encoder = PppEncoder(
            transmitAccm = 0u,
            protocolFieldCompression = true,
            addressControlFieldCompression = true,
        )
        val encoded = encoder.encode(frame)
        val decoded = mutableListOf<PppFrame>()

        PppFramer(
            onFrame = decoded::add,
            receiveAccm = 0u,
        ).receive(encoded)

        assertEquals(1, decoded.size)
        assertEquals(frame.protocol, decoded.single().protocol)
        assertContentEquals(frame.payload, decoded.single().payload)
    }
}
