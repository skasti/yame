package no.skasti.serialmodem.ppp.tcp

import no.skasti.serialmodem.ppp.ip.Ipv4Address
import java.util.ArrayDeque
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
    val peerWindowSize: Int,
    val availableSendWindow: Int,
    val peerMaximumSegmentSize: Int,
    val localReceiveWindow: Int,
    val unacknowledgedSegments: Int,
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
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private data class OutstandingSegment(
        var packet: TcpPacket,
        var lastSentNanos: Long,
    )

    private data class Flow(
        val key: TcpFlowKey,
        val peerInitialSequence: UInt,
        val localInitialSequence: UInt,
        var peerNextSequence: UInt,
        var localNextSequence: UInt,
        var localAcknowledgedSequence: UInt,
        var peerWindowSize: Int,
        var peerMaximumSegmentSize: Int,
        var localReceiveWindow: Int,
        val outstanding: ArrayDeque<OutstandingSegment> = ArrayDeque(),
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

        if (
            payload.size > availableSendWindow(flow) ||
            payload.size > flow.peerMaximumSegmentSize
        ) {
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
        if (availableSendWindow(flow) < 1) return null

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

    @Synchronized
    fun setReceiveWindow(
        key: TcpFlowKey,
        windowSize: Int,
    ): Boolean {
        require(windowSize in 0..0xffff) { "TCP receive window must be 0..65535" }
        val flow = flows[key] ?: return false
        flow.localReceiveWindow = windowSize
        return true
    }

    @Synchronized
    fun acknowledgment(key: TcpFlowKey): TcpPacket? =
        flows[key]?.let(::ack)

    @Synchronized
    fun markSent(
        key: TcpFlowKey,
        packet: TcpPacket,
    ) {
        if (packet.sequenceSpaceLength <= 0) return
        val flow = flows[key] ?: return
        val now = nanoTime()
        val existing = flow.outstanding.firstOrNull { outstanding ->
            outstanding.packet.sequenceNumber == packet.sequenceNumber &&
                outstanding.packet.sequenceSpaceLength == packet.sequenceSpaceLength
        }
        if (existing != null) {
            existing.packet = packet.copy(payload = packet.payload.copyOf())
            existing.lastSentNanos = now
            return
        }
        flow.outstanding.addLast(
            OutstandingSegment(
                packet = packet.copy(payload = packet.payload.copyOf()),
                lastSentNanos = now,
            ),
        )
    }

    @Synchronized
    fun retransmissionDue(
        key: TcpFlowKey,
        timeoutMillis: Long,
    ): TcpPacket? {
        require(timeoutMillis > 0) { "TCP retransmission timeout must be positive" }
        val flow = flows[key] ?: return null
        val outstanding = flow.outstanding.firstOrNull() ?: return null
        val timeoutNanos = timeoutMillis * NANOS_PER_MILLISECOND
        if (nanoTime() - outstanding.lastSentNanos < timeoutNanos) return null

        return outstanding.packet.copy(
            acknowledgmentNumber = flow.peerNextSequence,
            windowSize = flow.localReceiveWindow,
            payload = outstanding.packet.payload.copyOf(),
        )
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
                peerWindowSize = packet.windowSize,
                peerMaximumSegmentSize = peerMaximumSegmentSize(packet),
                localReceiveWindow = receiveWindow,
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
            flow.peerWindowSize = packet.windowSize
            flow.peerMaximumSegmentSize = peerMaximumSegmentSize(packet)
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

        acknowledgeLocal(flow, packet.acknowledgmentNumber)
        flow.peerWindowSize = packet.windowSize
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
            if (isSequenceAfter(packet.acknowledgmentNumber, flow.localNextSequence)) {
                return TcpFlowResult(responses = listOf(ack(flow)))
            }

            val acknowledgmentAccepted = acknowledgeLocal(
                flow,
                packet.acknowledgmentNumber,
            )
            if (acknowledgmentAccepted) {
                flow.peerWindowSize = packet.windowSize
            }
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
    ): Boolean {
        val outstanding = forwardDistance(
            flow.localAcknowledgedSequence,
            flow.localNextSequence,
        )
        val acknowledged = forwardDistance(
            flow.localAcknowledgedSequence,
            acknowledgmentNumber,
        )
        if (acknowledged > outstanding) return false

        acknowledgeOutstandingSegments(flow, acknowledged.toLong())
        flow.localAcknowledgedSequence = acknowledgmentNumber
        return true
    }

    private fun peerMaximumSegmentSize(packet: TcpPacket): Int =
        packet.maximumSegmentSizeOption()
            ?.takeIf { it > 0 }
            ?: TcpPacket.DEFAULT_IPV4_MAXIMUM_SEGMENT_SIZE

    private fun acknowledgeOutstandingSegments(
        flow: Flow,
        acknowledgedSequenceSpace: Long,
    ) {
        var remaining = acknowledgedSequenceSpace
        while (remaining > 0 && flow.outstanding.isNotEmpty()) {
            val outstanding = flow.outstanding.first()
            val length = outstanding.packet.sequenceSpaceLength
            if (remaining >= length) {
                flow.outstanding.removeFirst()
                remaining -= length
                continue
            }

            outstanding.packet = trimAcknowledgedPrefix(
                outstanding.packet,
                remaining.toInt(),
            )
            remaining = 0
        }
    }

    private fun trimAcknowledgedPrefix(
        packet: TcpPacket,
        acknowledged: Int,
    ): TcpPacket {
        var remaining = acknowledged
        var sequence = packet.sequenceNumber
        var flags = packet.flags
        var payload = packet.payload

        if (remaining > 0 && flags and TcpPacket.SYN != 0) {
            flags = flags and TcpPacket.SYN.inv()
            sequence += 1u
            remaining--
        }

        if (remaining > 0 && payload.isNotEmpty()) {
            val consumed = minOf(remaining, payload.size)
            payload = payload.copyOfRange(consumed, payload.size)
            sequence += consumed.toUInt()
            remaining -= consumed
        }

        if (remaining > 0 && flags and TcpPacket.FIN != 0) {
            flags = flags and TcpPacket.FIN.inv()
            sequence += 1u
            remaining--
        }

        check(remaining == 0) { "ACK exceeds tracked TCP segment sequence space" }
        return packet.copy(
            sequenceNumber = sequence,
            flags = flags,
            payload = payload,
        )
    }

    private fun availableSendWindow(flow: Flow): Int {
        val inFlight = forwardDistance(
            flow.localAcknowledgedSequence,
            flow.localNextSequence,
        ).toLong()
        return (flow.peerWindowSize.toLong() - inFlight)
            .coerceAtLeast(0)
            .toInt()
    }

    private fun forwardDistance(
        from: UInt,
        to: UInt,
    ): UInt = to - from

    private fun isSequenceAfter(
        candidate: UInt,
        reference: UInt,
    ): Boolean {
        val distance = candidate - reference
        return distance != 0u && distance < HALF_SEQUENCE_SPACE
    }


    private fun synAck(flow: Flow): TcpPacket =
        TcpPacket(
            sourcePort = flow.key.remotePort,
            destinationPort = flow.key.peerPort,
            sequenceNumber = flow.localInitialSequence,
            acknowledgmentNumber = flow.peerNextSequence,
            flags = TcpPacket.SYN or TcpPacket.ACK,
            windowSize = flow.localReceiveWindow,
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
            windowSize = flow.localReceiveWindow,
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
            peerWindowSize = peerWindowSize,
            availableSendWindow = availableSendWindow(this),
            peerMaximumSegmentSize = peerMaximumSegmentSize,
            localReceiveWindow = localReceiveWindow,
            unacknowledgedSegments = outstanding.size,
        )
    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val HALF_SEQUENCE_SPACE = 0x80000000u
    }

}
