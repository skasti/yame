package no.skasti.serialmodem

import no.skasti.serialmodem.modem.HayesModem
import no.skasti.serialmodem.modem.HayesModemConfig
import no.skasti.serialmodem.ppp.PppFramer
import no.skasti.serialmodem.serial.SerialConnection
import no.skasti.serialmodem.tone.HandshakeProfile
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    val options = parseArgs(args)

    if (options.testNumber != null) {
        println(
            "Tone test. Dialing: ${options.testNumber} " +
                "(pickup: ${options.modemConfig.pickupTime}, " +
                "dial tone: ${options.modemConfig.dialToneTime}, " +
                "handshake: ${options.modemConfig.handshakeProfile})...",
        )
        HayesModem(
            output = OutputStream.nullOutputStream(),
            baudRate = options.baudRate,
            config = options.modemConfig,
        ).use { modem ->
            modem.dial(options.testNumber)
        }
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

    connection.open()

    val pppFramer = PppFramer(
        onFrame = { frame ->
            val hex = frame.payload.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
            println(
                "PPP <= protocol=${frame.protocolName()} (0x%04X), payload=${frame.payload.size} bytes: $hex"
                    .format(frame.protocol),
            )
        },
        onInvalidFrame = { frame ->
            val hex = frame.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
            println("PPP !! invalid frame (${frame.size} bytes): $hex")
        },
    )

    val modem = HayesModem(
        output = connection.output,
        baudRate = options.baudRate,
        config = options.modemConfig,
        onData = pppFramer::receive,
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        modem.close()
        connection.close()
        shutdown.countDown()
    })

    connection.startReading(modem::receive)
    println("Serial modem emulator ready. Press Ctrl+C to stop.")
    shutdown.await()
}

private data class Options(
    val portName: String?,
    val baudRate: Int,
    val listPorts: Boolean,
    val testNumber: String?,
    val modemConfig: HayesModemConfig,
)

private fun parseArgs(args: Array<String>): Options {
    val defaults = HayesModemConfig()
    var port: String? = null
    var baud = 115200
    var list = false
    var testNumber: String? = null
    var pickupTime = defaults.pickupTime
    var dialToneTime = defaults.dialToneTime
    var handshakeProfile = defaults.handshakeProfile

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
            "--pickup-time" -> {
                require(i + 1 < args.size) { "$arg requires a duration, e.g. 2s or 500ms" }
                pickupTime = parseDuration(args[++i], arg)
            }
            "--dial-tone-time" -> {
                require(i + 1 < args.size) { "$arg requires a duration, e.g. 500ms or 1s" }
                dialToneTime = parseDuration(args[++i], arg)
            }
            "--handshake-profile" -> {
                require(i + 1 < args.size) { "$arg requires a profile (${handshakeProfileNames()})" }
                handshakeProfile = parseHandshakeProfile(args[++i])
            }
            "--help", "-h" -> {
                printUsage()
                kotlin.system.exitProcess(0)
            }
            else -> error("Unknown argument: $arg")
        }
        i++
    }

    return Options(
        portName = port,
        baudRate = baud,
        listPorts = list,
        testNumber = testNumber,
        modemConfig = HayesModemConfig(
            pickupTime = pickupTime,
            dialToneTime = dialToneTime,
            handshakeProfile = handshakeProfile,
        ),
    )
}

private fun parseDuration(value: String, argument: String): Duration {
    val match = DURATION_PATTERN.matchEntire(value.lowercase())
        ?: error("$argument expects a duration such as 500ms, 2s, or 1.5s")

    val amount = match.groupValues[1].toDouble()
    require(amount.isFinite()) { "$argument must be finite" }
    require(amount >= 0.0) { "$argument must not be negative" }

    return when (match.groupValues[2]) {
        "ms" -> amount.milliseconds
        "s" -> amount.seconds
        else -> error("Unsupported duration unit")
    }
}

private fun parseHandshakeProfile(value: String): HandshakeProfile =
    HandshakeProfile.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: error("Unknown handshake profile '$value'. Available profiles: ${handshakeProfileNames()}")

private fun handshakeProfileNames(): String =
    HandshakeProfile.entries.joinToString(", ") { it.name.lowercase() }

private val DURATION_PATTERN = Regex("""(\d+(?:\.\d+)?)(ms|s)""")

private fun printUsage() {
    val defaults = HayesModemConfig()
    println(
        """
        Serial Modem Emulator

        Usage:
          serial-modem-emulator --list
          serial-modem-emulator --test-tone NUMBER [modem options]
          serial-modem-emulator --port PORT [--baud 115200] [modem options]

        Options:
          -l, --list                  List available serial ports
          -t, --test-tone NUM         Run the modem dialing sequence without a serial port
          -p, --port PORT             Serial port, e.g. COM3 or /dev/ttyUSB0
          -b, --baud RATE             Baud rate (default: 115200)
              --pickup-time DURATION  Ringback time before pickup (default: ${defaults.pickupTime})
              --dial-tone-time DUR    Dial-tone duration (default: ${defaults.dialToneTime})
              --handshake-profile P   Handshake profile: ${handshakeProfileNames()} (default: ${defaults.handshakeProfile.name.lowercase()})
          -h, --help                  Show this help

        Durations accept milliseconds or seconds, e.g. 500ms, 2s, or 1.5s.
        Tone progress is always logged while a dialing sequence is played.
        """.trimIndent(),
    )
}
