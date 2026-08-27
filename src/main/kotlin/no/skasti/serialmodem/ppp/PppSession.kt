package no.skasti.serialmodem.ppp

class PppSession(
    private val sendFrame: (PppFrame) -> Unit,
    private val logger: (String) -> Unit = ::println,
) {
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

    private var started = false
    private var peerConfigured = false
    private var localConfigured = false
    private var nextIdentifier = 1
    private var localConfigureRequest: LcpPacket? = null

    fun start() {
        if (started) return
        started = true
        sendLocalConfigureRequest()
    }

    fun receive(frame: PppFrame) {
        when (frame.protocol) {
            LCP_PROTOCOL -> receiveLcp(frame.payload)
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
        transmitAccm = PppEncoder.DEFAULT_TRANSMIT_ACCM
        transmitProtocolFieldCompression = false
        transmitAddressControlFieldCompression = false

        for (option in options) {
            when (option.type) {
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

    private fun sendLocalConfigureRequest() {
        val identifier = nextIdentifier and 0xff
        nextIdentifier = (nextIdentifier + 1) and 0xff

        val accm = LcpOption(
            type = LcpOption.ACCM,
            data = byteArrayOf(0x00, 0x00, 0x00, 0x00),
        )
        val request = LcpPacket(
            code = LcpPacket.CONFIGURE_REQUEST,
            identifier = identifier,
            data = accm.encode(),
        )

        localConfigureRequest = request
        logger("LCP => Configure-Request id=$identifier (ACCM=0)")
        sendLcp(request)
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
        }
    }

    companion object {
        const val LCP_PROTOCOL = 0xc021
        private const val REQUESTED_RECEIVE_ACCM: UInt = 0u
    }
}
