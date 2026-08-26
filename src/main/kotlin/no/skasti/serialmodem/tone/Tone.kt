package no.skasti.serialmodem.tone

import java.io.Closeable
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Blocking telephone-tone playback for simulated dial-up calls.
 *
 * The default timings model a Norwegian PSTN call:
 * - 425 Hz dial tone
 * - standard DTMF frequency pairs
 * - 95 ms DTMF tone / 95 ms inter-digit pause (classic Hayes S11 default)
 * - 425 Hz ringback, 1 s on / 4 s off
 */
object tone {
    fun dial(
        number: String,
        pickupTime: Duration,
        dialToneTime: Duration = 500.milliseconds,
    ) {
        JavaSoundTonePlayer().use { player ->
            player.dial(number, pickupTime, dialToneTime)
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
    ) {
        require(!pickupTime.isNegative()) { "pickupTime must not be negative" }
        require(!dialToneTime.isNegative()) { "dialToneTime must not be negative" }

        play(ToneSequence.dial(number, pickupTime, dialToneTime, sampleRate))
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
    ): ShortArray {
        val normalizedNumber = number.uppercase().filterNot(Char::isWhitespace)
        require(normalizedNumber.isNotEmpty()) { "number must contain at least one DTMF digit" }
        require(normalizedNumber.all(dtmf::containsKey)) {
            "number contains unsupported DTMF characters: $number"
        }

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
}

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
