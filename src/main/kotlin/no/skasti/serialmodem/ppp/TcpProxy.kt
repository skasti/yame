package no.skasti.serialmodem.ppp

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class TcpProxyFlow(
    val key: TcpFlowKey,
    val generation: Long,
)

sealed interface TcpProxyEvent {
    data object Connected : TcpProxyEvent

    data class Payload(val bytes: ByteArray) : TcpProxyEvent

    data object EndOfStream : TcpProxyEvent

    data class Failure(val error: Throwable) : TcpProxyEvent
}

interface TcpProxy : Closeable {
    fun connect(
        flow: TcpProxyFlow,
        onEvent: (TcpProxyEvent) -> Unit,
    )

    fun send(
        flow: TcpProxyFlow,
        payload: ByteArray,
    ): Result<Unit>

    fun shutdownOutput(flow: TcpProxyFlow): Result<Unit>

    fun closeFlow(flow: TcpProxyFlow)

    fun invalidateBefore(generation: Long) = Unit

    override fun close() = Unit
}

class SystemTcpProxy(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readBufferSize: Int = DEFAULT_READ_BUFFER_SIZE,
    private val maxFlows: Int = DEFAULT_MAX_FLOWS,
) : TcpProxy {
    private data class FlowState(
        val flow: TcpProxyFlow,
        val socket: Socket,
        val onEvent: (TcpProxyEvent) -> Unit,
        val writeLock: Any = Any(),
    )

    init {
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readBufferSize > 0) { "readBufferSize must be positive" }
        require(maxFlows > 0) { "maxFlows must be positive" }
    }

    private val executor = Executors.newCachedThreadPool(
        ThreadFactory { runnable ->
            Thread(runnable, "tcp-proxy").apply { isDaemon = true }
        },
    )
    private val flows = ConcurrentHashMap<TcpProxyFlow, FlowState>()
    private val minimumGeneration = AtomicLong(Long.MIN_VALUE)

    @Volatile
    private var closed = false

    override fun connect(
        flow: TcpProxyFlow,
        onEvent: (TcpProxyEvent) -> Unit,
    ) {
        if (closed) {
            safeCallback(onEvent, TcpProxyEvent.Failure(IllegalStateException("TCP proxy is closed")))
            return
        }
        if (flow.generation < minimumGeneration.get()) {
            safeCallback(
                onEvent,
                TcpProxyEvent.Failure(IllegalStateException("TCP flow belongs to an old IPCP generation")),
            )
            return
        }
        if (flows.size >= maxFlows) {
            safeCallback(onEvent, TcpProxyEvent.Failure(IllegalStateException("TCP flow limit reached")))
            return
        }

        executor.execute {
            connectAndRead(flow, onEvent)
        }
    }

    private fun connectAndRead(
        flow: TcpProxyFlow,
        onEvent: (TcpProxyEvent) -> Unit,
    ) {
        val socket = Socket()
        try {
            val address = InetAddress.getByAddress(flow.key.remoteAddress.toByteArray())
            socket.connect(
                InetSocketAddress(address, flow.key.remotePort),
                connectTimeoutMillis,
            )
            socket.tcpNoDelay = true

            if (closed || flow.generation < minimumGeneration.get()) {
                socket.close()
                return
            }

            val state = FlowState(flow = flow, socket = socket, onEvent = onEvent)
            val previous = flows.putIfAbsent(flow, state)
            if (previous != null) {
                socket.close()
                safeCallback(
                    onEvent,
                    TcpProxyEvent.Failure(IllegalStateException("TCP flow is already connected")),
                )
                return
            }

            safeCallback(onEvent, TcpProxyEvent.Connected)
            readLoop(state)
        } catch (error: Throwable) {
            runCatching { socket.close() }
            if (!closed && flow.generation >= minimumGeneration.get()) {
                safeCallback(onEvent, TcpProxyEvent.Failure(error))
            }
        }
    }

    private fun readLoop(state: FlowState) {
        val buffer = ByteArray(readBufferSize)
        try {
            val input = state.socket.getInputStream()
            while (!closed && flows[state.flow] === state) {
                val count = input.read(buffer)
                if (count < 0) {
                    safeCallback(state.onEvent, TcpProxyEvent.EndOfStream)
                    return
                }
                if (count == 0) continue
                safeCallback(
                    state.onEvent,
                    TcpProxyEvent.Payload(buffer.copyOf(count)),
                )
            }
        } catch (error: Throwable) {
            if (!closed && flows[state.flow] === state) {
                removeFlow(state)
                safeCallback(state.onEvent, TcpProxyEvent.Failure(error))
            }
        }
    }

    override fun send(
        flow: TcpProxyFlow,
        payload: ByteArray,
    ): Result<Unit> {
        val state = flows[flow]
            ?: return Result.failure(IllegalStateException("TCP flow is not connected"))

        return runCatching {
            synchronized(state.writeLock) {
                state.socket.getOutputStream().write(payload)
                state.socket.getOutputStream().flush()
            }
        }.onFailure {
            removeFlow(state)
        }
    }

    override fun shutdownOutput(flow: TcpProxyFlow): Result<Unit> {
        val state = flows[flow]
            ?: return Result.failure(IllegalStateException("TCP flow is not connected"))

        return runCatching {
            if (!state.socket.isOutputShutdown) {
                state.socket.shutdownOutput()
            }
        }.onFailure {
            removeFlow(state)
        }
    }

    override fun closeFlow(flow: TcpProxyFlow) {
        val state = flows.remove(flow) ?: return
        runCatching { state.socket.close() }
    }

    private fun removeFlow(state: FlowState) {
        if (flows.remove(state.flow, state)) {
            runCatching { state.socket.close() }
        }
    }

    override fun invalidateBefore(generation: Long) {
        minimumGeneration.accumulateAndGet(generation, ::maxOf)
        flows.values
            .filter { it.flow.generation < generation }
            .forEach(::removeFlow)
    }

    private fun safeCallback(
        callback: (TcpProxyEvent) -> Unit,
        event: TcpProxyEvent,
    ) {
        runCatching { callback(event) }
    }

    override fun close() {
        if (closed) return
        closed = true
        flows.values.toList().forEach(::removeFlow)
        executor.shutdownNow()
        runCatching { executor.awaitTermination(CLOSE_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
        private const val DEFAULT_READ_BUFFER_SIZE = 4_096
        private const val DEFAULT_MAX_FLOWS = 64
        private const val CLOSE_JOIN_TIMEOUT_MILLIS = 1_000L
    }
}
