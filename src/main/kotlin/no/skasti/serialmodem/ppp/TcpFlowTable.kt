package no.skasti.serialmodem.ppp

import java.util.concurrent.ThreadLocalRandom

data class TcpFlowKey(
    val peerAddress: Ipv4Address,
    val peerPort: Int,
    val remoteAddress: Ipv4Address,
    val remotePort: Int,
) {
    init {
        require(peerPort in 0..0xffff) { "TCP peer port must be 0..65535" }
        require(remotePort in 0..0xffff) { "TCP remote port must be 0..65535" }
    }
}

enum class TcpConnectionState {
    SYN_RECEIVED,
    ESTABLISHED,
    FIN_WAIT_1,
    FIN_WAIT_2,
    CLOSE_WAIT,
    CLOSING,
    LAST_ACK,
}

data class TcpFlowSnapshot(
    val key: TcpFlowKey,
    val state: TcpConnectionState,
    val peerNextSequence: UInt,
    val localNextSequence: UInt,
    val localAcknowledgedSequence: UInt,
)

sealed interface TcpFlowEvent {
    val key: TcpFlowKey

    data class ConnectionRequested(override val key: TcpFlowKey) : TcpFlowEvent

    data class Established(override val key: TcpFlowKey) : TcpFlowEvent

    data class PayloadReceived(
        override val key: TcpFlowKey,
        val payload: ByteArray,
    ) : TcpFlowEvent

    data class PeerClosed(override val key: TcpFlowKey) : TcpFlowEvent

    data class Reset(override val key: TcpFlowKey) : TcpFlowEvent

    data class Closed(override val key: TcpFlowKey) : TcpFlowEvent
}

data class TcpFlowResult(
    val responses: List<TcpPacket> = emptyList(),
    val events: List<TcpFlowEvent> = emptyList(),
)

