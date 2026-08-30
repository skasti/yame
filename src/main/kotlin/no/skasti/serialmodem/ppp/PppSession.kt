package no.skasti.serialmodem.ppp

import java.io.Closeable
import java.util.ArrayDeque
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
    private val icmpEchoProxy: IcmpEchoProxy = SystemPingIcmpEchoProxy(),
    private val icmpEchoTimeoutMillis: Long = DEFAULT_ICMP_ECHO_TIMEOUT_MILLIS,
    private val udpProxy: UdpProxy = SystemUdpProxy(),
    private val tcpProxy: TcpProxy = SystemTcpProxy(),
    private val tcpHandshakeTimeoutMillis: Long = DEFAULT_TCP_HANDSHAKE_TIMEOUT_MILLIS,
    private val tcpRetransmitTimeoutMillis: Long = DEFAULT_TCP_RETRANSMIT_TIMEOUT_MILLIS,
    private val dnsConfig: PppDnsConfig = PppDnsConfig(),
) : Closeable {
    private data class TcpContext(
        val proxyFlow: TcpProxyFlow,
        val dscpEcn: Int,
        var pendingSynAck: TcpPacket?,
        var connected: Boolean = false,
        var established: Boolean = false,
        var hostEof: Boolean = false,
        var hostFinSent: Boolean = false,
        var hostReadsPaused: Boolean = false,
        var handshakeTimeoutTask: TimerTask? = null,
        val pendingHostPayloads: ArrayDeque<ByteArray> = ArrayDeque(),
        var pendingHostBytes: Int = 0,
    )

    private val tcpFlowTable = TcpFlowTable()
    private val tcpHandshakeTimer = Timer("ppp-tcp-handshake-timeout", true)
    private val tcpRetransmitTimer = Timer("ppp-tcp-retransmit", true)
    private val tcpContexts = mutableMapOf<TcpFlowKey, TcpContext>()

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
    private var closed = false
    private var peerConfigured = false
    private var localConfigured = false
    private var nextLcpIdentifier = 1
    private var localConfigureRequest: LcpPacket? = null
    private var lcpRestartTimer: Timer? = null

    private var ipcpStarted = false
    private var ipcpGeneration = 0L
    private var ipcpDnsPrompted = false
    private var ipcpDnsPromptRequestIdentifier: Int? = null
    private var ipcpDnsPromptRequestData: ByteArray? = null
    private var ipcpPeerConfigured = false
    private var ipcpLocalConfigured = false
    private var nextIpcpIdentifier = 1
    private var localIpcpConfigureRequest: PppControlPacket? = null
    private var ipcpRestartTimer: Timer? = null

    init {
        require(restartIntervalMillis > 0) { "restartIntervalMillis must be positive" }
        require(icmpEchoTimeoutMillis > 0) { "icmpEchoTimeoutMillis must be positive" }
        require(tcpHandshakeTimeoutMillis > 0) { "tcpHandshakeTimeoutMillis must be positive" }
        require(tcpRetransmitTimeoutMillis > 0) { "tcpRetransmitTimeoutMillis must be positive" }

        val retransmitScanInterval = maxOf(
            MIN_TCP_RETRANSMIT_SCAN_MILLIS,
            tcpRetransmitTimeoutMillis / 2,
        )
        tcpRetransmitTimer.schedule(
            object : TimerTask() {
                override fun run() {
                    retransmitDueTcpSegments()
                }
            },
            retransmitScanInterval,
            retransmitScanInterval,
        )
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
            IPV4_PROTOCOL -> receiveIpv4(frame.payload)
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
    private fun receiveIpv4(payload: ByteArray) {
        if (!ipcpOpen) {
            logger("IPv4 .. ignored before IPCP is open")
            return
        }

        val packet = Ipv4Packet.parse(payload)
        if (packet == null) {
            logger("IPv4 !! malformed packet or invalid header checksum (${payload.size} bytes)")
            return
        }

        if (packet.source != peerIpAddress) {
            logger(
                "IPv4 !! source ${packet.source} does not match negotiated peer $peerIpAddress",
            )
            return
        }

        if (packet.destination == localIpAddress) {
            receiveLocalIpv4(packet)
        } else {
            receiveExternalIpv4(packet)
        }
    }

    private fun receiveLocalIpv4(packet: Ipv4Packet) {
        if (packet.isFragmented) {
            logger("IPv4 .. fragmented packet to local endpoint not handled")
            return
        }

        when (packet.protocol) {
            Ipv4Packet.ICMP_PROTOCOL -> receiveLocalIcmp(packet)
            Ipv4Packet.UDP_PROTOCOL -> receiveLocalUdp(packet)
            else -> logger("IPv4 .. local protocol=${packet.protocol} not handled")
        }
    }

    private fun receiveLocalIcmp(packet: Ipv4Packet) {
        val icmp = IcmpPacket.parse(packet.payload)
        if (icmp == null) {
            logger("ICMP !! malformed packet or invalid checksum")
            return
        }

        val reply = icmp.toEchoReply()
        if (reply == null) {
            logger("ICMP .. type=${icmp.type} code=${icmp.code} not handled")
            return
        }

        val identifier = icmp.echoIdentifier()
        val sequence = icmp.echoSequence()
        logger(
            "ICMP <= Echo Request ${packet.source} -> ${packet.destination} " +
                "id=$identifier seq=$sequence",
        )

        val replyPacket = Ipv4Packet(
            dscpEcn = packet.dscpEcn,
            identification = packet.identification,
            ttl = Ipv4Packet.DEFAULT_TTL,
            protocol = Ipv4Packet.ICMP_PROTOCOL,
            source = localIpAddress,
            destination = packet.source,
            payload = reply.encode(),
        )
        sendIpv4EchoReply(
            replyPacket = replyPacket,
            logSource = localIpAddress,
            logDestination = packet.source,
            identifier = identifier,
            sequence = sequence,
        )
    }

    private fun receiveLocalUdp(packet: Ipv4Packet) {
        val udp = UdpPacket.parse(
            bytes = packet.payload,
            source = packet.source,
            destination = packet.destination,
        )
        if (udp == null) {
            logger("UDP !! malformed local datagram or invalid checksum")
            return
        }

        if (udp.destinationPort != DNS_PORT) {
            logger("UDP .. local port=${udp.destinationPort} not handled")
            return
        }

        val generation = ipcpGeneration
        val flow = UdpFlow(
            peerPort = udp.sourcePort,
            destination = dnsConfig.upstreamServer,
            destinationPort = DNS_PORT,
            generation = generation,
            namespace = UdpFlowNamespace.LOCAL_DNS,
        )
        logger(
            "DNS <= ${packet.source}:${udp.sourcePort} -> " +
                "$localIpAddress:$DNS_PORT; forwarding to ${dnsConfig.upstreamServer}:$DNS_PORT",
        )

        udpProxy.send(flow, udp.payload) { result ->
            result.fold(
                onSuccess = { payload ->
                    sendLocalDnsReply(
                        flow = flow,
                        payload = payload,
                        dscpEcn = packet.dscpEcn,
                    )
                },
                onFailure = { error ->
                    val reason = error.message ?: error.javaClass.simpleName
                    logger("DNS !! upstream ${dnsConfig.upstreamServer}:$DNS_PORT failed: $reason")
                },
            )
        }
    }

    private fun receiveExternalIpv4(packet: Ipv4Packet) {
        if (packet.isFragmented) {
            logger("IPv4 .. fragmented egress packet not handled")
            return
        }

        when (packet.protocol) {
            Ipv4Packet.ICMP_PROTOCOL -> receiveExternalIcmp(packet)
            Ipv4Packet.TCP_PROTOCOL -> receiveExternalTcp(packet)
            Ipv4Packet.UDP_PROTOCOL -> receiveExternalUdp(packet)
            else -> logger(
                "IPv4 .. ${packet.source} -> ${packet.destination} " +
                    "protocol=${packet.protocol} egress not handled yet",
            )
        }
    }

    private fun receiveExternalIcmp(packet: Ipv4Packet) {
        val icmp = IcmpPacket.parse(packet.payload)
        if (icmp == null) {
            logger("ICMP !! malformed packet or invalid checksum")
            return
        }

        val reply = icmp.toEchoReply()
        if (reply == null) {
            logger("ICMP .. egress type=${icmp.type} code=${icmp.code} not handled")
            return
        }

        val identifier = icmp.echoIdentifier()
        val sequence = icmp.echoSequence()
        val generation = ipcpGeneration
        logger(
            "ICMP <= Echo Request ${packet.source} -> ${packet.destination} " +
                "id=$identifier seq=$sequence; probing via host",
        )

        icmpEchoProxy.echo(packet.destination, icmpEchoTimeoutMillis) { result ->
            result.fold(
                onSuccess = { reachable ->
                    if (reachable) {
                        sendExternalEchoReply(
                            packet,
                            reply,
                            identifier,
                            sequence,
                            generation,
                        )
                    } else {
                        logger(
                            "ICMP .. host Echo Request to ${packet.destination} " +
                                "id=$identifier seq=$sequence timed out",
                        )
                    }
                },
                onFailure = { error ->
                    val reason = error.message ?: error.javaClass.simpleName
                    logger(
                        "ICMP !! host Echo Request to ${packet.destination} " +
                            "id=$identifier seq=$sequence failed: $reason",
                    )
                },
            )
        }
    }

    private fun receiveExternalUdp(packet: Ipv4Packet) {
        val udp = UdpPacket.parse(
            bytes = packet.payload,
            source = packet.source,
            destination = packet.destination,
        )
        if (udp == null) {
            logger("UDP !! malformed datagram or invalid checksum")
            return
        }

        val generation = ipcpGeneration
        val flow = UdpFlow(
            peerPort = udp.sourcePort,
            destination = packet.destination,
            destinationPort = udp.destinationPort,
            generation = generation,
        )
        logger(
            "UDP <= ${packet.source}:${udp.sourcePort} -> " +
                "${packet.destination}:${udp.destinationPort} " +
                "payload=${udp.payload.size} bytes",
        )

        udpProxy.send(flow, udp.payload) { result ->
            result.fold(
                onSuccess = { payload ->
                    sendExternalUdpReply(
                        flow = flow,
                        payload = payload,
                        dscpEcn = packet.dscpEcn,
                    )
                },
                onFailure = { error ->
                    val reason = error.message ?: error.javaClass.simpleName
                    logger(
                        "UDP !! ${packet.destination}:${udp.destinationPort} " +
                            "flow failed: $reason",
                    )
                },
            )
        }
    }


    private fun receiveExternalTcp(packet: Ipv4Packet) {
        val tcp = TcpPacket.parse(
            bytes = packet.payload,
            source = packet.source,
            destination = packet.destination,
        )
        if (tcp == null) {
            logger("TCP !! malformed segment or invalid checksum")
            return
        }

        val key = TcpFlowKey(
            peerAddress = packet.source,
            peerPort = tcp.sourcePort,
            remoteAddress = packet.destination,
            remotePort = tcp.destinationPort,
        )

        val existingContext = tcpContexts[key]
        if (
            existingContext?.connected == true &&
            tcp.payload.isNotEmpty()
        ) {
            val snapshot = tcpFlowTable.snapshot(key)
            if (snapshot != null && tcp.sequenceNumber == snapshot.peerNextSequence) {
                val writeCapacity = tcpProxy
                    .availableWriteCapacity(existingContext.proxyFlow)
                    .coerceIn(0, 0xffff)
                if (tcp.payload.size > writeCapacity) {
                    tcpFlowTable.setReceiveWindow(key, writeCapacity)
                    tcpFlowTable.acknowledgment(key)?.let { ack ->
                        sendTcpPacket(key, ack, existingContext.dscpEcn)
                    }
                    logger(
                        "TCP .. host write buffer has $writeCapacity bytes free; " +
                            "deferring ${tcp.payload.size}-byte peer segment",
                    )
                    return
                }
                tcpFlowTable.setReceiveWindow(
                    key,
                    writeCapacity - tcp.payload.size,
                )
            }
        }

        val result = tcpFlowTable.receive(key, tcp)
        val connectionRequested = result.events.any { it is TcpFlowEvent.ConnectionRequested }

        if (connectionRequested) {
            val synAck = result.responses.singleOrNull()
            if (synAck == null) {
                logger("TCP !! SYN did not produce exactly one SYN-ACK")
                tcpFlowTable.reset(key)
                return
            }

            val proxyFlow = TcpProxyFlow(
                key = key,
                generation = ipcpGeneration,
            )
            val context = TcpContext(
                proxyFlow = proxyFlow,
                dscpEcn = packet.dscpEcn,
                pendingSynAck = synAck,
            )
            tcpContexts[key] = context
            logger(
                "TCP <= SYN ${packet.source}:${tcp.sourcePort} -> " +
                    "${packet.destination}:${tcp.destinationPort}; connecting via host",
            )
            tcpProxy.connect(proxyFlow) { event ->
                receiveTcpProxyEvent(proxyFlow, event)
            }
        } else {
            val context = tcpContexts[key]
            val reset = result.events.any { it is TcpFlowEvent.Reset }
            if (context == null || context.connected || reset) {
                sendTcpResponses(
                    key = key,
                    responses = result.responses,
                    dscpEcn = context?.dscpEcn ?: packet.dscpEcn,
                )
            }
        }

        handleTcpFlowEvents(key, result.events)
        tcpContexts[key]?.let(::drainHostTcpPayloads)
    }

    private fun handleTcpFlowEvents(
        key: TcpFlowKey,
        events: List<TcpFlowEvent>,
    ) {
        for (event in events) {
            when (event) {
                is TcpFlowEvent.ConnectionRequested -> Unit

                is TcpFlowEvent.Established -> {
                    val context = tcpContexts[key] ?: continue
                    context.established = true
                    cancelTcpHandshakeTimeout(context)
                    logger(
                        "TCP open ${key.peerAddress}:${key.peerPort} <-> " +
                            "${key.remoteAddress}:${key.remotePort}",
                    )
                }

                is TcpFlowEvent.PayloadReceived -> {
                    val context = tcpContexts[key] ?: continue
                    tcpProxy.send(context.proxyFlow, event.payload)
                        .onFailure { error -> failTcpFlow(context, error) }
                }

                is TcpFlowEvent.PeerClosed -> {
                    val context = tcpContexts[key] ?: continue
                    tcpProxy.shutdownOutput(context.proxyFlow)
                        .onFailure { error -> failTcpFlow(context, error) }
                }

                is TcpFlowEvent.Reset,
                is TcpFlowEvent.Closed,
                -> {
                    tcpContexts.remove(key)?.let { context ->
                        cancelTcpHandshakeTimeout(context)
                        tcpProxy.closeFlow(context.proxyFlow)
                    }
                }
            }
        }
    }

    @Synchronized
    private fun receiveTcpProxyEvent(
        flow: TcpProxyFlow,
        event: TcpProxyEvent,
    ) {
        val context = tcpContexts[flow.key]
        if (
            context == null ||
            context.proxyFlow != flow ||
            closed ||
            !ipcpOpen ||
            flow.generation != ipcpGeneration
        ) {
            tcpProxy.closeFlow(flow)
            return
        }

        when (event) {
            TcpProxyEvent.Connected -> {
                context.connected = true
                updateHostReadBackpressure(context)
                startTcpHandshakeTimeout(context)
                val writeCapacity = tcpProxy
                    .availableWriteCapacity(flow)
                    .coerceIn(0, 0xffff)
                tcpFlowTable.setReceiveWindow(flow.key, writeCapacity)
                val synAck = context.pendingSynAck?.copy(windowSize = writeCapacity)
                context.pendingSynAck = null
                if (synAck != null) {
                    sendTcpPacket(flow.key, synAck, context.dscpEcn)
                    logger(
                        "TCP host connected ${flow.key.remoteAddress}:${flow.key.remotePort}; " +
                            "sent SYN-ACK to ${flow.key.peerAddress}:${flow.key.peerPort}",
                    )
                }
            }

            is TcpProxyEvent.Payload -> queueHostTcpPayload(context, event.bytes)

            TcpProxyEvent.EndOfStream -> {
                context.hostEof = true
                drainHostTcpPayloads(context)
            }

            is TcpProxyEvent.WriteCompleted -> refreshPeerReceiveWindow(context)

            is TcpProxyEvent.Failure -> failTcpFlow(context, event.error)
        }
    }

    private fun refreshPeerReceiveWindow(context: TcpContext) {
        if (tcpContexts[context.proxyFlow.key] !== context) return

        val snapshot = tcpFlowTable.snapshot(context.proxyFlow.key) ?: return
        val writeCapacity = tcpProxy
            .availableWriteCapacity(context.proxyFlow)
            .coerceIn(0, 0xffff)
        if (!tcpFlowTable.setReceiveWindow(context.proxyFlow.key, writeCapacity)) return

        if (context.established && writeCapacity > snapshot.localReceiveWindow) {
            tcpFlowTable.acknowledgment(context.proxyFlow.key)?.let { ack ->
                sendTcpPacket(context.proxyFlow.key, ack, context.dscpEcn)
            }
        }
    }

    private fun queueHostTcpPayload(
        context: TcpContext,
        payload: ByteArray,
    ) {
        if (payload.isEmpty()) return

        context.pendingHostPayloads.addLast(payload.copyOf())
        context.pendingHostBytes += payload.size
        drainHostTcpPayloads(context)
    }

    private fun drainHostTcpPayloads(context: TcpContext) {
        if (tcpContexts[context.proxyFlow.key] !== context) return
        if (!context.established) {
            updateHostReadBackpressure(context)
            return
        }

        val maximumIpv4Payload = transmitMru - IPV4_TCP_HEADER_LENGTH
        if (maximumIpv4Payload <= 0) {
            failTcpFlow(
                context,
                IllegalStateException("peer MRU $transmitMru is too small for IPv4/TCP"),
            )
            return
        }

        while (
            context.pendingHostPayloads.isNotEmpty() &&
            tcpContexts[context.proxyFlow.key] === context
        ) {
            val snapshot = tcpFlowTable.snapshot(context.proxyFlow.key) ?: return
            val availableWindow = snapshot.availableSendWindow
            if (availableWindow <= 0) {
                updateHostReadBackpressure(context)
                return
            }

            val payload = context.pendingHostPayloads.first()
            val count = minOf(
                maximumIpv4Payload,
                snapshot.peerMaximumSegmentSize,
                availableWindow,
                payload.size,
            )
            if (count <= 0) {
                updateHostReadBackpressure(context)
                return
            }

            val chunk = payload.copyOfRange(0, count)
            val segment = tcpFlowTable.send(context.proxyFlow.key, chunk) ?: return
            sendTcpPacket(context.proxyFlow.key, segment, context.dscpEcn)

            context.pendingHostPayloads.removeFirst()
            if (count < payload.size) {
                context.pendingHostPayloads.addFirst(payload.copyOfRange(count, payload.size))
            }
            context.pendingHostBytes -= count
        }

        if (
            context.pendingHostPayloads.isEmpty() &&
            context.hostEof &&
            !context.hostFinSent &&
            tcpContexts[context.proxyFlow.key] === context
        ) {
            closeTcpFromHost(context)
        }

        if (tcpContexts[context.proxyFlow.key] === context) {
            updateHostReadBackpressure(context)
        }
    }

    private fun updateHostReadBackpressure(context: TcpContext) {
        if (tcpContexts[context.proxyFlow.key] !== context) return

        val availableWindow = tcpFlowTable.snapshot(context.proxyFlow.key)
            ?.availableSendWindow
            ?: 0
        val shouldPause = when {
            !context.established -> true
            availableWindow <= 0 -> true
            context.pendingHostBytes >= TCP_HOST_BUFFER_HIGH_WATER_BYTES -> true
            context.hostReadsPaused &&
                context.pendingHostBytes > TCP_HOST_BUFFER_LOW_WATER_BYTES -> true
            else -> false
        }
        if (shouldPause == context.hostReadsPaused) return

        context.hostReadsPaused = shouldPause
        if (shouldPause) {
            tcpProxy.pauseReads(context.proxyFlow)
        } else {
            tcpProxy.resumeReads(context.proxyFlow)
        }
    }

    @Synchronized
    private fun retransmitDueTcpSegments() {
        if (closed || !ipcpOpen) return

        for (context in tcpContexts.values.toList()) {
            if (context.proxyFlow.generation != ipcpGeneration) continue
            val packet = tcpFlowTable.retransmissionDue(
                context.proxyFlow.key,
                tcpRetransmitTimeoutMillis,
            ) ?: continue

            logger(
                "TCP .. retransmitting ${context.proxyFlow.key.remoteAddress}:" +
                    "${packet.sourcePort} -> ${context.proxyFlow.key.peerAddress}:" +
                    "${packet.destinationPort} seq=${packet.sequenceNumber} " +
                    "payload=${packet.payload.size} bytes",
            )
            sendTcpPacket(
                context.proxyFlow.key,
                packet,
                context.dscpEcn,
            )
        }
    }

    private fun startTcpHandshakeTimeout(context: TcpContext) {
        cancelTcpHandshakeTimeout(context)
        val flow = context.proxyFlow
        val task = object : TimerTask() {
            override fun run() {
                expireTcpHandshake(flow)
            }
        }
        context.handshakeTimeoutTask = task
        tcpHandshakeTimer.schedule(task, tcpHandshakeTimeoutMillis)
    }

    @Synchronized
    private fun expireTcpHandshake(flow: TcpProxyFlow) {
        val context = tcpContexts[flow.key] ?: return
        if (
            context.proxyFlow != flow ||
            context.established ||
            closed ||
            flow.generation != ipcpGeneration
        ) {
            return
        }

        context.handshakeTimeoutTask = null
        failTcpFlow(
            context,
            IllegalStateException("TCP peer did not complete handshake before timeout"),
        )
    }

    private fun cancelTcpHandshakeTimeout(context: TcpContext) {
        context.handshakeTimeoutTask?.cancel()
        context.handshakeTimeoutTask = null
    }

    private fun closeTcpFromHost(context: TcpContext) {
        val fin = tcpFlowTable.close(context.proxyFlow.key) ?: return
        context.hostFinSent = true
        sendTcpPacket(context.proxyFlow.key, fin, context.dscpEcn)
        logger(
            "TCP host closed ${context.proxyFlow.key.remoteAddress}:" +
                "${context.proxyFlow.key.remotePort}; sent FIN",
        )
    }

    private fun failTcpFlow(
        context: TcpContext,
        error: Throwable,
    ) {
        if (tcpContexts[context.proxyFlow.key] !== context) return

        cancelTcpHandshakeTimeout(context)
        val reason = error.message ?: error.javaClass.simpleName
        logger(
            "TCP !! ${context.proxyFlow.key.remoteAddress}:" +
                "${context.proxyFlow.key.remotePort} failed: $reason",
        )
        val reset = tcpFlowTable.reset(context.proxyFlow.key)
        tcpContexts.remove(context.proxyFlow.key)
        tcpProxy.closeFlow(context.proxyFlow)
        if (
            reset != null &&
            !closed &&
            ipcpOpen &&
            context.proxyFlow.generation == ipcpGeneration
        ) {
            sendTcpPacket(context.proxyFlow.key, reset, context.dscpEcn)
        }
    }

    private fun sendTcpResponses(
        key: TcpFlowKey,
        responses: List<TcpPacket>,
        dscpEcn: Int,
    ) {
        responses.forEach { response ->
            sendTcpPacket(key, response, dscpEcn)
        }
    }

    private fun sendTcpPacket(
        key: TcpFlowKey,
        packet: TcpPacket,
        dscpEcn: Int,
    ) {
        val ipv4 = Ipv4Packet(
            dscpEcn = dscpEcn,
            protocol = Ipv4Packet.TCP_PROTOCOL,
            source = key.remoteAddress,
            destination = key.peerAddress,
            payload = packet.encode(
                source = key.remoteAddress,
                destination = key.peerAddress,
            ),
        )
        val encoded = ipv4.encode()
        if (encoded.size > transmitMru) {
            logger(
                "TCP .. segment ${encoded.size} bytes exceeds peer MRU $transmitMru; not sent",
            )
            return
        }

        sendFrame(
            PppFrame(
                protocol = IPV4_PROTOCOL,
                payload = encoded,
            ),
        )
        tcpFlowTable.markSent(key, packet)
        logger(
            "TCP => ${key.remoteAddress}:${packet.sourcePort} -> " +
                "${key.peerAddress}:${packet.destinationPort} " +
                "flags=0x${packet.flags.toString(16)} seq=${packet.sequenceNumber} " +
                "ack=${packet.acknowledgmentNumber} payload=${packet.payload.size} bytes",
        )
    }


    @Synchronized
    private fun sendExternalEchoReply(
        request: Ipv4Packet,
        reply: IcmpPacket,
        identifier: Int?,
        sequence: Int?,
        generation: Long,
    ) {
        if (
            closed ||
            !ipcpOpen ||
            generation != ipcpGeneration ||
            request.source != peerIpAddress
        ) {
            logger(
                "ICMP .. dropping host Echo Reply from ${request.destination}; PPP/IPCP state changed",
            )
            return
        }

        val replyPacket = Ipv4Packet(
            dscpEcn = request.dscpEcn,
            identification = request.identification,
            ttl = Ipv4Packet.DEFAULT_TTL,
            protocol = Ipv4Packet.ICMP_PROTOCOL,
            source = request.destination,
            destination = request.source,
            payload = reply.encode(),
        )
        sendIpv4EchoReply(
            replyPacket = replyPacket,
            logSource = request.destination,
            logDestination = request.source,
            identifier = identifier,
            sequence = sequence,
        )
    }

    @Synchronized
    private fun sendLocalDnsReply(
        flow: UdpFlow,
        payload: ByteArray,
        dscpEcn: Int,
    ) {
        if (closed || !ipcpOpen || flow.generation != ipcpGeneration) {
            logger("DNS .. dropping upstream reply; PPP/IPCP state changed")
            return
        }

        sendUdpReply(
            source = localIpAddress,
            sourcePort = DNS_PORT,
            destination = peerIpAddress,
            destinationPort = flow.peerPort,
            payload = payload,
            dscpEcn = dscpEcn,
            logPrefix = "DNS",
        )
    }

    @Synchronized
    private fun sendExternalUdpReply(
        flow: UdpFlow,
        payload: ByteArray,
        dscpEcn: Int,
    ) {
        if (closed || !ipcpOpen || flow.generation != ipcpGeneration) {
            logger(
                "UDP .. dropping reply from ${flow.destination}:${flow.destinationPort}; " +
                    "PPP/IPCP state changed",
            )
            return
        }

        sendUdpReply(
            source = flow.destination,
            sourcePort = flow.destinationPort,
            destination = peerIpAddress,
            destinationPort = flow.peerPort,
            payload = payload,
            dscpEcn = dscpEcn,
            logPrefix = "UDP",
        )
    }

    private fun sendUdpReply(
        source: Ipv4Address,
        sourcePort: Int,
        destination: Ipv4Address,
        destinationPort: Int,
        payload: ByteArray,
        dscpEcn: Int,
        logPrefix: String,
    ) {
        val udp = UdpPacket(
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            payload = payload,
        )
        val replyPacket = Ipv4Packet(
            dscpEcn = dscpEcn,
            protocol = Ipv4Packet.UDP_PROTOCOL,
            source = source,
            destination = destination,
            payload = udp.encode(
                source = source,
                destination = destination,
            ),
        )
        val encodedReply = replyPacket.encode()
        if (encodedReply.size > transmitMru) {
            logger(
                "$logPrefix .. reply ${encodedReply.size} bytes exceeds peer MRU $transmitMru; not sent",
            )
            return
        }

        sendFrame(
            PppFrame(
                protocol = IPV4_PROTOCOL,
                payload = encodedReply,
            ),
        )
        logger(
            "$logPrefix => $source:$sourcePort -> " +
                "$destination:$destinationPort payload=${payload.size} bytes",
        )
    }

    private fun sendIpv4EchoReply(
        replyPacket: Ipv4Packet,
        logSource: Ipv4Address,
        logDestination: Ipv4Address,
        identifier: Int?,
        sequence: Int?,
    ) {
        val encodedReply = replyPacket.encode()
        if (encodedReply.size > transmitMru) {
            logger(
                "ICMP .. Echo Reply ${encodedReply.size} bytes exceeds peer MRU $transmitMru; not sent",
            )
            return
        }

        sendFrame(
            PppFrame(
                protocol = IPV4_PROTOCOL,
                payload = encodedReply,
            ),
        )

        logger(
            "ICMP => Echo Reply $logSource -> $logDestination " +
                "id=$identifier seq=$sequence",
        )
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
            ipcpGeneration++
            ipcpDnsPrompted = false
            ipcpDnsPromptRequestIdentifier = null
            ipcpDnsPromptRequestData = null
            invalidateTransportFlows()
            restartLocalIpcpNegotiation()
        }

        val options = PppControlOption.parseAll(packet.data)
        if (options == null) {
            logger("IPCP !! malformed Configure-Request id=${packet.identifier}")
            return
        }

        val rejected = options.filterNot(::isSupportedPeerIpcpOption)
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

        val nakOptions = mutableListOf<PppControlOption>()
        val addressOption = options.firstOrNull { it.type == PppControlOption.IPCP_IP_ADDRESS }
        val requestedAddress = addressOption
            ?.let { Ipv4Address.fromBytes(it.data) }
            ?: Ipv4Address.ZERO
        val selectedAddress = selectPeerAddress(requestedAddress)
        var primaryDnsSeen = false

        for (option in options) {
            when (option.type) {
                PppControlOption.IPCP_IP_ADDRESS -> {
                    if (
                        requestedAddress == Ipv4Address.ZERO ||
                        requestedAddress != selectedAddress
                    ) {
                        nakOptions += PppControlOption(
                            type = PppControlOption.IPCP_IP_ADDRESS,
                            data = selectedAddress.toByteArray(),
                        )
                    }
                }

                PppControlOption.IPCP_PRIMARY_DNS,
                PppControlOption.IPCP_SECONDARY_DNS,
                -> {
                    if (option.type == PppControlOption.IPCP_PRIMARY_DNS) {
                        primaryDnsSeen = true
                        ipcpDnsPrompted = true
                        ipcpDnsPromptRequestIdentifier = null
                        ipcpDnsPromptRequestData = null
                    }
                    val requestedDns = Ipv4Address.fromBytes(option.data)
                    if (requestedDns != localIpAddress) {
                        nakOptions += PppControlOption(
                            type = option.type,
                            data = localIpAddress.toByteArray(),
                        )
                    }
                }
            }
        }

        if (addressOption == null) {
            nakOptions += PppControlOption(
                type = PppControlOption.IPCP_IP_ADDRESS,
                data = selectedAddress.toByteArray(),
            )
        }

        val repeatedDnsPromptRequest =
            ipcpDnsPrompted &&
                ipcpDnsPromptRequestIdentifier == packet.identifier &&
                ipcpDnsPromptRequestData?.contentEquals(packet.data) == true
        if (!primaryDnsSeen && (!ipcpDnsPrompted || repeatedDnsPromptRequest)) {
            nakOptions += PppControlOption(
                type = PppControlOption.IPCP_PRIMARY_DNS,
                data = localIpAddress.toByteArray(),
            )
            if (!ipcpDnsPrompted) {
                ipcpDnsPrompted = true
                ipcpDnsPromptRequestIdentifier = packet.identifier
                ipcpDnsPromptRequestData = packet.data.copyOf()
            }
        }

        if (nakOptions.isNotEmpty()) {
            val nakData = nakOptions.fold(ByteArray(0)) { bytes, option -> bytes + option.encode() }
            logger(
                "IPCP => Configure-Nak id=${packet.identifier}: " +
                    nakOptions.joinToString { option ->
                        val address = Ipv4Address.fromBytes(option.data)
                        "type=${option.type} address=$address"
                    },
            )
            sendIpcp(
                PppControlPacket(
                    code = PppControlPacket.CONFIGURE_NAK,
                    identifier = packet.identifier,
                    data = nakData,
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

    private fun isSupportedPeerIpcpOption(option: PppControlOption): Boolean =
        when (option.type) {
            PppControlOption.IPCP_IP_ADDRESS,
            PppControlOption.IPCP_PRIMARY_DNS,
            PppControlOption.IPCP_SECONDARY_DNS,
            -> option.data.size == 4

            else -> false
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

    private fun invalidateTransportFlows() {
        udpProxy.invalidateBefore(ipcpGeneration)
        tcpProxy.invalidateBefore(ipcpGeneration)
        tcpContexts.values.forEach(::cancelTcpHandshakeTimeout)
        tcpFlowTable.clear()
        tcpContexts.clear()
    }

    private fun resetIpcp() {
        ipcpGeneration++
        ipcpDnsPrompted = false
        ipcpDnsPromptRequestIdentifier = null
        ipcpDnsPromptRequestData = null
        invalidateTransportFlows()
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
        if (closed) return
        closed = true
        lcpOpen = false
        ipcpOpen = false
        stopLcpRestartTimer()
        stopIpcpRestartTimer()
        tcpHandshakeTimer.cancel()
        tcpRetransmitTimer.cancel()
        icmpEchoProxy.close()
        udpProxy.close()
        tcpContexts.values.forEach(::cancelTcpHandshakeTimeout)
        tcpFlowTable.clear()
        tcpContexts.clear()
        tcpProxy.close()
    }

    companion object {
        const val LCP_PROTOCOL = 0xc021
        const val IPCP_PROTOCOL = 0x8021
        const val IPV4_PROTOCOL = 0x0021
        const val DEFAULT_MRU = 1500
        private const val REQUESTED_RECEIVE_ACCM: UInt = 0u
        private const val DEFAULT_RESTART_INTERVAL_MILLIS = 3_000L
        private const val DEFAULT_ICMP_ECHO_TIMEOUT_MILLIS = 2_000L
        private const val DEFAULT_TCP_HANDSHAKE_TIMEOUT_MILLIS = 10_000L
        private const val DEFAULT_TCP_RETRANSMIT_TIMEOUT_MILLIS = 3_000L
        private const val MIN_TCP_RETRANSMIT_SCAN_MILLIS = 25L
        private const val DNS_PORT = 53
        private const val IPV4_TCP_HEADER_LENGTH = 40
        private const val TCP_HOST_BUFFER_HIGH_WATER_BYTES = 32 * 1024
        private const val TCP_HOST_BUFFER_LOW_WATER_BYTES = 16 * 1024

        private val DEFAULT_IP_ADDRESSES = PppAddresses(
            localAddress = Ipv4Address.parse("10.0.0.1"),
            peerAddress = Ipv4Address.parse("10.0.0.2"),
            allocationSubnet = Ipv4Cidr.parse("10.0.0.0/30"),
        )
    }
}