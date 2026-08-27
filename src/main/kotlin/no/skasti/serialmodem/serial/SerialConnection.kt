package no.skasti.serialmodem.serial

import com.fazecast.jSerialComm.SerialPort
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

class SerialConnection(
    portName: String,
    private val baudRate: Int,
    private val logger: (String) -> Unit = ::println,
) : Closeable {
    private val port: SerialPort = SerialPort.getCommPort(portName)
    private var readerThread: Thread? = null

    val output: OutputStream
        get() {
            check(port.isOpen) { "Serial port must be open before accessing its output stream" }
            return port.outputStream
        }

    fun open() {
        port.setComPortParameters(
            baudRate,
            8,
            SerialPort.ONE_STOP_BIT,
            SerialPort.NO_PARITY,
        )
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0)

        if (!port.openPort()) {
            error("Could not open serial port ${port.systemPortName}")
        }

        logger("Opened ${port.systemPortName} at $baudRate baud, 8N1, no flow control")
    }

    fun startReading(onBytes: (ByteArray) -> Unit) {
        check(port.isOpen) { "Serial port must be open before starting the reader" }
        check(readerThread == null) { "Serial reader is already running" }

        readerThread = thread(
            name = "serial-reader-${port.systemPortName}",
            isDaemon = false,
        ) {
            readLoop(port.inputStream, onBytes)
        }
    }

    fun setCarrierPresent(carrierPresent: Boolean) {
        if (carrierPresent) {
            port.setDTR()
            port.setRTS()
        } else {
            port.clearDTR()
            port.clearRTS()
        }
    }

    private fun readLoop(input: InputStream, onBytes: (ByteArray) -> Unit) {
        val buffer = ByteArray(4096)
        try {
            while (port.isOpen) {
                val count = input.read(buffer)
                if (count > 0) {
                    onBytes(buffer.copyOf(count))
                }
            }
        } catch (e: Exception) {
            if (port.isOpen) {
                logger("Serial reader stopped: ${e.message}")
            }
        }
    }

    override fun close() {
        if (port.isOpen) {
            port.closePort()
            logger("Closed ${port.systemPortName}")
        }
    }

    companion object {
        fun availablePorts(): List<String> =
            SerialPort.getCommPorts().map { it.systemPortName }
    }
}
