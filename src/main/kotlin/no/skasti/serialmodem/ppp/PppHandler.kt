package no.skasti.serialmodem.ppp

import java.io.Closeable
import java.io.OutputStream

interface PppHandler : Closeable {
    fun attachOutput(output: OutputStream)
    fun connected()
    fun receive(bytes: ByteArray)

    override fun close() = Unit
}

class RetroPppHandler(
    private val logger: (String) -> Unit = ::println,
) : PppHandler {
    private var output: OutputStream? = null
    private var encoder = PppEncoder()
    private var framer = createFramer()
    private var session: PppSession? = null

    override fun attachOutput(output: OutputStream) {
        check(this.output == null) { "PPP output is already attached" }
        this.output = output
    }

    override fun connected() {
        if (output == null) return

        encoder = PppEncoder()
        framer = createFramer()
        session = PppSession(
            sendFrame = ::sendFrame,
            logger = logger,
        )
    }

    override fun receive(bytes: ByteArray) {
        framer.receive(bytes)
    }

    private fun createFramer(): PppFramer =
        PppFramer(
            onFrame = ::receiveFrame,
            onInvalidFrame = { frame ->
                val hex = frame.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
                logger("PPP !! invalid frame (${frame.size} bytes): $hex")
            },
        )

    private fun receiveFrame(frame: PppFrame) {
        val hex = frame.payload.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
        logger(
            "PPP <= protocol=${frame.protocolName()} (0x%04X), payload=${frame.payload.size} bytes: $hex"
                .format(frame.protocol),
        )

        val session = session
        if (session == null) {
            logger("PPP !! received frame without an active session")
            return
        }

        session.receive(frame)
        syncNegotiatedOptions(session)

        // CONNECT only means that the modem has entered data mode. Some clients,
        // including Trumpet Winsock, do not enable PPP immediately. Wait until
        // the peer sends a valid PPP frame before starting our side of LCP.
        session.start()
        syncNegotiatedOptions(session)
    }

    private fun sendFrame(frame: PppFrame) {
        val output = output ?: return
        val wire = encoder.encode(frame)
        output.write(wire)
        output.flush()

        val hex = frame.payload.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
        logger(
            "PPP => protocol=${frame.protocolName()} (0x%04X), payload=${frame.payload.size} bytes: $hex"
                .format(frame.protocol),
        )
    }

    private fun syncNegotiatedOptions(session: PppSession) {
        encoder.transmitAccm = session.transmitAccm
        encoder.protocolFieldCompression = session.transmitProtocolFieldCompression
        encoder.addressControlFieldCompression = session.transmitAddressControlFieldCompression
        framer.receiveAccm = session.receiveAccm
    }
}
