package no.skasti.serialmodem.ppp.http

import no.skasti.serialmodem.ppp.tcp.TcpProxy
import no.skasti.serialmodem.ppp.tcp.TcpProxyEvent
import no.skasti.serialmodem.ppp.tcp.TcpProxyFlow
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class HttpConnection(
    val flow: TcpProxyFlow,
    private val active: () -> Boolean,
) {
    val isActive: Boolean
        get() = active()
}

internal interface HttpRequestHandler : Closeable {
    fun handle(
        connection: HttpConnection,
        request: HttpRequest,
    ): HttpResponse

    fun connectionClosed(flow: TcpProxyFlow) = Unit

    fun invalidateBefore(generation: Long) = Unit

    override fun close() = Unit
}

internal class HttpTcpProxy(
    private val handler: HttpRequestHandler,
    private val maxFlows: Int,
    private val maxRequestBytes: Int,
    private val responseChunkBytes: Int = DEFAULT_RESPONSE_CHUNK_BYTES,
    private val shutdownTimeoutMillis: Long = SHUTDOWN_TIMEOUT_MILLIS,
) : TcpProxy {
    init {
        require(maxFlows > 0) { "maxFlows must be positive" }
        require(maxRequestBytes > 0) { "maxRequestBytes must be positive" }
        require(responseChunkBytes > 0) { "responseChunkBytes must be positive" }
        require(shutdownTimeoutMillis > 0) { "shutdownTimeoutMillis must be positive" }
    }

    private data class FlowState(
        val flow: TcpProxyFlow,
        val onEvent: (TcpProxyEvent) -> Unit,
        val decoder: HttpRequestDecoder,
        val slotReleased: AtomicBoolean = AtomicBoolean(),
        val readMonitor: Object = Object(),
        @Volatile var processing: Boolean = false,
        @Volatile var readsPaused: Boolean = false,
        @Volatile var cancelled: Boolean = false,
        @Volatile var task: Future<*>? = null,
        @Volatile var interimTask: Future<*>? = null,
    )

    private val executor = Executors.newFixedThreadPool(maxFlows) { runnable ->
        Thread(runnable, "http-tcp-proxy").apply { isDaemon = true }
    }
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
            safeCallback(onEvent, TcpProxyEvent.Failure(IllegalStateException("HTTP proxy is closed")))
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
            safeCallback(onEvent, TcpProxyEvent.Failure(IllegalStateException("HTTP flow limit reached")))
            return
        }

        val state =
            FlowState(
                flow = flow,
                onEvent = onEvent,
                decoder = HttpRequestDecoder(maxRequestBytes),
            )
        if (flows.putIfAbsent(flow, state) != null) {
            releaseSlot(state)
            safeCallback(onEvent, TcpProxyEvent.Failure(IllegalStateException("HTTP flow already exists")))
            return
        }
        safeCallback(onEvent, TcpProxyEvent.Connected)
    }

    override fun send(
        flow: TcpProxyFlow,
        payload: ByteArray,
    ): Result<Unit> {
        if (payload.isEmpty()) return Result.success(Unit)
        val state = flows[flow]
            ?: return Result.failure(IllegalStateException("HTTP flow is not connected"))

        val decoded =
            synchronized(state) {
                if (state.cancelled) {
                    return Result.failure(IllegalStateException("HTTP flow is closed"))
                }
                if (state.processing) {
                    return Result.failure(IllegalStateException("HTTP pipelining is not supported"))
                }
                state.decoder.accept(payload).also { result ->
                    if (
                        result is HttpRequestDecodeResult.Complete ||
                        result is HttpRequestDecodeResult.Rejected
                    ) {
                        state.processing = true
                    }
                }
            }

        safeCallback(state.onEvent, TcpProxyEvent.WriteCompleted(payload.size))

        return when (decoded) {
            HttpRequestDecodeResult.NeedMoreData -> Result.success(Unit)
            HttpRequestDecodeResult.ContinueRequired ->
                scheduleInterimResponse(
                    state,
                    "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII),
                )
            is HttpRequestDecodeResult.Rejected -> {
                scheduleResponse(
                    state,
                    HttpResponse(
                        statusCode = decoded.status,
                        reasonPhrase = decoded.reason,
                        headers = listOf("Content-Type" to "text/plain; charset=us-ascii"),
                        body = "${decoded.status} ${decoded.reason}\r\n${decoded.message}\r\n"
                            .toByteArray(Charsets.US_ASCII),
                    ).withTransportHeaders(),
                )
            }
            is HttpRequestDecodeResult.Complete -> scheduleRequest(state, decoded.request)
        }
    }

    private fun scheduleInterimResponse(
        state: FlowState,
        payload: ByteArray,
    ): Result<Unit> {
        return try {
            lateinit var task: FutureTask<Unit>
            task =
                FutureTask {
                    try {
                        emitBytes(state, payload)
                    } finally {
                        if (state.interimTask === task) {
                            state.interimTask = null
                        }
                    }
                }
            state.interimTask = task
            executor.execute(task)
            if (!isActive(state)) task.cancel(true)
            Result.success(Unit)
        } catch (error: Throwable) {
            state.interimTask = null
            removeFlow(state)
            safeCallback(state.onEvent, TcpProxyEvent.Failure(error))
            Result.failure(error)
        }
    }

    private fun scheduleRequest(
        state: FlowState,
        request: HttpRequest,
    ): Result<Unit> =
        schedule(state) {
            state.interimTask?.let { runCatching { it.get() } }
            val connection =
                HttpConnection(state.flow) {
                    !closed &&
                        !state.cancelled &&
                        state.flow.generation >= minimumGeneration.get() &&
                        flows[state.flow] === state
                }
            val response = handler.handle(connection, request).withTransportHeaders()
            emitResponse(state, response)
        }

    private fun scheduleResponse(
        state: FlowState,
        response: HttpResponse,
    ): Result<Unit> =
        schedule(state) {
            state.interimTask?.let { runCatching { it.get() } }
            emitResponse(state, response)
        }

    private fun schedule(
        state: FlowState,
        work: () -> Unit,
    ): Result<Unit> {
        return try {
            val task =
                object : FutureTask<Unit>(
                    java.util.concurrent.Callable {
                        try {
                            work()
                            finishFlow(state)
                        } catch (error: Throwable) {
                            if (isActive(state)) {
                                removeFlow(state)
                                safeCallback(state.onEvent, TcpProxyEvent.Failure(error))
                            }
                        } finally {
                            synchronized(state) {
                                state.processing = false
                            }
                        }
                    },
                ) {
                    override fun run() {
                        try {
                            super.run()
                        } finally {
                            synchronized(state) {
                                if (state.task === this) {
                                    state.task = null
                                }
                            }
                            if (flows[state.flow] !== state) {
                                releaseSlot(state)
                            }
                        }
                    }
                }
            val published =
                synchronized(state) {
                    if (state.cancelled || flows[state.flow] !== state) {
                        false
                    } else {
                        state.task = task
                        true
                    }
                }
            if (!published) {
                synchronized(state) {
                    state.processing = false
                }
                return Result.failure(IllegalStateException("HTTP flow is closed"))
            }

            try {
                executor.execute(task)
            } catch (error: Throwable) {
                synchronized(state) {
                    state.processing = false
                    if (state.task === task) {
                        state.task = null
                    }
                }
                if (!removeFlow(state) && flows[state.flow] !== state) {
                    releaseSlot(state)
                }
                safeCallback(state.onEvent, TcpProxyEvent.Failure(error))
                return Result.failure(error)
            }
            if (!isActive(state)) task.cancel(true)
            Result.success(Unit)
        } catch (error: Throwable) {
            synchronized(state) {
                state.processing = false
            }
            removeFlow(state)
            safeCallback(state.onEvent, TcpProxyEvent.Failure(error))
            Result.failure(error)
        }
    }

    override fun shutdownOutput(flow: TcpProxyFlow): Result<Unit> {
        val state = flows[flow]
            ?: return Result.failure(IllegalStateException("HTTP flow is not connected"))

        val endResult =
            synchronized(state) {
                if (state.processing) {
                    HttpRequestDecodeResult.NeedMoreData
                } else {
                    state.decoder.endOfInput()
                }
            }
        return when (endResult) {
            is HttpRequestDecodeResult.Rejected -> {
                synchronized(state) { state.processing = true }
                scheduleResponse(
                    state,
                    HttpResponse(
                        statusCode = endResult.status,
                        reasonPhrase = endResult.reason,
                        headers = listOf("Content-Type" to "text/plain; charset=us-ascii"),
                        body = "${endResult.status} ${endResult.reason}\r\n${endResult.message}\r\n"
                            .toByteArray(Charsets.US_ASCII),
                    ).withTransportHeaders(),
                )
            }
            else -> {
                if (!state.processing && state.decoder.bufferedBytes == 0) {
                    finishFlow(state)
                }
                Result.success(Unit)
            }
        }
    }

    override fun availableWriteCapacity(flow: TcpProxyFlow): Int {
        val state = flows[flow] ?: return 0
        synchronized(state) {
            if (state.processing || state.cancelled) return 0
            return state.decoder.availableCapacity
        }
    }

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

    override fun closeFlow(flow: TcpProxyFlow) {
        flows[flow]?.let(::removeFlow)
    }

    override fun invalidateBefore(generation: Long) {
        minimumGeneration.accumulateAndGet(generation, ::maxOf)
        handler.invalidateBefore(generation)
        flows.values
            .filter { it.flow.generation < generation }
            .forEach(::removeFlow)
    }

    private fun emitResponse(
        state: FlowState,
        response: HttpResponse,
    ) {
        emitBytes(state, HttpResponseEncoder.encodeHead(response))
        HttpResponseEncoder.bodyChunks(response, responseChunkBytes).forEach { chunk ->
            emitBytes(state, chunk)
        }
    }

    private fun emitBytes(
        state: FlowState,
        payload: ByteArray,
    ) {
        if (payload.isEmpty() || !isActive(state)) return
        awaitReadsEnabled(state)
        if (isActive(state)) {
            safeCallback(state.onEvent, TcpProxyEvent.Payload(payload.copyOf()))
        }
    }

    private fun awaitReadsEnabled(state: FlowState) {
        synchronized(state.readMonitor) {
            while (state.readsPaused && isActive(state)) {
                state.readMonitor.wait()
            }
        }
    }

    private fun finishFlow(state: FlowState) {
        if (!isActive(state)) return
        safeCallback(state.onEvent, TcpProxyEvent.EndOfStream)
        removeFlow(state, cancelTask = false)
    }

    private fun isActive(state: FlowState): Boolean =
        !closed &&
            !state.cancelled &&
            state.flow.generation >= minimumGeneration.get() &&
            flows[state.flow] === state

    private fun removeFlow(
        state: FlowState,
        cancelTask: Boolean = true,
    ): Boolean {
        val taskToCancel: Future<*>?
        val interimToCancel: Future<*>?
        val releaseImmediately: Boolean
        synchronized(state) {
            if (!flows.remove(state.flow, state)) return false
            state.cancelled = true
            taskToCancel = if (cancelTask) state.task else null
            interimToCancel = if (cancelTask) state.interimTask else null
            releaseImmediately = state.task == null
        }
        synchronized(state.readMonitor) {
            state.readsPaused = false
            state.readMonitor.notifyAll()
        }
        taskToCancel?.cancel(true)
        interimToCancel?.cancel(true)
        handler.connectionClosed(state.flow)
        if (releaseImmediately) {
            releaseSlot(state)
        }
        return true
    }

    private fun releaseSlot(state: FlowState) {
        if (state.slotReleased.compareAndSet(false, true)) {
            flowSlots.release()
        }
    }

    private fun safeCallback(
        callback: (TcpProxyEvent) -> Unit,
        event: TcpProxyEvent,
    ) {
        runCatching { callback(event) }
    }

    private fun HttpResponse.withTransportHeaders(): HttpResponse {
        val existingContentLength =
            headers.firstOrNull { (name, _) ->
                name.equals("Content-Length", ignoreCase = true)
            }?.second
        val filtered =
            headers.filterNot { (name, _) ->
                name.equals("Content-Length", ignoreCase = true) ||
                    name.equals("Connection", ignoreCase = true)
            }
        return copy(
            headers =
                filtered +
                    listOf(
                        "Content-Length" to (existingContentLength ?: body.size.toString()),
                        "Connection" to "close",
                    ),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        flows.values.toList().forEach(::removeFlow)
        executor.shutdownNow()

        val terminated =
            try {
                executor.awaitTermination(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }

        if (terminated) {
            handler.close()
        } else {
            Thread(
                {
                    try {
                        while (!executor.awaitTermination(1, TimeUnit.DAYS)) {
                            // Keep waiting until all HTTP workers have actually exited.
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                    handler.close()
                },
                "http-tcp-proxy-close",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private companion object {
        const val DEFAULT_RESPONSE_CHUNK_BYTES = 4_096
        const val SHUTDOWN_TIMEOUT_MILLIS = 2_000L
    }
}
