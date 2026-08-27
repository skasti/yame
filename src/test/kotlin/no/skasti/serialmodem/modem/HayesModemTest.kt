package no.skasti.serialmodem.modem

import no.skasti.serialmodem.tone.HandshakeProfile
import no.skasti.serialmodem.tone.TonePlayer
import no.skasti.serialmodem.tone.ToneProgress
import no.skasti.serialmodem.tone.ToneStep
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE
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
        lateinit var modem: HayesModem
        val tonePlayer = FakeTonePlayer { number, pickupTime, dialToneTime, handshakeProfile, onProgress ->
            assertEquals(HayesModem.State.DIALING, modem.state)
            dialed += number
            assertEquals(3.seconds, pickupTime)
            assertEquals(750.milliseconds, dialToneTime)
            assertEquals(HandshakeProfile.NONE, handshakeProfile)
            onProgress?.invoke(ToneProgress(ToneStep.DIAL_TONE, "test dial tone"))
        }
        modem = HayesModem(
            output = output,
            baudRate = 115200,
            config = config,
            tonePlayer = tonePlayer,
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
            tonePlayer = FakeTonePlayer { _, _, _, _, _ -> error("no audio device") },
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
            tonePlayer = FakeTonePlayer(),
            logger = {},
        )

        modem.receive("ATD1\r".toByteArray())
        modem.receive(byteArrayOf(0x7e, 0xff.toByte(), 0x03, 0xc0.toByte(), 0x21))

        assertEquals(1, received.size)
        assertEquals(listOf(0x7e, 0xff, 0x03, 0xc0, 0x21), received.single().map { it.toInt() and 0xff })
    }

    @Test
    fun `modem config rejects infinite tone durations`() {
        assertFailsWith<IllegalArgumentException> {
            HayesModemConfig(pickupTime = INFINITE)
        }
        assertFailsWith<IllegalArgumentException> {
            HayesModemConfig(dialToneTime = INFINITE)
        }
    }

    @Test
    fun `closing modem closes tone player`() {
        val tonePlayer = FakeTonePlayer()
        val modem = HayesModem(
            output = ByteArrayOutputStream(),
            baudRate = 115200,
            tonePlayer = tonePlayer,
            logger = {},
        )

        modem.close()

        assertTrue(tonePlayer.closed)
    }
}

private class FakeTonePlayer(
    private val onDial: (
        String,
        Duration,
        Duration,
        HandshakeProfile,
        ((ToneProgress) -> Unit)?,
    ) -> Unit = { _, _, _, _, _ -> },
) : TonePlayer {
    var closed = false
        private set

    override fun dial(
        number: String,
        pickupTime: Duration,
        dialToneTime: Duration,
        handshakeProfile: HandshakeProfile,
        onProgress: ((ToneProgress) -> Unit)?,
    ) {
        onDial(number, pickupTime, dialToneTime, handshakeProfile, onProgress)
    }

    override fun close() {
        closed = true
    }
}
