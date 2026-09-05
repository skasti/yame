package no.skasti.serialmodem.serial

import com.fazecast.jSerialComm.SerialPort
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

data class SerialPortDescriptor(
    val systemPortName: String,
    val descriptivePortName: String,
)

class SerialConnection(
    portName: String,
    private val baudRate: Int,
    private val flowControl: SerialFlowControl = SerialFlowControl.DISABLED,
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
        port.setFlowControl(flowControl.toJSerialCommValue())
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0)

        if (!port.openPort()) {
            error("Could not open serial port ${port.systemPortName}")
        }

        logger(
            "Opened ${port.systemPortName} at $baudRate baud, 8N1, " +
                "flow control ${flowControl.displayName}",
        )
    }

    fun startReading(
        onBytes: (ByteArray) -> Unit,
        onStopped: (Throwable?) -> Unit = {},
    ) {
        check(port.isOpen) { "Serial port must be open before starting the reader" }
        check(readerThread == null) { "Serial reader is already running" }

        readerThread = thread(
            name = "serial-reader-${port.systemPortName}",
            isDaemon = false,
        ) {
            readLoop(port.inputStream, onBytes, onStopped)
        }
    }

    fun setCarrierPresent(carrierPresent: Boolean) {
        if (carrierPresent) {
            port.setDTR()
        } else {
            port.clearDTR()
        }
    }

    private fun readLoop(
        input: InputStream,
        onBytes: (ByteArray) -> Unit,
        onStopped: (Throwable?) -> Unit,
    ) {
        val buffer = ByteArray(4096)
        var failure: Throwable? = null
        try {
            while (port.isOpen) {
                val count = input.read(buffer)
                if (count > 0) {
                    onBytes(buffer.copyOf(count))
                }
            }
        } catch (e: Exception) {
            if (port.isOpen) {
                failure = e
                logger("Serial reader stopped: ${e.message}")
            }
        } finally {
            runCatching { onStopped(failure) }
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
            availablePortDescriptors().map { it.systemPortName }

        fun availablePortDescriptors(): List<SerialPortDescriptor> =
            SerialPort.getCommPorts().map { port ->
                SerialPortDescriptor(
                    systemPortName = port.systemPortName,
                    descriptivePortName = port.descriptivePortName,
                )
            }
    }
}
