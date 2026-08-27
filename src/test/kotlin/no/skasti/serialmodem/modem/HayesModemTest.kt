package no.skasti.serialmodem.modem

import no.skasti.serialmodem.ppp.PppHandler
import no.skasti.serialmodem.tone.HandshakeProfile
import no.skasti.serialmodem.tone.TonePlayer
import no.skasti.serialmodem.tone.ToneProgress
import no.skasti.serialmodem.tone.ToneStep
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `garbage before AT command is ignored without ERROR response`() {
        val output = ByteArrayOutputStream()
        val logs = mutableListOf<String>()
        val modem = HayesModem(output, 115200, logger = logs::add)

        modem.receive("~~\rAT\r".toByteArray())

        assertTrue(output.toString().contains("OK"))
        assertFalse(output.toString().contains("ERROR"))
        assertEquals(listOf("AT <= AT", "AT => OK"), logs)
    }

    @Test
    fun `PPP-like garbage can be immediately followed by modem reset`() {
        val output = ByteArrayOutputStream()
        val logs = mutableListOf<String>()
        val modem = HayesModem(output, 115200, logger = logs::add)
        val pppLikeGarbage = byteArrayOf(
            0x7e,
            0x7e,
            0x7d,
            0x23,
            0x21,
            0x7d,
            0x21,
            0x7e,
        )

        modem.receive(pppLikeGarbage)
        modem.receive("a".toByteArray())
        modem.receive("tz\r".toByteArray())

        assertTrue(output.toString().contains("atz"))
        assertTrue(output.toString().contains("OK"))
        assertFalse(output.toString().contains("ERROR"))
        assertEquals(listOf("AT <= atz", "AT => OK"), logs)
    }

    @Test
    fun `invalid framing discards a false AT candidate before the real command`() {
        val output = ByteArrayOutputStream()
        val logs = mutableListOf<String>()
        val modem = HayesModem(output, 115200, logger = logs::add)

        modem.receive("AT~".toByteArray())
        modem.receive("ATZ\r".toByteArray())

        assertTrue(output.toString().contains("OK"))
        assertFalse(output.toString().contains("ERROR"))
        assertEquals(listOf("AT <= ATZ", "AT => OK"), logs)
    }

    @Test
    fun `printable false AT candidate resynchronizes on later real command`() {
        val output = ByteArrayOutputStream()
        val logs = mutableListOf<String>()
        val modem = HayesModem(output, 115200, logger = logs::add)

        modem.receive("ATjunkATZ\r".toByteArray())

        assertFalse(output.toString().contains("ERROR"))
        assertEquals(listOf("AT <= ATZ", "AT => OK"), logs)
    }

    @Test
    fun `implausible ATD garbage resynchronizes on later real command`() {
        val output = ByteArrayOutputStream()
        val logs = mutableListOf<String>()
        val modem = HayesModem(output, 115200, logger = logs::add)

        modem.receive("ATDjunkATZ\r".toByteArray())

        assertFalse(output.toString().contains("CONNECT"))
        assertFalse(output.toString().contains("ERROR"))
        assertEquals(listOf("AT <= ATZ", "AT => OK"), logs)
        assertEquals(HayesModem.State.COMMAND, modem.state)
    }

    @Test
    fun `embedded AT sequence in dial string does not trigger resynchronization`() {
        val output = ByteArrayOutputStream()
        val dialed = mutableListOf<String>()
        val modem = HayesModem(
            output = output,
            baudRate = 115200,
            tonePlayer = FakeTonePlayer { number, _, _, _, _ -> dialed += number },
            logger = {},
        )

        modem.receive("ATDAT123\r".toByteArray())

        assertEquals(listOf("A123"), dialed)
        assertTrue(output.toString().contains("CONNECT 115200"))
        assertEquals(HayesModem.State.LOGIN, modem.state)
    }

    @Test
    fun `garbage without AT prefix produces no modem response`() {
        val output = ByteArrayOutputStream()
        val logs = mutableListOf<String>()
        val modem = HayesModem(output, 115200, logger = logs::add)

        modem.receive("~~ random garbage }#!\r".toByteArray())

        assertEquals("", output.toString())
        assertTrue(logs.isEmpty())
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
        assertEquals(HayesModem.State.LOGIN, modem.state)
    }

    @Test
    fun `attaching carrier callback initializes carrier as absent`() {
        val carrierStates = mutableListOf<Boolean>()
        val modem = HayesModem(
            baudRate = 115200,
            tonePlayer = FakeTonePlayer(),
            logger = {},
        )

        modem.attachCarrierPresent(carrierStates::add)

        assertEquals(listOf(false), carrierStates)
    }

    @Test
    fun `modem can attach serial output after construction`() {
        val output = ByteArrayOutputStream()
        val modem = HayesModem(
            baudRate = 115200,
            tonePlayer = FakeTonePlayer(),
            logger = {},
        )

        modem.attachOutput(output)
        modem.receive("AT\r".toByteArray())

        assertTrue(output.toString().contains("OK"))
        assertEquals(HayesModem.State.COMMAND, modem.state)
    }

    @Test
    fun `public dial works without attached serial output`() {
        val modem = HayesModem(
            baudRate = 115200,
            tonePlayer = FakeTonePlayer(),
            logger = {},
        )

        modem.dial("1")

        assertEquals(HayesModem.State.CONNECTED, modem.state)
    }

    @Test
    fun `public dial does not propagate tone player failure`() {
        val modem = HayesModem(
            baudRate = 115200,
            tonePlayer = FakeTonePlayer { _, _, _, _, _ -> error("no audio device") },
            logger = {},
        )

        modem.dial("1")

        assertEquals(HayesModem.State.CONNECTED, modem.state)
    }

    @Test
    fun `audio failure does not prevent AT dial connection`() {
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
        assertEquals(HayesModem.State.LOGIN, modem.state)
    }

    @Test
    fun `connected bytes are passed to PPP handler`() {
        val output = ByteArrayOutputStream()
        val pppHandler = FakePppHandler()
        val modem = HayesModem(
            output = output,
            baudRate = 115200,
            tonePlayer = FakeTonePlayer(),
            logger = {},
            pppHandler = pppHandler,
        )

        modem.receive("ATD1\r".toByteArray())
        modem.receive(byteArrayOf(0x7e, 0xff.toByte(), 0x03, 0xc0.toByte(), 0x21))

        assertEquals(1, pppHandler.connectedCalls)
        assertEquals(1, pppHandler.received.size)
        assertEquals(
            listOf(0x7e, 0xff, 0x03, 0xc0, 0x21),
            pppHandler.received.single().map { it.toInt() and 0xff },
        )
    }

    @Test
    fun `PPP handler starts after CONNECT when direct PPP framing is detected`() {
        val output = ByteArrayOutputStream()
        var outputAtConnect = ""
        val pppHandler = FakePppHandler(
            onConnected = { outputAtConnect = output.toString() },
        )
        val modem = HayesModem(
            output = output,
            baudRate = 115200,
            tonePlayer = FakeTonePlayer(),
            logger = {},
            pppHandler = pppHandler,
        )

        modem.dial("1")
        assertEquals(0, pppHandler.connectedCalls)

        modem.receive(byteArrayOf(0x7e))

        assertTrue(outputAtConnect.contains("CONNECT 115200"))
        assertEquals(1, pppHandler.connectedCalls)
        assertEquals(HayesModem.State.CONNECTED, modem.state)
    }

    @Test
    fun `Trumpet terminal login transitions to PPP after ppp command`() {
        val output = ByteArrayOutputStream()
        val logs = mutableListOf<String>()
        val pppHandler = FakePppHandler()
        val modem = HayesModem(
            output = output,
            baudRate = 9600,
            tonePlayer = FakeTonePlayer(),
            logger = logs::add,
            pppHandler = pppHandler,
        )

        modem.receive("ATD123\r".toByteArray())
        assertEquals(HayesModem.State.LOGIN, modem.state)
        assertTrue(output.toString().contains("CONNECT 9600"))

        modem.receive("\r".toByteArray())
        assertTrue(output.toString().contains("Login: Username:"))

        modem.receive("trumpet-user\r".toByteArray())
        assertTrue(output.toString().contains("Password:"))

        modem.receive("secret-password\r".toByteArray())
        assertTrue(output.toString().contains(">"))
        assertFalse(logs.any { it.contains("secret-password") })

        modem.receive("ppp\r".toByteArray())

        assertTrue(output.toString().contains("PPP."))
        assertEquals(1, pppHandler.connectedCalls)
        assertEquals(HayesModem.State.CONNECTED, modem.state)
    }

    @Test
    fun `PPP bytes following ppp command in same read are forwarded`() {
        val output = ByteArrayOutputStream()
        val pppHandler = FakePppHandler()
        val modem = HayesModem(
            output = output,
            baudRate = 115200,
            tonePlayer = FakeTonePlayer(),
            logger = {},
            pppHandler = pppHandler,
        )

        modem.receive("ATD1\r".toByteArray())
        modem.receive("\ruser\rpassword\r".toByteArray())

        val pppBytes = byteArrayOf(0x7e, 0xff.toByte(), 0x03, 0xc0.toByte(), 0x21)
        modem.receive("ppp\r".toByteArray() + pppBytes)

        assertEquals(HayesModem.State.CONNECTED, modem.state)
        assertEquals(1, pppHandler.connectedCalls)
        assertEquals(1, pppHandler.received.size)
        assertEquals(
            listOf(0x7e, 0xff, 0x03, 0xc0, 0x21),
            pppHandler.received.single().map { it.toInt() and 0xff },
        )
    }

    @Test
    fun `PPP bytes immediately after CONNECT skip terminal login`() {
        val output = ByteArrayOutputStream()
        val pppHandler = FakePppHandler()
        val modem = HayesModem(
            output = output,
            baudRate = 115200,
            tonePlayer = FakeTonePlayer(),
            logger = {},
            pppHandler = pppHandler,
        )

        modem.receive("ATD1\r".toByteArray())
        modem.receive(byteArrayOf(0x7e, 0xff.toByte(), 0x03, 0xc0.toByte(), 0x21))

        assertEquals(HayesModem.State.CONNECTED, modem.state)
        assertEquals(1, pppHandler.connectedCalls)
        assertEquals(1, pppHandler.received.size)
        assertEquals(
            listOf(0x7e, 0xff, 0x03, 0xc0, 0x21),
            pppHandler.received.single().map { it.toInt() and 0xff },
        )
        assertFalse(output.toString().contains("Username:"))
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
    fun `modem config accepts ten second tone durations`() {
        HayesModemConfig(
            pickupTime = 10.seconds,
            dialToneTime = 10.seconds,
        )
    }

    @Test
    fun `modem config rejects tone durations over ten seconds`() {
        assertFailsWith<IllegalArgumentException> {
            HayesModemConfig(pickupTime = 10_001.milliseconds)
        }
        assertFailsWith<IllegalArgumentException> {
            HayesModemConfig(dialToneTime = 10_001.milliseconds)
        }
    }

    @Test
    fun `closing modem closes tone player and PPP handler`() {
        val tonePlayer = FakeTonePlayer()
        val pppHandler = FakePppHandler()
        val modem = HayesModem(
            output = ByteArrayOutputStream(),
            baudRate = 115200,
            tonePlayer = tonePlayer,
            logger = {},
            pppHandler = pppHandler,
        )

        modem.close()

        assertTrue(tonePlayer.closed)
        assertTrue(pppHandler.closed)
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


private class FakePppHandler(
    private val onConnected: () -> Unit = {},
) : PppHandler {
    var attachedOutput: OutputStream? = null
        private set

    var connectedCalls = 0
        private set

    val received = mutableListOf<ByteArray>()

    var closed = false
        private set

    override fun attachOutput(output: OutputStream) {
        attachedOutput = output
    }

    override fun connected() {
        connectedCalls++
        onConnected()
    }

    override fun receive(bytes: ByteArray) {
        received += bytes
    }

    override fun close() {
        closed = true
    }
}
