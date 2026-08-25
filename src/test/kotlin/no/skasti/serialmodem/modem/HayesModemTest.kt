package no.skasti.serialmodem.modem

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HayesModemTest {
    @Test
    fun `AT returns OK`() {
        val output = ByteArrayOutputStream()
        val modem = HayesModem(output, 115200, logger = {})

        modem.receive("AT\r".toByteArray())

        assertTrue(output.toString().contains("OK"))
        assertEquals(HayesModem.State.COMMAND, modem.state)
    }

    @Test
    fun `dial command enters connected mode`() {
        val output = ByteArrayOutputStream()
        val modem = HayesModem(output, 115200, logger = {})

        modem.receive("ATDT5551234\r".toByteArray())

        assertTrue(output.toString().contains("CONNECT 115200"))
        assertEquals(HayesModem.State.CONNECTED, modem.state)
    }

    @Test
    fun `bytes after connect are passed to data handler`() {
        val output = ByteArrayOutputStream()
        val received = mutableListOf<ByteArray>()
        val modem = HayesModem(output, 115200, onData = received::add, logger = {})

        modem.receive("ATD1\r".toByteArray())
        modem.receive(byteArrayOf(0x7e, 0xff.toByte(), 0x03, 0xc0.toByte(), 0x21))

        assertEquals(1, received.size)
        assertEquals(listOf(0x7e, 0xff, 0x03, 0xc0, 0x21), received.single().map { it.toInt() and 0xff })
    }
}
