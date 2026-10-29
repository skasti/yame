package no.skasti.yame.ppp

import no.skasti.yame.observer.YameEvent
import no.skasti.yame.ppp.dns.PppDnsConfig
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig
import no.skasti.yame.ppp.proxy.SystemRoutingTcpProxy
import no.skasti.yame.ppp.proxy.YameRegistries
import no.skasti.yame.ppp.session.PppSession
import no.skasti.yame.ppp.lcp.LcpPacket
import no.skasti.yame.ppp.session.PppSessionIdentity
import java.io.Closeable
import java.io.OutputStream
import kotlin.time.Duration.Companion.milliseconds

interface PppHandler : Closeable {
    fun attachOutput(output: OutputStream)
    fun connected()
    /** Returns true after acknowledging a valid peer LCP termination. */
    fun receive(bytes: ByteArray): Boolean
    fun disconnected()

    override fun close() = Unit
}

internal class RetroPppHandler(
    private val connectionId: Long = 0,
    private val logger: (String) -> Unit = ::println,
    private val dnsLogger: (String) -> Unit = logger,
    private val transferLogger: (String) -> Unit = logger,
    private val proxyLogger: (String) -> Unit = logger,
    private val diagnosticSink: (YameEvent) -> Unit = {},
    private val ipConfig: PppIpConfig = PppIpConfig(),
    private val dnsConfig: PppDnsConfig = PppDnsConfig(),
    private val transformerSettings: (() -> no.skasti.yame.ppp.proxy.transform.HtmlTransformerSettings)? = null,
    private val httpCompatibilityConfig: PppHttpCompatibilityConfig = PppHttpCompatibilityConfig(),
    internal val resourceGraphs: NavigationResourceRegistry =
        YameRegistries.navigationResources(httpCompatibilityConfig),
) : PppHandler {
    private var output: OutputStream? = null
    private var encoder = PppEncoder()
    private var framer = createFramer()
    private var session: PppSession? = null
    private var peerTerminated = false
    private var addressResolver = createAddressResolver()
    private var frameDelay = 75.milliseconds

    override fun attachOutput(output: OutputStream) {
        check(this.output == null) { "PPP output is already attached" }
        addressResolver.validateConfiguredSubnet()
        this.output = output
    }

    override fun connected() {
        if (output == null) return

        disconnected()
        peerTerminated = false
        encoder = PppEncoder()
        framer = createFramer()
        addressResolver = createAddressResolver()

        val addresses = addressResolver.resolve()
        logger("PPP IP local=${addresses.localAddress} peer=${addresses.peerAddress}")

        session = PppSession(
            sessionIdentity = PppSessionIdentity.create(connectionId),
            sendFrame = ::sendFrame,
            logger = logger,
            dnsLogger = dnsLogger,
            transferLogger = transferLogger,
            diagnosticSink = diagnosticSink,
            ipAddresses = addresses,
            selectPeerAddress = addressResolver::selectPeerAddress,
            dnsConfig = dnsConfig,
            tcpProxy = SystemRoutingTcpProxy(
                httpConfig = httpCompatibilityConfig,
                transformerSettings = transformerSettings,
                logger = proxyLogger,
                diagnosticSink = diagnosticSink,
                resourceGraphs = resourceGraphs,
            ),
        )
    }

    override fun receive(bytes: ByteArray): Boolean {
        framer.receive(bytes)
        return peerTerminated
    }

    private fun createAddressResolver(): PppAddressResolver =
        PppAddressResolver(
            config = ipConfig,
            logger = logger,
        )

    private fun createFramer(): PppFramer =
        PppFramer(
            onFrame = ::receiveFrame,
            onInvalidFrame = { frame ->
                val hex = frame.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
                logger("PPP !! invalid frame (${frame.size} bytes): $hex")
            },
        )

    private fun receiveFrame(frame: PppFrame) {
        // Ignore trailing frames in this batch once the call has ended.
        if (peerTerminated) return
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
        if (frame.protocol == PppSession.LCP_PROTOCOL &&
            LcpPacket.parse(frame.payload)?.code == LcpPacket.TERMINATE_REQUEST
        ) {
            // Session processing, including writing/flushing Terminate-Ack, is complete.
            peerTerminated = true
            return
        }

        // CONNECT only means that the modem has entered data mode. Some clients,
        // including Trumpet Winsock, do not enable PPP immediately. Wait until
        // the peer sends a valid PPP frame before starting our side of LCP.
        session.start()
        syncNegotiatedOptions(session)
    }

    @Synchronized
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

    override fun disconnected() {
        val active = session
        session = null
        active?.close()
    }

    override fun close() = disconnected()
}
