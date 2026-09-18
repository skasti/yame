package no.skasti.yame.ppp.tcp

import no.skasti.yame.ppp.ip.Ipv4Address
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class TcpProxyFlow(
    val key: TcpFlowKey,
    val generation: Long,
    val connectAddress: Ipv4Address = key.remoteAddress,
    val connectPort: Int = key.remotePort,
    val readTimeoutMillis: Int = 0,
) {
    init {
        require(connectPort in 1..0xffff) { "TCP connect port must be 1..65535" }
        require(readTimeoutMillis >= 0) { "TCP read timeout must not be negative" }
    }
}

sealed interface TcpProxyEvent {
    data object Connected : TcpProxyEvent

    data class Payload(val bytes: ByteArray) : TcpProxyEvent

    data object EndOfStream : TcpProxyEvent

    data class WriteCompleted(val bytes: Int) : TcpProxyEvent

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

    fun availableWriteCapacity(flow: TcpProxyFlow): Int = 0xffff

    fun pauseReads(flow: TcpProxyFlow) = Unit

    fun resumeReads(flow: TcpProxyFlow) = Unit

    fun closeFlow(flow: TcpProxyFlow)

    fun invalidateBefore(generation: Long) = Unit

    override fun close() = Unit
}

class SystemTcpProxy(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readBufferSize: Int = DEFAULT_READ_BUFFER_SIZE,
    private val maxFlows: Int = DEFAULT_MAX_FLOWS,
    private val maxQueuedWriteBytes: Int = DEFAULT_MAX_QUEUED_WRITE_BYTES,
    private val connectOperation: (Socket, TcpProxyFlow, Int) -> Unit = ::connectSocket,
    private val writeOperation: (Socket, ByteArray) -> Unit = ::writeSocket,
) : TcpProxy {
    private sealed interface WriteCommand {
        data class Payload(val bytes: ByteArray) : WriteCommand
        data object ShutdownOutput : WriteCommand
        data object Stop : WriteCommand
    }

    private data class FlowState(
        val flow: TcpProxyFlow,
        val socket: Socket,
        val onEvent: (TcpProxyEvent) -> Unit,
        val writes: LinkedBlockingQueue<WriteCommand>,
        val queuedWriteBytes: AtomicInteger = AtomicInteger(),
        val readMonitor: Object = Object(),
        @Volatile var connected: Boolean = false,
        @Volatile var readsPaused: Boolean = false,
    )

    init {
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readBufferSize > 0) { "readBufferSize must be positive" }
        require(maxFlows > 0) { "maxFlows must be positive" }
        require(maxQueuedWriteBytes in 1..0xffff) {
            "maxQueuedWriteBytes must be 1..65535"
        }
    }

    private val threadNumber = AtomicInteger()
    private val readExecutor = Executors.newFixedThreadPool(
        maxFlows,
        daemonThreadFactory("tcp-proxy-read"),
    )
    private val writeExecutor = Executors.newFixedThreadPool(
        maxFlows,
        daemonThreadFactory("tcp-proxy-write"),
    )
    private val flowSlots = Semaphore(maxFlows)
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
        if (!flowSlots.tryAcquire()) {
            safeCallback(onEvent, TcpProxyEvent.Failure(IllegalStateException("TCP flow limit reached")))
            return
        }

        val state = try {
            FlowState(
                flow = flow,
                socket = Socket(),
                onEvent = onEvent,
                writes = LinkedBlockingQueue(),
            )
        } catch (error: Throwable) {
            flowSlots.release()
            safeCallback(onEvent, TcpProxyEvent.Failure(error))
            return
        }

        val previous = flows.putIfAbsent(flow, state)
        if (previous != null) {
            runCatching { state.socket.close() }
            flowSlots.release()
            safeCallback(
                onEvent,
                TcpProxyEvent.Failure(IllegalStateException("TCP flow is already connecting or connected")),
            )
            return
        }

        try {
            readExecutor.execute {
                connectAndRead(state)
            }
        } catch (error: Throwable) {
            removeFlow(state)
            safeCallback(onEvent, TcpProxyEvent.Failure(error))
        }
    }

    private fun connectAndRead(state: FlowState) {
        try {
            connectOperation(state.socket, state.flow, connectTimeoutMillis)
            state.socket.tcpNoDelay = true
            if (state.flow.readTimeoutMillis > 0) {
                state.socket.soTimeout = state.flow.readTimeoutMillis
            }

            if (
                closed ||
                state.flow.generation < minimumGeneration.get() ||
                flows[state.flow] !== state
            ) {
                removeFlow(state)
                return
            }

            state.connected = true
            writeExecutor.execute {
                writeLoop(state)
            }
            safeCallback(state.onEvent, TcpProxyEvent.Connected)
            readLoop(state)
        } catch (error: Throwable) {
            if (flows[state.flow] === state) {
                removeFlow(state)
                if (!closed && state.flow.generation >= minimumGeneration.get()) {
                    safeCallback(state.onEvent, TcpProxyEvent.Failure(error))
                }
            }
        }
    }

    private fun readLoop(state: FlowState) {
        val buffer = ByteArray(readBufferSize)
        try {
            val input = state.socket.getInputStream()
            while (!closed && flows[state.flow] === state) {
                awaitReadsEnabled(state)
                if (closed || flows[state.flow] !== state) return
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

    private fun writeLoop(state: FlowState) {
        try {
            while (!closed && flows[state.flow] === state) {
                when (val command = state.writes.take()) {
                    is WriteCommand.Payload -> {
                        writeOperation(state.socket, command.bytes)
                        state.queuedWriteBytes.addAndGet(-command.bytes.size)
                        safeCallback(
                            state.onEvent,
                            TcpProxyEvent.WriteCompleted(command.bytes.size),
                        )
                    }
                    WriteCommand.ShutdownOutput -> {
                        if (!state.socket.isOutputShutdown) {
                            state.socket.shutdownOutput()
                        }
                    }
                    WriteCommand.Stop -> return
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
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
        if (payload.isEmpty()) return Result.success(Unit)
        val state = connectedState(flow)
            ?: return Result.failure(IllegalStateException("TCP flow is not connected"))

        if (!reserveWriteBytes(state, payload.size)) {
            return Result.failure(IllegalStateException("TCP host write buffer is full"))
        }

        return runCatching {
            state.writes.put(WriteCommand.Payload(payload.copyOf()))
        }.onFailure {
            state.queuedWriteBytes.addAndGet(-payload.size)
        }
    }

    override fun shutdownOutput(flow: TcpProxyFlow): Result<Unit> {
        val state = connectedState(flow)
            ?: return Result.failure(IllegalStateException("TCP flow is not connected"))

        return runCatching {
            state.writes.put(WriteCommand.ShutdownOutput)
        }
    }

    override fun availableWriteCapacity(flow: TcpProxyFlow): Int {
        val state = connectedState(flow) ?: return 0
        return (maxQueuedWriteBytes - state.queuedWriteBytes.get()).coerceAtLeast(0)
    }

    private fun reserveWriteBytes(
        state: FlowState,
        bytes: Int,
    ): Boolean {
        if (bytes <= 0 || bytes > maxQueuedWriteBytes) return false

        while (true) {
            val current = state.queuedWriteBytes.get()
            if (current + bytes > maxQueuedWriteBytes) return false
            if (state.queuedWriteBytes.compareAndSet(current, current + bytes)) {
                return true
            }
        }
    }

    private fun connectedState(flow: TcpProxyFlow): FlowState? =
        flows[flow]?.takeIf { it.connected }

    override fun pauseReads(flow: TcpProxyFlow) {
        val state = flows[flow] ?: return
        synchronized(state.readMonitor) {
            state.readsPaused = true
        }
    }

    override fun resumeReads(flow: TcpProxyFlow) {
        val state = flows[flow] ?: return
        synchronized(state.readMonitor) {
            state.readsPaused = false
            state.readMonitor.notifyAll()
        }
    }

    private fun awaitReadsEnabled(state: FlowState) {
        synchronized(state.readMonitor) {
            while (
                state.readsPaused &&
                !closed &&
                flows[state.flow] === state
            ) {
                state.readMonitor.wait()
            }
        }
    }

    override fun closeFlow(flow: TcpProxyFlow) {
        flows[flow]?.let(::removeFlow)
    }

    private fun removeFlow(state: FlowState) {
        if (flows.remove(state.flow, state)) {
            synchronized(state.readMonitor) {
                state.readsPaused = false
                state.readMonitor.notifyAll()
            }
            runCatching { state.socket.close() }
            state.writes.clear()
            state.queuedWriteBytes.set(0)
            state.writes.offer(WriteCommand.Stop)
            flowSlots.release()
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
        readExecutor.shutdownNow()
        writeExecutor.shutdownNow()
        runCatching { readExecutor.awaitTermination(CLOSE_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        runCatching { writeExecutor.awaitTermination(CLOSE_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
    }

    private fun daemonThreadFactory(prefix: String): ThreadFactory =
        ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${threadNumber.incrementAndGet()}").apply {
                isDaemon = true
            }
        }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
        private const val DEFAULT_READ_BUFFER_SIZE = 4_096
        private const val DEFAULT_MAX_FLOWS = 64
        private const val DEFAULT_MAX_QUEUED_WRITE_BYTES = 0xffff
        private const val CLOSE_JOIN_TIMEOUT_MILLIS = 1_000L

        private fun connectSocket(
            socket: Socket,
            flow: TcpProxyFlow,
            timeoutMillis: Int,
        ) {
            val address = InetAddress.getByAddress(flow.connectAddress.toByteArray())
            socket.connect(
                InetSocketAddress(address, flow.connectPort),
                timeoutMillis,
            )
        }

        private fun writeSocket(
            socket: Socket,
            payload: ByteArray,
        ) {
            socket.getOutputStream().write(payload)
            socket.getOutputStream().flush()
        }
    }
}
