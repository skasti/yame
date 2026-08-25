package no.skasti.serialmodem

import no.skasti.serialmodem.modem.HayesModem
import no.skasti.serialmodem.serial.SerialConnection
import java.util.concurrent.CountDownLatch

fun main(args: Array<String>) {
    val options = parseArgs(args)

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
    )

    connection.startReading(modem::receive)
    println("Serial modem emulator ready. Press Ctrl+C to stop.")
    shutdown.await()
}

private data class Options(
    val portName: String?,
    val baudRate: Int,
    val listPorts: Boolean,
)

private fun parseArgs(args: Array<String>): Options {
    var port: String? = null
    var baud = 115200
    var list = false

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
            "--help", "-h" -> {
                printUsage()
                kotlin.system.exitProcess(0)
            }
            else -> error("Unknown argument: $arg")
        }
        i++
    }

    return Options(port, baud, list)
}

private fun printUsage() {
    println(
        """
        Serial Modem Emulator

        Usage:
          serial-modem-emulator --list
          serial-modem-emulator --port COM3 [--baud 115200]

        Options:
          -l, --list          List available serial ports
          -p, --port PORT     Serial port, e.g. COM3
          -b, --baud RATE     Baud rate (default: 115200)
          -h, --help          Show this help
        """.trimIndent(),
    )
}
