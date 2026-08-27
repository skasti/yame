package no.skasti.serialmodem.ppp

import java.io.Closeable
import java.util.Timer
import java.util.TimerTask

class PppSession(
    private val sendFrame: (PppFrame) -> Unit,
    private val logger: (String) -> Unit = ::println,
    private val restartIntervalMillis: Long = DEFAULT_RESTART_INTERVAL_MILLIS,
    private val ipAddresses: PppAddresses = DEFAULT_IP_ADDRESSES,
    private val selectPeerAddress: (Ipv4Address) -> Ipv4Address = { requested ->
        if (requested == Ipv4Address.ZERO) ipAddresses.peerAddress else requested
    },
) : Closeable {
    var transmitMru: Int = DEFAULT_MRU
        private set

    var transmitAccm: UInt = PppEncoder.DEFAULT_TRANSMIT_ACCM
        private set

    var transmitProtocolFieldCompression: Boolean = false
        private set

    var transmitAddressControlFieldCompression: Boolean = false
        private set

    var receiveAccm: UInt = PppFramer.DEFAULT_RECEIVE_ACCM
        private set

    var lcpOpen: Boolean = false
        private set

    var ipcpOpen: Boolean = false
        private set

    var localIpAddress: Ipv4Address = ipAddresses.localAddress
        private set

    var peerIpAddress: Ipv4Address = ipAddresses.peerAddress
        private set

    private var started = false
    private var peerConfigured = false
    private var localConfigured = false
    private var nextLcpIdentifier = 1
    private var localConfigureRequest: LcpPacket? = null
    private var lcpRestartTimer: Timer? = null

    private var ipcpStarted = false
    private var ipcpPeerConfigured = false
    private var ipcpLocalConfigured = false
    private var nextIpcpIdentifier = 1
    private var localIpcpConfigureRequest: PppControlPacket? = null
    private var ipcpRestartTimer: Timer? = null

    init {
        require(restartIntervalMillis > 0) { "restartIntervalMillis must be positive" }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        createLocalConfigureRequest()
        sendOutstandingConfigureRequest()
        startLcpRestartTimer()
    }

    @Synchronized
    fun receive(frame: PppFrame) {
        when (frame.protocol) {
            LCP_PROTOCOL -> receiveLcp(frame.payload)
            IPCP_PROTOCOL -> receiveIpcp(frame.payload)
            else -> logger(
                "PPP .. protocol=${frame.protocolName()} (0x%04X) not handled yet"
                    .format(frame.protocol),
            )
        }
    }

    private fun receiveLcp(payload: ByteArray) {
        val packet = LcpPacket.parse(payload)
        if (packet == null) {
            logger("LCP !! malformed packet (${payload.size} bytes)")
            return
        }

        when (packet.code) {
            LcpPacket.CONFIGURE_REQUEST -> receiveConfigureRequest(packet)
            LcpPacket.CONFIGURE_ACK -> receiveConfigureAck(packet)
            LcpPacket.CONFIGURE_NAK ->
                logger("LCP <= Configure-Nak id=${packet.identifier}; renegotiation not implemented yet")
            LcpPacket.CONFIGURE_REJECT ->
                logger("LCP <= Configure-Reject id=${packet.identifier}; renegotiation not implemented yet")
            else -> logger("LCP <= code=${packet.code} id=${packet.identifier} (${packet.data.size} data bytes)")
        }
    }

    private fun receiveConfigureRequest(packet: LcpPacket) {
        val wasOpen = lcpOpen
        peerConfigured = false
        lcpOpen = false

        if (wasOpen) {
            resetIpcp()
            restartLocalNegotiation()
        }

        val options = LcpOption.parseAll(packet.data)
        if (options == null) {
            logger("LCP !! malformed Configure-Request id=${packet.identifier}")
            return
        }

        val rejected = options.filterNot(::isSupportedPeerOption)
        if (rejected.isNotEmpty()) {
            val rejectData = rejected.fold(ByteArray(0)) { bytes, option -> bytes + option.encode() }
            logger(
                "LCP => Configure-Reject id=${packet.identifier}: " +
                    rejected.joinToString { "type=${it.type}" },
            )
            sendLcp(
                LcpPacket(
                    code = LcpPacket.CONFIGURE_REJECT,
                    identifier = packet.identifier,
                    data = rejectData,
                ),
            )
            return
        }

        logger("LCP => Configure-Ack id=${packet.identifier}")
        sendLcp(
            LcpPacket(
                code = LcpPacket.CONFIGURE_ACK,
                identifier = packet.identifier,
                data = packet.data,
            ),
        )

        applyPeerOptions(options)
        peerConfigured = true
        updateLcpState()
    }

    private fun receiveConfigureAck(packet: LcpPacket) {
        val request = localConfigureRequest
        if (request == null ||
            packet.identifier != request.identifier ||
            !packet.data.contentEquals(request.data)
        ) {
            logger("LCP !! unexpected Configure-Ack id=${packet.identifier}")
            return
        }

        logger("LCP <= Configure-Ack id=${packet.identifier}")
        receiveAccm = REQUESTED_RECEIVE_ACCM
        localConfigured = true
        stopLcpRestartTimer()
        updateLcpState()
    }

    private fun isSupportedPeerOption(option: LcpOption): Boolean =
        when (option.type) {
            LcpOption.MRU -> option.data.size == 2
            LcpOption.ACCM -> option.data.size == 4
            LcpOption.MAGIC_NUMBER -> option.data.size == 4
            LcpOption.PROTOCOL_FIELD_COMPRESSION,
            LcpOption.ADDRESS_CONTROL_FIELD_COMPRESSION,
            -> option.data.isEmpty()
            else -> false
        }

    private fun applyPeerOptions(options: List<LcpOption>) {
        transmitMru = DEFAULT_MRU
        transmitAccm = PppEncoder.DEFAULT_TRANSMIT_ACCM
        transmitProtocolFieldCompression = false
        transmitAddressControlFieldCompression = false

        for (option in options) {
            when (option.type) {
                LcpOption.MRU -> {
                    transmitMru =
                        ((option.data[0].toInt() and 0xff) shl 8) or
                            (option.data[1].toInt() and 0xff)
                }

                LcpOption.ACCM -> {
                    transmitAccm =
                        ((option.data[0].toUInt() and 0xffu) shl 24) or
                            ((option.data[1].toUInt() and 0xffu) shl 16) or
                            ((option.data[2].toUInt() and 0xffu) shl 8) or
                            (option.data[3].toUInt() and 0xffu)
                }

                LcpOption.PROTOCOL_FIELD_COMPRESSION ->
                    transmitProtocolFieldCompression = true

                LcpOption.ADDRESS_CONTROL_FIELD_COMPRESSION ->
                    transmitAddressControlFieldCompression = true
            }
        }
    }

    private fun restartLocalNegotiation() {
        localConfigured = false
        receiveAccm = PppFramer.DEFAULT_RECEIVE_ACCM
        createLocalConfigureRequest()
        sendOutstandingConfigureRequest()
        startLcpRestartTimer()
    }

    private fun createLocalConfigureRequest() {
        val identifier = nextLcpIdentifier and 0xff
        nextLcpIdentifier = (nextLcpIdentifier + 1) and 0xff

        val accm = LcpOption(
            type = LcpOption.ACCM,
            data = byteArrayOf(0x00, 0x00, 0x00, 0x00),
        )
        localConfigureRequest = LcpPacket(
            code = LcpPacket.CONFIGURE_REQUEST,
            identifier = identifier,
            data = accm.encode(),
        )
    }

    private fun sendOutstandingConfigureRequest(isRetry: Boolean = false) {
        val request = localConfigureRequest ?: return

        if (isRetry) {
            logger("LCP => Configure-Request id=${request.identifier} retry")
        } else {
            logger("LCP => Configure-Request id=${request.identifier} (ACCM=0)")
        }
        sendLcp(request)
    }

    private fun startLcpRestartTimer() {
        stopLcpRestartTimer()
        lcpRestartTimer = Timer("ppp-lcp-restart", true).apply {
            schedule(
                object : TimerTask() {
                    override fun run() {
                        retryOutstandingConfigureRequest()
                    }
                },
                restartIntervalMillis,
                restartIntervalMillis,
            )
        }
    }

    @Synchronized
    private fun retryOutstandingConfigureRequest() {
        if (localConfigured) {
            stopLcpRestartTimer()
            return
        }

        try {
            sendOutstandingConfigureRequest(isRetry = true)
        } catch (e: Exception) {
            logger("LCP !! Configure-Request retry failed: ${e.message}")
        }
    }

    private fun stopLcpRestartTimer() {
        lcpRestartTimer?.cancel()
        lcpRestartTimer = null
    }

    private fun sendLcp(packet: LcpPacket) {
        sendFrame(
            PppFrame(
                protocol = LCP_PROTOCOL,
                payload = packet.encode(),
            ),
        )
    }

    private fun updateLcpState() {
        val open = peerConfigured && localConfigured
        if (open && !lcpOpen) {
            lcpOpen = true
            logger("LCP open")
            startIpcp()
        }
    }

    private fun receiveIpcp(payload: ByteArray) {
        if (!lcpOpen) {
            logger("IPCP .. ignored before LCP is open")
            return
        }

        val packet = PppControlPacket.parse(payload)
        if (packet == null) {
            logger("IPCP !! malformed packet (${payload.size} bytes)")
            return
        }

        when (packet.code) {
            PppControlPacket.CONFIGURE_REQUEST -> receiveIpcpConfigureRequest(packet)
            PppControlPacket.CONFIGURE_ACK -> receiveIpcpConfigureAck(packet)
            PppControlPacket.CONFIGURE_NAK ->
                logger(
                    "IPCP <= Configure-Nak id=${packet.identifier}; " +
                        "local address renegotiation not implemented yet",
                )
            PppControlPacket.CONFIGURE_REJECT ->
                logger(
                    "IPCP <= Configure-Reject id=${packet.identifier}; " +
                        "local address renegotiation not implemented yet",
                )
            else -> logger(
                "IPCP <= code=${packet.code} id=${packet.identifier} (${packet.data.size} data bytes)",
            )
        }
    }

    private fun receiveIpcpConfigureRequest(packet: PppControlPacket) {
        val wasOpen = ipcpOpen
        ipcpPeerConfigured = false
        ipcpOpen = false

        if (wasOpen) {
            restartLocalIpcpNegotiation()
        }

        val options = PppControlOption.parseAll(packet.data)
        if (options == null) {
            logger("IPCP !! malformed Configure-Request id=${packet.identifier}")
            return
        }

        val rejected = options.filterNot {
            it.type == PppControlOption.IPCP_IP_ADDRESS && it.data.size == 4
        }
        if (rejected.isNotEmpty()) {
            val rejectData = rejected.fold(ByteArray(0)) { bytes, option -> bytes + option.encode() }
            logger(
                "IPCP => Configure-Reject id=${packet.identifier}: " +
                    rejected.joinToString { "type=${it.type}" },
            )
            sendIpcp(
                PppControlPacket(
                    code = PppControlPacket.CONFIGURE_REJECT,
                    identifier = packet.identifier,
                    data = rejectData,
                ),
            )
            return
        }

        val addressOption = options.firstOrNull {
            it.type == PppControlOption.IPCP_IP_ADDRESS
        }
        val requestedAddress = addressOption
            ?.let { Ipv4Address.fromBytes(it.data) }
            ?: Ipv4Address.ZERO
        val selectedAddress = selectPeerAddress(requestedAddress)

        if (addressOption == null ||
            requestedAddress == Ipv4Address.ZERO ||
            requestedAddress != selectedAddress
        ) {
            val nak = PppControlOption(
                type = PppControlOption.IPCP_IP_ADDRESS,
                data = selectedAddress.toByteArray(),
            )
            logger(
                "IPCP => Configure-Nak id=${packet.identifier} " +
                    "(IP-Address=$selectedAddress)",
            )
            sendIpcp(
                PppControlPacket(
                    code = PppControlPacket.CONFIGURE_NAK,
                    identifier = packet.identifier,
                    data = nak.encode(),
                ),
            )
            return
        }

        peerIpAddress = selectedAddress
        logger(
            "IPCP => Configure-Ack id=${packet.identifier} " +
                "(IP-Address=$peerIpAddress)",
        )
        sendIpcp(
            PppControlPacket(
                code = PppControlPacket.CONFIGURE_ACK,
                identifier = packet.identifier,
                data = packet.data,
            ),
        )

        ipcpPeerConfigured = true
        updateIpcpState()
    }

    private fun receiveIpcpConfigureAck(packet: PppControlPacket) {
        val request = localIpcpConfigureRequest
        if (request == null ||
            packet.identifier != request.identifier ||
            !packet.data.contentEquals(request.data)
        ) {
            logger("IPCP !! unexpected Configure-Ack id=${packet.identifier}")
            return
        }

        logger("IPCP <= Configure-Ack id=${packet.identifier}")
        ipcpLocalConfigured = true
        stopIpcpRestartTimer()
        updateIpcpState()
    }

    private fun startIpcp() {
        if (ipcpStarted) return
        ipcpStarted = true
        createLocalIpcpConfigureRequest()
        sendOutstandingIpcpConfigureRequest()
        startIpcpRestartTimer()
    }

    private fun restartLocalIpcpNegotiation() {
        ipcpLocalConfigured = false
        createLocalIpcpConfigureRequest()
        sendOutstandingIpcpConfigureRequest()
        startIpcpRestartTimer()
    }

    private fun createLocalIpcpConfigureRequest() {
        val identifier = nextIpcpIdentifier and 0xff
        nextIpcpIdentifier = (nextIpcpIdentifier + 1) and 0xff

        val address = PppControlOption(
            type = PppControlOption.IPCP_IP_ADDRESS,
            data = localIpAddress.toByteArray(),
        )
        localIpcpConfigureRequest = PppControlPacket(
            code = PppControlPacket.CONFIGURE_REQUEST,
            identifier = identifier,
            data = address.encode(),
        )
    }

    private fun sendOutstandingIpcpConfigureRequest(isRetry: Boolean = false) {
        val request = localIpcpConfigureRequest ?: return

        if (isRetry) {
            logger("IPCP => Configure-Request id=${request.identifier} retry")
        } else {
            logger(
                "IPCP => Configure-Request id=${request.identifier} " +
                    "(IP-Address=$localIpAddress)",
            )
        }
        sendIpcp(request)
    }

    private fun startIpcpRestartTimer() {
        stopIpcpRestartTimer()
        ipcpRestartTimer = Timer("ppp-ipcp-restart", true).apply {
            schedule(
                object : TimerTask() {
                    override fun run() {
                        retryOutstandingIpcpConfigureRequest()
                    }
                },
                restartIntervalMillis,
                restartIntervalMillis,
            )
        }
    }

    @Synchronized
    private fun retryOutstandingIpcpConfigureRequest() {
        if (ipcpLocalConfigured || !lcpOpen) {
            stopIpcpRestartTimer()
            return
        }

        try {
            sendOutstandingIpcpConfigureRequest(isRetry = true)
        } catch (e: Exception) {
            logger("IPCP !! Configure-Request retry failed: ${e.message}")
        }
    }

    private fun stopIpcpRestartTimer() {
        ipcpRestartTimer?.cancel()
        ipcpRestartTimer = null
    }

    private fun sendIpcp(packet: PppControlPacket) {
        sendFrame(
            PppFrame(
                protocol = IPCP_PROTOCOL,
                payload = packet.encode(),
            ),
        )
    }

    private fun updateIpcpState() {
        val open = ipcpPeerConfigured && ipcpLocalConfigured
        if (open && !ipcpOpen) {
            ipcpOpen = true
            logger("IPCP open: local=$localIpAddress peer=$peerIpAddress")
        }
    }

    private fun resetIpcp() {
        ipcpStarted = false
        ipcpPeerConfigured = false
        ipcpLocalConfigured = false
        ipcpOpen = false
        peerIpAddress = ipAddresses.peerAddress
        localIpcpConfigureRequest = null
        stopIpcpRestartTimer()
    }

    @Synchronized
    override fun close() {
        stopLcpRestartTimer()
        stopIpcpRestartTimer()
    }

    companion object {
        const val LCP_PROTOCOL = 0xc021
        const val IPCP_PROTOCOL = 0x8021
        const val DEFAULT_MRU = 1500
        private const val REQUESTED_RECEIVE_ACCM: UInt = 0u
        private const val DEFAULT_RESTART_INTERVAL_MILLIS = 3_000L

        private val DEFAULT_IP_ADDRESSES = PppAddresses(
            localAddress = Ipv4Address.parse("10.0.0.1"),
            peerAddress = Ipv4Address.parse("10.0.0.2"),
            allocationSubnet = Ipv4Cidr.parse("10.0.0.0/30"),
        )
    }
}
