package no.skasti.serialmodem.modem

import no.skasti.serialmodem.tone.HandshakeProfile
import no.skasti.serialmodem.tone.ToneProgress
import no.skasti.serialmodem.tone.ToneStep
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
    fun `dial normalizes number and uses configured tone settings before connecting`() {
        val output = ByteArrayOutputStream()
        val dialed = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val config = HayesModemConfig(
            pickupTime = 3.seconds,
            dialToneTime = 750.milliseconds,
            handshakeProfile = HandshakeProfile.NONE,
        )
        val modem = HayesModem(
            output = output,
            baudRate = 115200,
            config = config,
            toneDialer = { number, pickupTime, dialToneTime, handshakeProfile, onProgress ->
                dialed += number
                assertEquals(3.seconds, pickupTime)
                assertEquals(750.milliseconds, dialToneTime)
                assertEquals(HandshakeProfile.NONE, handshakeProfile)
                onProgress?.invoke(ToneProgress(ToneStep.DIAL_TONE, "test dial tone"))
            },
            logger = logs::add,
        )

        modem.dial("+47 (345) 76-543")

        assertEquals(listOf("004734576543"), dialed)
        assertTrue(logs.contains("TONE [dial_tone] test dial tone"))
        assertTrue(output.toString().contains("CONNECT 115200"))
        assertEquals(HayesModem.State.CONNECTED, modem.state)
    }

    @Test
    fun `audio failure does not prevent connection`() {
        val output = ByteArrayOutputStream()
        val logs = mutableListOf<String>()
        val modem = HayesModem(
            output = output,
            baudRate = 115200,
            toneDialer = { _, _, _, _, _ -> error("no audio device") },
            logger = logs::add,
        )

        modem.receive("ATD1\r".toByteArray())

        assertTrue(logs.any { it.startsWith("AUDIO !! Could not play dialing tones:") })
        assertTrue(output.toString().contains("CONNECT 115200"))
        assertEquals(HayesModem.State.CONNECTED, modem.state)
    }

    @Test
    fun `bytes after connect are passed to data handler`() {
        val output = ByteArrayOutputStream()
        val received = mutableListOf<ByteArray>()
        val modem = HayesModem(
            output = output,
            baudRate = 115200,
            onData = received::add,
            toneDialer = { _, _, _, _, _ -> },
            logger = {},
        )

        modem.receive("ATD1\r".toByteArray())
        modem.receive(byteArrayOf(0x7e, 0xff.toByte(), 0x03, 0xc0.toByte(), 0x21))

        assertEquals(1, received.size)
        assertEquals(listOf(0x7e, 0xff, 0x03, 0xc0, 0x21), received.single().map { it.toInt() and 0xff })
    }
}
