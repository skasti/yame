package no.skasti.serialmodem

import no.skasti.serialmodem.modem.HayesModem
import no.skasti.serialmodem.serial.SerialConnection
import no.skasti.serialmodem.tone.HandshakeProfile
import no.skasti.serialmodem.tone.ToneProgress
import no.skasti.serialmodem.tone.tone
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    val options = parseArgs(args)
    val toneProgressLogger = toneProgressLogger(options.logToneSteps)

    if (options.testNumber != null) {
        println("Tone test. Dialing: ${options.testNumber} (handshake: ${options.handshakeProfile})...")
        tone.dial(
            number = options.testNumber,
            pickupTime = 7.seconds,
            dialToneTime = 1.seconds,
            handshakeProfile = options.handshakeProfile,
            onProgress = toneProgressLogger,
        )
        println("Tone test complete.")
        return
    }

    if (options.listPorts) {
        val ports = SerialConnection.availablePorts()
        if (ports.isEmpty()) {
            println("No serial ports found")
        } else {
            println("Available serial ports:")
            ports.forEach { println("  $it") }
        }
        return
    }

    val portName = options.portName ?: run {
        printUsage()
        return
    }

    val connection = SerialConnection(portName, options.baudRate)
    val shutdown = CountDownLatch(1)

    Runtime.getRuntime().addShutdownHook(Thread {
        connection.close()
        shutdown.countDown()
    })

    connection.open()

    val modem = HayesModem(
        output = connection.output,
        baudRate = options.baudRate,
        onData = { bytes ->
            val hex = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
            println("DATA <= ${bytes.size} bytes: $hex")
        },
        onDial = { number ->
            try {
                tone.dial(
                    number = number,
                    pickupTime = 2.seconds,
                    onProgress = toneProgressLogger,
                )
            } catch (e: Exception) {
                // Audio is cosmetic: a missing/unconfigured audio device must not
                // prevent the serial modem itself from establishing a connection.
                println("AUDIO !! Could not play dialing tones: ${e.message}")
            }
        },
    )

    connection.startReading(modem::receive)
    println("Serial modem emulator ready. Press Ctrl+C to stop.")
    shutdown.await()
}

private data class Options(
    val portName: String?,
    val baudRate: Int,
    val listPorts: Boolean,
    val testNumber: String?,
    val handshakeProfile: HandshakeProfile,
    val logToneSteps: Boolean,
)

private fun parseArgs(args: Array<String>): Options {
    var port: String? = null
    var baud = 115200
    var list = false
    var testNumber: String? = null
    var handshakeProfile = HandshakeProfile.V34
    var logToneSteps = false

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--port", "-p" -> {
                require(i + 1 < args.size) { "$arg requires a port name" }
                port = args[++i]
            }
            "--baud", "-b" -> {
                require(i + 1 < args.size) { "$arg requires a baud rate" }
                baud = args[++i].toInt()
            }
            "--list", "-l" -> list = true
            "--test-tone", "-t" -> {
                require(i + 1 < args.size) { "$arg requires a number to dial" }
                testNumber = args[++i]
            }
            "--handshake-profile" -> {
                require(i + 1 < args.size) { "$arg requires a profile (${handshakeProfileNames()})" }
                handshakeProfile = parseHandshakeProfile(args[++i])
            }
            "--log-tone-steps" -> logToneSteps = true
            "--help", "-h" -> {
                printUsage()
                kotlin.system.exitProcess(0)
            }
            else -> error("Unknown argument: $arg")
        }
        i++
    }

    return Options(port, baud, list, testNumber, handshakeProfile, logToneSteps)
}

private fun parseHandshakeProfile(value: String): HandshakeProfile =
    HandshakeProfile.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: error("Unknown handshake profile '$value'. Available profiles: ${handshakeProfileNames()}")

private fun handshakeProfileNames(): String =
    HandshakeProfile.entries.joinToString(", ") { it.name.lowercase() }

private fun toneProgressLogger(enabled: Boolean): ((ToneProgress) -> Unit)? =
    if (!enabled) {
        null
    } else {
        { progress ->
            println("TONE [${progress.step.name.lowercase()}] ${progress.description}")
        }
    }

private fun printUsage() {
    println(
        """
        Serial Modem Emulator

        Usage:
          serial-modem-emulator --list
          serial-modem-emulator --test-tone NUMBER [--handshake-profile PROFILE] [--log-tone-steps]
          serial-modem-emulator --port PORT [--baud 115200] [--log-tone-steps]

        Options:
          -l, --list                  List available serial ports
          -t, --test-tone NUM         Play a simulated dialing sequence and exit
              --handshake-profile P   Handshake for --test-tone: ${handshakeProfileNames()} (default: v34)
              --log-tone-steps        Log dialing/handshake phases as they are played
          -p, --port PORT             Serial port, e.g. COM3 or /dev/ttyUSB0
          -b, --baud RATE             Baud rate (default: 115200)
          -h, --help                  Show this help
        """.trimIndent(),
    )
}
