package no.skasti.serialmodem.ppp

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

data class UdpFlow(
    val peerPort: Int,
    val destination: Ipv4Address,
    val destinationPort: Int,
    val generation: Long,
) {
    init {
        require(peerPort in 0..0xffff) { "UDP peer port must be 0..65535" }
        require(destinationPort in 0..0xffff) { "UDP destination port must be 0..65535" }
    }
}

interface UdpProxy : Closeable {
    fun send(
        flow: UdpFlow,
        payload: ByteArray,
        onReply: (Result<ByteArray>) -> Unit,
    )

    override fun close() = Unit
}

class SystemUdpProxy(
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
    private val maxFlows: Int = DEFAULT_MAX_FLOWS,
    maxQueuedCommands: Int = DEFAULT_MAX_QUEUED_COMMANDS,
) : UdpProxy {
    private data class SendCommand(
        val flow: UdpFlow,
        val payload: ByteArray,
        val onReply: (Result<ByteArray>) -> Unit,
    )

    private data class FlowState(
        val flow: UdpFlow,
        val channel: DatagramChannel,
        var onReply: (Result<ByteArray>) -> Unit,
        var lastActivityNanos: Long,
    )

    private val selector = Selector.open()
    private val commands = ArrayBlockingQueue<SendCommand>(maxQueuedCommands)
    private val flows = mutableMapOf<UdpFlow, FlowState>()
    private val idleTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis)

    @Volatile
    private var closed = false

    private val worker = Thread(::runLoop, "udp-proxy").apply {
        isDaemon = true
        start()
    }

    init {
        require(idleTimeoutMillis > 0) { "idleTimeoutMillis must be positive" }
        require(maxFlows > 0) { "maxFlows must be positive" }
        require(maxQueuedCommands > 0) { "maxQueuedCommands must be positive" }
    }

    override fun send(
        flow: UdpFlow,
        payload: ByteArray,
        onReply: (Result<ByteArray>) -> Unit,
    ) {
        if (closed) {
            onReply(Result.failure(IllegalStateException("UDP proxy is closed")))
            return
        }

        val accepted = commands.offer(
            SendCommand(
                flow = flow,
                payload = payload.copyOf(),
                onReply = onReply,
            ),
        )
        if (!accepted) {
            onReply(Result.failure(IllegalStateException("UDP proxy is busy")))
            return
        }
        selector.wakeup()
    }

    private fun runLoop() {
        try {
            while (!closed) {
                drainCommands()
                selector.select(SELECT_TIMEOUT_MILLIS)
                drainCommands()
                receiveReplies()
                expireIdleFlows()
            }
        } finally {
            flows.values.forEach { state ->
                runCatching { state.channel.close() }
            }
            flows.clear()
            runCatching { selector.close() }
            commands.clear()
        }
    }

    private fun drainCommands() {
        while (true) {
            val command = commands.poll() ?: return
            if (closed) return
            sendCommand(command)
        }
    }

    private fun sendCommand(command: SendCommand) {
        var state = flows[command.flow]
        if (state == null) {
            if (flows.size >= maxFlows) {
                safeCallback(
                    command.onReply,
                    Result.failure(IllegalStateException("UDP flow limit reached")),
                )
                return
            }

            state = runCatching { createFlow(command.flow, command.onReply) }
                .getOrElse { error ->
                    safeCallback(command.onReply, Result.failure(error))
                    return
                }
            flows[command.flow] = state
        } else {
            state.onReply = command.onReply
        }

        try {
            val written = state.channel.write(ByteBuffer.wrap(command.payload))
            if (written != command.payload.size) {
                removeFlow(state)
                safeCallback(
                    command.onReply,
                    Result.failure(
                        IllegalStateException("UDP datagram could not be sent immediately"),
                    ),
                )
                return
            }
            state.lastActivityNanos = System.nanoTime()
        } catch (e: Exception) {
            removeFlow(state)
            safeCallback(command.onReply, Result.failure(e))
        }
    }

    private fun createFlow(
        flow: UdpFlow,
        onReply: (Result<ByteArray>) -> Unit,
    ): FlowState {
        val channel = DatagramChannel.open(StandardProtocolFamily.INET)
        try {
            channel.configureBlocking(false)
            channel.bind(InetSocketAddress(0))
            val address = InetAddress.getByAddress(flow.destination.toByteArray())
            channel.connect(InetSocketAddress(address, flow.destinationPort))
            val state = FlowState(
                flow = flow,
                channel = channel,
                onReply = onReply,
                lastActivityNanos = System.nanoTime(),
            )
            channel.register(selector, SelectionKey.OP_READ, state)
            return state
        } catch (e: Exception) {
            runCatching { channel.close() }
            throw e
        }
    }

    private fun receiveReplies() {
        val selected = selector.selectedKeys().iterator()
        while (selected.hasNext()) {
            val key = selected.next()
            selected.remove()
            if (!key.isValid || !key.isReadable) continue

            val state = key.attachment() as? FlowState ?: continue
            receiveFromFlow(state)
        }
    }

    private fun receiveFromFlow(state: FlowState) {
        val buffer = ByteBuffer.allocate(MAX_UDP_PAYLOAD_LENGTH)
        try {
            while (true) {
                buffer.clear()
                val count = state.channel.read(buffer)
                if (count <= 0) return

                val payload = ByteArray(count)
                buffer.flip()
                buffer.get(payload)
                state.lastActivityNanos = System.nanoTime()
                safeCallback(state.onReply, Result.success(payload))
            }
        } catch (e: Exception) {
            removeFlow(state)
            safeCallback(state.onReply, Result.failure(e))
        }
    }

    private fun expireIdleFlows() {
        val cutoff = System.nanoTime() - idleTimeoutNanos
        val expired = flows.values.filter { it.lastActivityNanos <= cutoff }
        expired.forEach(::removeFlow)
    }

    private fun removeFlow(state: FlowState) {
        if (flows.remove(state.flow, state)) {
            state.channel.keyFor(selector)?.cancel()
            runCatching { state.channel.close() }
        }
    }

    private fun safeCallback(
        callback: (Result<ByteArray>) -> Unit,
        result: Result<ByteArray>,
    ) {
        runCatching { callback(result) }
    }

    override fun close() {
        if (closed) return
        closed = true
        selector.wakeup()
        if (Thread.currentThread() !== worker) {
            runCatching { worker.join(CLOSE_JOIN_TIMEOUT_MILLIS) }
        }
    }

    companion object {
        private const val DEFAULT_IDLE_TIMEOUT_MILLIS = 60_000L
        private const val DEFAULT_MAX_FLOWS = 64
        private const val DEFAULT_MAX_QUEUED_COMMANDS = 256
        private const val SELECT_TIMEOUT_MILLIS = 250L
        private const val CLOSE_JOIN_TIMEOUT_MILLIS = 1_000L
        private const val MAX_UDP_PAYLOAD_LENGTH = 65_507
    }
}
