package no.skasti.serialmodem.tone

import java.io.Closeable
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Selects the audible modem handshake played after the remote side picks up.
 *
 * V34 is a standards-derived audio simulation. It uses real V.8/V.21/V.34
 * carrier/probing frequencies and timings where practical, but it does not
 * encode a valid modem negotiation payload.
 */
enum class HandshakeProfile {
    NONE,
    V34,
}

/**
 * Blocking telephone-tone playback for simulated dial-up calls.
 *
 * The default sequence models a Norwegian PSTN call followed by a V.34-style
 * modem handshake:
 * - 425 Hz dial tone
 * - standard DTMF frequency pairs
 * - 95 ms DTMF tone / 95 ms inter-digit pause (classic Hayes S11 default)
 * - 425 Hz ringback, 1 s on / 4 s off
 * - V.8/V.34-style answer, negotiation, probing and training sounds
 */
object tone {
    fun dial(
        number: String,
        pickupTime: Duration,
        dialToneTime: Duration = 500.milliseconds,
        handshakeProfile: HandshakeProfile = HandshakeProfile.V34,
    ) {
        JavaSoundTonePlayer().use { player ->
            player.dial(number, pickupTime, dialToneTime, handshakeProfile)
        }
    }
}

class JavaSoundTonePlayer(
    private val sampleRate: Int = 44_100,
) : Closeable {
    private val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(format).apply {
        open(format)
        start()
    }

    fun dial(
        number: String,
        pickupTime: Duration,
        dialToneTime: Duration = 500.milliseconds,
        handshakeProfile: HandshakeProfile = HandshakeProfile.V34,
    ) {
        require(!pickupTime.isNegative()) { "pickupTime must not be negative" }
        require(!dialToneTime.isNegative()) { "dialToneTime must not be negative" }

        play(ToneSequence.dial(number, pickupTime, dialToneTime, sampleRate, handshakeProfile))
        line.drain()
    }

    private fun play(samples: ShortArray) {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xff).toByte()
            bytes[index * 2 + 1] = ((sample.toInt() ushr 8) and 0xff).toByte()
        }

        var offset = 0
        while (offset < bytes.size) {
            offset += line.write(bytes, offset, bytes.size - offset)
        }
    }

    override fun close() {
        line.stop()
        line.close()
    }
}

internal object ToneSequence {
    private val dtmf = mapOf(
        '1' to (697.0 to 1209.0), '2' to (697.0 to 1336.0), '3' to (697.0 to 1477.0), 'A' to (697.0 to 1633.0),
        '4' to (770.0 to 1209.0), '5' to (770.0 to 1336.0), '6' to (770.0 to 1477.0), 'B' to (770.0 to 1633.0),
        '7' to (852.0 to 1209.0), '8' to (852.0 to 1336.0), '9' to (852.0 to 1477.0), 'C' to (852.0 to 1633.0),
        '*' to (941.0 to 1209.0), '0' to (941.0 to 1336.0), '#' to (941.0 to 1477.0), 'D' to (941.0 to 1633.0),
    )

    private val dtmfTone = 95.milliseconds
    private val dtmfPause = 95.milliseconds
    private val ringOn = 1.seconds
    private val ringOff = 4.seconds

    fun dial(
        number: String,
        pickupTime: Duration,
        dialToneTime: Duration,
        sampleRate: Int,
        handshakeProfile: HandshakeProfile = HandshakeProfile.V34,
    ): ShortArray {
        val normalizedNumber = DialString.normalize(number)
        require(normalizedNumber.isNotEmpty()) { "number must contain at least one DTMF digit" }

        val output = ShortArrayBuilder()
        output.append(tone(425.0, dialToneTime, sampleRate, amplitude = 0.20))

        normalizedNumber.forEachIndexed { index, digit ->
            val (low, high) = dtmf.getValue(digit)
            output.append(dualTone(low, high, dtmfTone, sampleRate, amplitude = 0.16))
            if (index != normalizedNumber.lastIndex) {
                output.append(silence(dtmfPause, sampleRate))
            }
        }

        var remaining = pickupTime
        while (remaining > Duration.ZERO) {
            val on = minOf(ringOn, remaining)
            output.append(tone(425.0, on, sampleRate, amplitude = 0.20))
            remaining -= on
            if (remaining <= Duration.ZERO) break

            val off = minOf(ringOff, remaining)
            output.append(silence(off, sampleRate))
            remaining -= off
        }

        when (handshakeProfile) {
            HandshakeProfile.NONE -> Unit
            HandshakeProfile.V34 -> output.append(ModemHandshakeSequence.v34(sampleRate))
        }

        return output.toArray()
    }

