package no.skasti.yame.serial

import com.fazecast.jSerialComm.SerialPort
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
) : SerialTransport {
    private val port: SerialPort = SerialPort.getCommPort(portName)
    private var readerThread: Thread? = null
    private var signalThread: Thread? = null
    private var onPeerDtrDrop: (() -> Unit)? = null

    override val output: OutputStream
        get() {
            check(port.isOpen) { "Serial port must be open before accessing its output stream" }
            return port.outputStream
        }

    override fun open() {
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

    override fun onPeerDtrDropped(callback: () -> Unit) {
        check(readerThread == null) { "Configure peer DTR monitoring before starting the reader" }
        onPeerDtrDrop = callback
    }

    override fun startReading(
        onBytes: (ByteArray) -> Unit,
        onStopped: (Throwable?) -> Unit,
    ) {
        check(port.isOpen) { "Serial port must be open before starting the reader" }
        check(readerThread == null) { "Serial reader is already running" }

        onPeerDtrDrop?.let { callback ->
            signalThread = thread(name = "serial-signals-${port.systemPortName}", isDaemon = true) {
                val monitor = PeerDtrMonitor()
                while (port.isOpen && !Thread.currentThread().isInterrupted) {
                    try {
                        val dropped = monitor.observe(port.getDSR(), port.getDCD())
                        if (dropped.isNotEmpty()) {
                            logger("Peer DTR dropped (observed via ${dropped.joinToString()})")
                            callback()
                        }
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        break
                    } catch (error: Exception) {
                        logger("Serial !! Modem signal monitoring failed: ${error.message}")
                        break
                    }
                }
            }
        }
        readerThread = thread(
            name = "serial-reader-${port.systemPortName}",
            isDaemon = false,
        ) {
            readLoop(port.inputStream, onBytes, onStopped)
        }
    }

    override fun setCarrierPresent(carrierPresent: Boolean) {
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
        try {
            if (port.isOpen) {
                port.closePort()
                logger("Closed ${port.systemPortName}")
            }
        } finally {
            signalThread?.interrupt()
            val signal = signalThread
            if (signal != null && signal !== Thread.currentThread()) {
                signal.join(2_000)
                check(!signal.isAlive) { "Serial signal monitor did not stop" }
            }
            val reader = readerThread
            if (reader != null && reader !== Thread.currentThread()) {
                reader.join(2_000)
                check(!reader.isAlive) { "Serial reader did not stop" }
            }
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