class TcpFlowTable(
    private val initialSequenceNumber: (TcpFlowKey) -> UInt = {
        ThreadLocalRandom.current().nextInt().toUInt()
    },
    private val receiveWindow: Int = TcpPacket.DEFAULT_WINDOW_SIZE,
) {
    private data class Flow(
        val key: TcpFlowKey,
        val peerInitialSequence: UInt,
        val localInitialSequence: UInt,
        var peerNextSequence: UInt,
        var localNextSequence: UInt,
        var localAcknowledgedSequence: UInt,
        var state: TcpConnectionState,
    )

    init {
        require(receiveWindow in 0..0xffff) { "TCP receive window must be 0..65535" }
    }

    private val flows = mutableMapOf<TcpFlowKey, Flow>()

    @Synchronized
    fun receive(
        key: TcpFlowKey,
        packet: TcpPacket,
    ): TcpFlowResult {
        require(packet.sourcePort == key.peerPort) { "TCP packet source port does not match flow key" }
        require(packet.destinationPort == key.remotePort) { "TCP packet destination port does not match flow key" }

        val flow = flows[key]
        if (flow == null) return receiveWithoutFlow(key, packet)

        if (packet.hasFlag(TcpPacket.RST)) {
            flows.remove(key)
            return TcpFlowResult(events = listOf(TcpFlowEvent.Reset(key)))
        }

        return when (flow.state) {
            TcpConnectionState.SYN_RECEIVED -> receiveSynReceived(flow, packet)
            TcpConnectionState.ESTABLISHED,
            TcpConnectionState.FIN_WAIT_1,
            TcpConnectionState.FIN_WAIT_2,
            TcpConnectionState.CLOSE_WAIT,
            TcpConnectionState.CLOSING,
            TcpConnectionState.LAST_ACK,
            -> receiveSynchronized(flow, packet)
        }
    }

    @Synchronized
    fun send(
        key: TcpFlowKey,
        payload: ByteArray,
        push: Boolean = true,
    ): TcpPacket? {
        if (payload.isEmpty()) return null
        val flow = flows[key] ?: return null
        if (flow.state != TcpConnectionState.ESTABLISHED && flow.state != TcpConnectionState.CLOSE_WAIT) {
            return null
        }

        val packet = packetToPeer(
            flow = flow,
            flags = TcpPacket.ACK or if (push) TcpPacket.PSH else 0,
            payload = payload.copyOf(),
        )
        flow.localNextSequence += payload.size.toUInt()
        return packet
    }

    @Synchronized
    fun close(key: TcpFlowKey): TcpPacket? {
        val flow = flows[key] ?: return null
        val nextState = when (flow.state) {
            TcpConnectionState.ESTABLISHED -> TcpConnectionState.FIN_WAIT_1
            TcpConnectionState.CLOSE_WAIT -> TcpConnectionState.LAST_ACK
            else -> return null
        }

        val packet = packetToPeer(flow, TcpPacket.FIN or TcpPacket.ACK)
        flow.localNextSequence += 1u
        flow.state = nextState
        return packet
    }

    @Synchronized
    fun reset(key: TcpFlowKey): TcpPacket? {
        val flow = flows.remove(key) ?: return null
        return packetToPeer(flow, TcpPacket.RST or TcpPacket.ACK)
    }

    @Synchronized
    fun snapshot(key: TcpFlowKey): TcpFlowSnapshot? = flows[key]?.snapshot()

    @Synchronized
    fun size(): Int = flows.size

    @Synchronized
    fun clear() {
        flows.clear()
    }

    private fun receiveWithoutFlow(
        key: TcpFlowKey,
        packet: TcpPacket,
    ): TcpFlowResult {
        if (packet.hasFlag(TcpPacket.RST)) return TcpFlowResult()

        if (
            packet.hasFlag(TcpPacket.SYN) &&
            !packet.hasFlag(TcpPacket.ACK) &&
            !packet.hasFlag(TcpPacket.FIN) &&
            packet.payload.isEmpty()
        ) {
            val localInitialSequence = initialSequenceNumber(key)
            val flow = Flow(
                key = key,
                peerInitialSequence = packet.sequenceNumber,
                localInitialSequence = localInitialSequence,
                peerNextSequence = packet.sequenceNumber + 1u,
                localNextSequence = localInitialSequence + 1u,
                localAcknowledgedSequence = localInitialSequence,
                state = TcpConnectionState.SYN_RECEIVED,
            )
            flows[key] = flow
            return TcpFlowResult(
                responses = listOf(synAck(flow)),
                events = listOf(TcpFlowEvent.ConnectionRequested(key)),
            )
        }

        return TcpFlowResult(responses = listOf(resetForUnknownFlow(key, packet)))
    }

    private fun receiveSynReceived(
        flow: Flow,
        packet: TcpPacket,
    ): TcpFlowResult {
        if (
            packet.hasFlag(TcpPacket.SYN) &&
            !packet.hasFlag(TcpPacket.ACK) &&
            packet.sequenceNumber == flow.peerInitialSequence
        ) {
            return TcpFlowResult(responses = listOf(synAck(flow)))
        }

        if (
            !packet.hasFlag(TcpPacket.ACK) ||
            packet.hasFlag(TcpPacket.SYN) ||
            packet.acknowledgmentNumber != flow.localNextSequence ||
            packet.sequenceNumber != flow.peerNextSequence
        ) {
            flows.remove(flow.key)
            return TcpFlowResult(
                responses = listOf(resetForUnknownFlow(flow.key, packet)),
                events = listOf(TcpFlowEvent.Reset(flow.key)),
            )
        }

        flow.localAcknowledgedSequence = packet.acknowledgmentNumber
        flow.state = TcpConnectionState.ESTABLISHED
        val established = TcpFlowEvent.Established(flow.key)

        if (packet.payload.isEmpty() && !packet.hasFlag(TcpPacket.FIN)) {
            return TcpFlowResult(events = listOf(established))
        }

        val synchronized = receiveSynchronized(flow, packet, sequenceAlreadyValidated = true)
        return TcpFlowResult(
            responses = synchronized.responses,
            events = listOf(established) + synchronized.events,
        )
    }

    private fun receiveSynchronized(
        flow: Flow,
        packet: TcpPacket,
        sequenceAlreadyValidated: Boolean = false,
    ): TcpFlowResult {
        if (!sequenceAlreadyValidated && packet.sequenceNumber != flow.peerNextSequence) {
            return TcpFlowResult(responses = listOf(ack(flow)))
        }

        if (packet.hasFlag(TcpPacket.SYN)) {
            return TcpFlowResult(responses = listOf(ack(flow)))
        }

        if (packet.hasFlag(TcpPacket.ACK)) {
            acknowledgeLocal(flow, packet.acknowledgmentNumber)
        }

        val events = mutableListOf<TcpFlowEvent>()
        var needsAck = false

        if (packet.payload.isNotEmpty()) {
            if (flow.state == TcpConnectionState.CLOSE_WAIT || flow.state == TcpConnectionState.LAST_ACK) {
                return TcpFlowResult(responses = listOf(ack(flow)))
            }
            flow.peerNextSequence += packet.payload.size.toUInt()
            events += TcpFlowEvent.PayloadReceived(flow.key, packet.payload.copyOf())
            needsAck = true
        }

        val peerFin = packet.hasFlag(TcpPacket.FIN)
        if (peerFin) {
            flow.peerNextSequence += 1u
            needsAck = true
            events += TcpFlowEvent.PeerClosed(flow.key)
        }

        var closed = false
        when (flow.state) {
            TcpConnectionState.ESTABLISHED -> {
                if (peerFin) flow.state = TcpConnectionState.CLOSE_WAIT
            }

            TcpConnectionState.FIN_WAIT_1 -> {
                val localFinAcknowledged = flow.localAcknowledgedSequence == flow.localNextSequence
                when {
                    peerFin && localFinAcknowledged -> closed = true
                    peerFin -> flow.state = TcpConnectionState.CLOSING
                    localFinAcknowledged -> flow.state = TcpConnectionState.FIN_WAIT_2
                }
            }

            TcpConnectionState.FIN_WAIT_2 -> {
                if (peerFin) closed = true
            }

            TcpConnectionState.CLOSING -> {
                if (flow.localAcknowledgedSequence == flow.localNextSequence) closed = true
            }

            TcpConnectionState.LAST_ACK -> {
                if (flow.localAcknowledgedSequence == flow.localNextSequence) closed = true
            }

            TcpConnectionState.CLOSE_WAIT -> Unit
            TcpConnectionState.SYN_RECEIVED -> error("SYN_RECEIVED handled separately")
        }

        val responses = if (needsAck) listOf(ack(flow)) else emptyList()
        if (closed) {
            flows.remove(flow.key)
            events += TcpFlowEvent.Closed(flow.key)
        }
        return TcpFlowResult(responses = responses, events = events)
    }

    private fun acknowledgeLocal(
        flow: Flow,
        acknowledgmentNumber: UInt,
    ) {
        if (acknowledgmentNumber == flow.localNextSequence) {
            flow.localAcknowledgedSequence = acknowledgmentNumber
        }
    }

    private fun synAck(flow: Flow): TcpPacket =
        TcpPacket(
            sourcePort = flow.key.remotePort,
            destinationPort = flow.key.peerPort,
            sequenceNumber = flow.localInitialSequence,
            acknowledgmentNumber = flow.peerNextSequence,
            flags = TcpPacket.SYN or TcpPacket.ACK,
            windowSize = receiveWindow,
        )

    private fun ack(flow: Flow): TcpPacket =
        packetToPeer(flow, TcpPacket.ACK)

    private fun packetToPeer(
        flow: Flow,
        flags: Int,
        payload: ByteArray = ByteArray(0),
    ): TcpPacket =
        TcpPacket(
            sourcePort = flow.key.remotePort,
            destinationPort = flow.key.peerPort,
            sequenceNumber = flow.localNextSequence,
            acknowledgmentNumber = flow.peerNextSequence,
            flags = flags,
            windowSize = receiveWindow,
            payload = payload,
        )

    private fun resetForUnknownFlow(
        key: TcpFlowKey,
        packet: TcpPacket,
    ): TcpPacket =
        if (packet.hasFlag(TcpPacket.ACK)) {
            TcpPacket(
                sourcePort = key.remotePort,
                destinationPort = key.peerPort,
                sequenceNumber = packet.acknowledgmentNumber,
                flags = TcpPacket.RST,
                windowSize = receiveWindow,
            )
        } else {
            TcpPacket(
                sourcePort = key.remotePort,
                destinationPort = key.peerPort,
                sequenceNumber = 0u,
                acknowledgmentNumber = packet.sequenceNumber + packet.sequenceSpaceLength.toUInt(),
                flags = TcpPacket.RST or TcpPacket.ACK,
                windowSize = receiveWindow,
            )
        }

    private fun Flow.snapshot(): TcpFlowSnapshot =
        TcpFlowSnapshot(
            key = key,
            state = state,
            peerNextSequence = peerNextSequence,
            localNextSequence = localNextSequence,
            localAcknowledgedSequence = localAcknowledgedSequence,
        )
}