    private fun tone(
        frequency: Double,
        duration: Duration,
        sampleRate: Int,
        amplitude: Double,
    ): ShortArray = synthesize(duration, sampleRate) { time ->
        amplitude * sin(2.0 * PI * frequency * time)
    }

    private fun dualTone(
        low: Double,
        high: Double,
        duration: Duration,
        sampleRate: Int,
        amplitude: Double,
    ): ShortArray = synthesize(duration, sampleRate) { time ->
        amplitude * (sin(2.0 * PI * low * time) + sin(2.0 * PI * high * time))
    }

    private fun silence(duration: Duration, sampleRate: Int): ShortArray =
        ShortArray(sampleCount(duration, sampleRate))
}

/**
 * Auditory simulation of the recognizable V.8/V.34 call setup sequence.
 *
 * The carrier and probing frequencies are standards-derived. The menu/training
 * payload itself is intentionally synthetic: the emulator only needs the sound
 * and timing here, not a decodable modem waveform.
 */
internal object ModemHandshakeSequence {
    val v34Duration: Duration = 6_160.milliseconds

    private val probeFrequencies = doubleArrayOf(
        150.0, 300.0, 450.0, 600.0, 750.0,
        1050.0, 1350.0, 1500.0, 1650.0, 1950.0, 2100.0,
        2250.0, 2550.0, 2700.0, 2850.0, 3000.0, 3150.0,
        3300.0, 3450.0, 3600.0, 3750.0,
    )

    private val probePhases = doubleArrayOf(
        0.0, PI, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, PI, 0.0, 0.0,
        PI, 0.0, PI, 0.0, PI, PI,
        PI, PI, 0.0, 0.0,
    )

    fun v34(sampleRate: Int): ShortArray {
        val output = ShortArrayBuilder()

        // V.8 ANSam: 2100 Hz, 15 Hz amplitude modulation and 180-degree
        // phase reversals every 450 ms. Kept shorter than the maximum answer
        // tone because the audible simulation proceeds directly into CM/JM.
        output.append(ansam(1_800.milliseconds, sampleRate))

        // Simulated V.8 CM/JM exchange using the real V.21 low/high-band FSK
        // frequencies (980/1180 and 1650/1850 Hz) at a 300-symbol/s cadence.
        output.append(v21Menu(900.milliseconds, sampleRate))

        // V.34 phase 2: answer-modem 2400 Hz carrier with 1800 Hz guard tone,
        // together with the calling modem's 1200 Hz carrier and phase changes.
        output.append(v34Phase2(900.milliseconds, sampleRate))

        // V.34 L1/L2 line probing. These use the specified 150 Hz-spaced tone
        // set from 150 to 3750 Hz, with 900/1200/1800/2400 Hz omitted.
        output.append(lineProbe(160.milliseconds, sampleRate, amplitude = 0.22))
        output.append(lineProbe(500.milliseconds, sampleRate, amplitude = 0.12))

        // Synthetic training/parameter exchange. This intentionally does not
        // encode valid V.34 TRN/MP data, but produces the familiar whistles,
        // warbles and dense carrier sound heard while equalizers converge.
        output.append(training(1_400.milliseconds, sampleRate))
        output.append(finalLock(500.milliseconds, sampleRate))

        return output.toArray()
    }

