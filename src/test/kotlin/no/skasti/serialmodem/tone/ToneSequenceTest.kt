package no.skasti.serialmodem.tone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ToneSequenceTest {
    private val sampleRate = 8_000

    @Test
    fun `dial sequence has expected blocking duration`() {
        val samples = ToneSequence.dial(
            number = "12",
            pickupTime = 2.seconds,
            dialToneTime = 500.milliseconds,
            sampleRate = sampleRate,
        )

        // 500 ms dial tone + 95 ms DTMF + 95 ms gap + 95 ms DTMF + 2 s ringback.
        val expectedDuration = 2_785.milliseconds
        val expectedSamples = (expectedDuration.inWholeMilliseconds * sampleRate / 1_000).toInt()
        assertEquals(expectedSamples, samples.size)
    }

    @Test
    fun `ringback is clipped exactly at pickup time`() {
        val samples = ToneSequence.dial(
            number = "1",
            pickupTime = 6.seconds,
            dialToneTime = 0.milliseconds,
            sampleRate = sampleRate,
        )

        // One DTMF digit plus exactly six seconds of ringback cadence.
        val expectedSamples = ((95 + 6_000) * sampleRate / 1_000)
        assertEquals(expectedSamples, samples.size)
    }

    @Test
    fun `supports international numbers made of DTMF digits`() {
        ToneSequence.dial(
            number = "004734576543",
            pickupTime = 2.seconds,
            dialToneTime = 0.milliseconds,
            sampleRate = sampleRate,
        )
    }

    @Test
    fun `rejects unsupported dial characters`() {
        assertFailsWith<IllegalArgumentException> {
            ToneSequence.dial(
                number = "+47 345 76 543",
                pickupTime = 2.seconds,
                dialToneTime = 0.milliseconds,
                sampleRate = sampleRate,
            )
        }
    }
}
