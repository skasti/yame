package no.skasti.serialmodem.tone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ToneSequenceTest {
    private val sampleRate = 8_000

    @Test
    fun `dial sequence without handshake has expected blocking duration`() {
        val samples = ToneSequence.dial(
            number = "12",
            pickupTime = 2.seconds,
            dialToneTime = 500.milliseconds,
            sampleRate = sampleRate,
            handshakeProfile = HandshakeProfile.NONE,
        )

        // 500 ms dial tone + 95 ms DTMF + 95 ms gap + 95 ms DTMF + 2 s ringback.
        val expectedDuration = 2_785.milliseconds
        val expectedSamples = (expectedDuration.inWholeMilliseconds * sampleRate / 1_000).toInt()
        assertEquals(expectedSamples, samples.size)
    }

    @Test
    fun `default dial sequence continues through v34 handshake after pickup`() {
        val samples = ToneSequence.dial(
            number = "1",
            pickupTime = 2.seconds,
            dialToneTime = 500.milliseconds,
            sampleRate = sampleRate,
        )

        val expectedDuration = 500.milliseconds + 95.milliseconds + 2.seconds + ModemHandshakeSequence.v34Duration
        val expectedSamples = (expectedDuration.inWholeMilliseconds * sampleRate / 1_000).toInt()
        assertEquals(expectedSamples, samples.size)
    }

    @Test
    fun `v34 dial plan exposes progress markers in playback order`() {
        val plan = ToneSequence.dialPlan(
            number = "12",
            pickupTime = 2.seconds,
            dialToneTime = 500.milliseconds,
            sampleRate = sampleRate,
        )

        assertEquals(
            listOf(
                ToneStep.DIAL_TONE,
                ToneStep.DTMF_DIALING,
                ToneStep.RINGBACK,
                ToneStep.REMOTE_ANSWERED,
                ToneStep.V8_ANSAM,
                ToneStep.V8_NEGOTIATION,
                ToneStep.V34_PHASE2,
                ToneStep.V34_LINE_PROBE_L1,
                ToneStep.V34_LINE_PROBE_L2,
                ToneStep.V34_TRAINING,
                ToneStep.V34_FINAL_EXCHANGE,
                ToneStep.COMPLETE,
            ),
            plan.progress.map { it.progress.step },
        )
        assertTrue(plan.progress.zipWithNext().all { (first, second) -> first.sampleOffset <= second.sampleOffset })
        assertEquals(plan.samples.size, plan.progress.last().sampleOffset)
    }

    @Test
    fun `v34 handshake contains audible signal`() {
        val samples = ModemHandshakeSequence.v34(sampleRate)

        assertEquals(
            (ModemHandshakeSequence.v34Duration.inWholeMilliseconds * sampleRate / 1_000).toInt(),
            samples.size,
        )
        assertTrue(samples.any { it != 0.toShort() })
    }

    @Test
    fun `v34 handshake waveform is deterministic`() {
        val first = ModemHandshakeSequence.v34(sampleRate)
        val second = ModemHandshakeSequence.v34(sampleRate)

        assertEquals(first.toList(), second.toList())
    }

    @Test
    fun `ringback is clipped exactly at pickup time`() {
        val samples = ToneSequence.dial(
            number = "1",
            pickupTime = 6.seconds,
            dialToneTime = 0.milliseconds,
            sampleRate = sampleRate,
            handshakeProfile = HandshakeProfile.NONE,
        )

        // One DTMF digit plus exactly six seconds of ringback cadence.
        val expectedSamples = ((95 + 6_000) * sampleRate / 1_000)
        assertEquals(expectedSamples, samples.size)
    }

    @Test
    fun `converts international plus notation to norwegian access prefix`() {
        assertEquals("004734576543", DialString.normalize("+47 345-76-543"))
    }

    @Test
    fun `removes hayes dial mode and presentation characters`() {
        assertEquals("004734576543", DialString.normalize("T +47 (345) 76-543"))
    }

    @Test
    fun `tone sequence accepts formatted international numbers`() {
        val formatted = ToneSequence.dial(
            number = "+47 345 76 543",
            pickupTime = 2.seconds,
            dialToneTime = 0.milliseconds,
            sampleRate = sampleRate,
            handshakeProfile = HandshakeProfile.NONE,
        )
        val normalized = ToneSequence.dial(
            number = "004734576543",
            pickupTime = 2.seconds,
            dialToneTime = 0.milliseconds,
            sampleRate = sampleRate,
            handshakeProfile = HandshakeProfile.NONE,
        )

        assertEquals(normalized.toList(), formatted.toList())
    }
}