    private fun ansam(duration: Duration, sampleRate: Int): ShortArray =
        synthesize(duration, sampleRate) { time ->
            val phaseReversals = floor(time / 0.450).toInt()
            val phase = if (phaseReversals % 2 == 0) 0.0 else PI
            val envelope = 1.0 + 0.20 * sin(2.0 * PI * 15.0 * time)
            0.16 * envelope * sin(2.0 * PI * 2100.0 * time + phase)
        }

    private fun v21Menu(duration: Duration, sampleRate: Int): ShortArray =
        synthesize(duration, sampleRate) { time ->
            val symbol = floor(time * 300.0).toInt()
            val callerFrequency = if (((symbol * 7 + 3) % 11) < 5) 980.0 else 1180.0
            val answerFrequency = if (((symbol * 5 + 1) % 13) < 6) 1650.0 else 1850.0
            0.08 * sin(2.0 * PI * callerFrequency * time) +
                0.08 * sin(2.0 * PI * answerFrequency * time)
        }

    private fun v34Phase2(duration: Duration, sampleRate: Int): ShortArray =
        synthesize(duration, sampleRate) { time ->
            val phaseBlock = floor(time / 0.120).toInt()
            val callPhase = if (phaseBlock % 2 == 0) 0.0 else PI
            val answerPhase = if ((phaseBlock / 2) % 2 == 0) 0.0 else PI

            0.07 * sin(2.0 * PI * 1200.0 * time + callPhase) +
                0.07 * sin(2.0 * PI * 2400.0 * time + answerPhase) +
                0.035 * sin(2.0 * PI * 1800.0 * time)
        }

    private fun lineProbe(
        duration: Duration,
        sampleRate: Int,
        amplitude: Double,
    ): ShortArray = synthesize(duration, sampleRate) { time ->
        var sample = 0.0
        for (i in probeFrequencies.indices) {
            sample += sin(2.0 * PI * probeFrequencies[i] * time + probePhases[i])
        }
        amplitude * sample / probeFrequencies.size
    }

    private fun training(duration: Duration, sampleRate: Int): ShortArray =
        synthesize(duration, sampleRate) { time ->
            val segment = floor(time / 0.070).toInt()
            val hop = doubleArrayOf(600.0, 900.0, 1200.0, 1500.0, 1800.0, 2100.0, 2400.0, 2700.0, 3000.0)
            val frequency = hop[(segment * 5 + 2) % hop.size]
            val chirp = 900.0 + (time % 0.350) / 0.350 * 1900.0
            val phase = (segment % 4) * PI / 2.0

            0.095 * sin(2.0 * PI * frequency * time + phase) +
                0.055 * sin(2.0 * PI * chirp * time)
        }

    private fun finalLock(duration: Duration, sampleRate: Int): ShortArray =
        synthesize(duration, sampleRate) { time ->
            val symbol = floor(time * 600.0).toInt()
            val phase = ((symbol * 3) % 4) * PI / 2.0
            0.10 * sin(2.0 * PI * 1800.0 * time + phase) +
                0.035 * sin(2.0 * PI * 2400.0 * time)
        }
}

private fun synthesize(
    duration: Duration,
    sampleRate: Int,
    wave: (Double) -> Double,
): ShortArray {
    val count = sampleCount(duration, sampleRate)
    return ShortArray(count) { i ->
        (wave(i.toDouble() / sampleRate) * Short.MAX_VALUE).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}

private fun sampleCount(duration: Duration, sampleRate: Int): Int =
    (duration.inWholeNanoseconds * sampleRate / 1_000_000_000L).toInt()

private class ShortArrayBuilder {
    private var data = ShortArray(4096)
    private var size = 0

    fun append(values: ShortArray) {
        ensureCapacity(size + values.size)
        values.copyInto(data, destinationOffset = size)
        size += values.size
    }

    fun toArray(): ShortArray = data.copyOf(size)

    private fun ensureCapacity(required: Int) {
        if (required <= data.size) return
        var newSize = data.size
        while (newSize < required) newSize *= 2
        data = data.copyOf(newSize)
    }
}
