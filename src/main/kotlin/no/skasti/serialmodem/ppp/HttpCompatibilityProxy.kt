package no.skasti.serialmodem.ppp

import no.skasti.serialmodem.observer.HttpProxyActionKind
import no.skasti.serialmodem.observer.YameEvent

import java.io.ByteArrayOutputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class PppHttpCompatibilityConfig(
    val enabled: Boolean = true,
    val maxRedirects: Int = 8,
    val requestTimeoutMillis: Long = 30_000,
    val maxRequestBytes: Int = 0xffff,
    val maxResponseBytes: Int = 16 * 1024 * 1024,
    val maxFlows: Int = 16,
) {
    init {
        require(maxRedirects >= 0) { "HTTP compatibility maxRedirects must not be negative" }
        require(requestTimeoutMillis > 0) { "HTTP compatibility request timeout must be positive" }
        require(maxRequestBytes in 1..0xffff) { "HTTP compatibility maxRequestBytes must be 1..65535" }
        require(maxResponseBytes > 0) { "HTTP compatibility maxResponseBytes must be positive" }
        require(maxFlows > 0) { "HTTP compatibility maxFlows must be positive" }
    }
}

internal object LegacyHttpsUrlCodec {
    private const val MARKER_PREFIX = "/.yame/https/"

    fun encode(uri: URI): String {
        require(uri.scheme.equals("https", ignoreCase = true)) { "Compatibility bootstrap target must be HTTPS" }
        val port = effectivePort(uri)
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        return buildString {
            append("http://")
            append(formatHost(requireNotNull(uri.host)))
            append(MARKER_PREFIX)
            append(port)
            append(path)
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }

    fun decodeLegacyUri(uri: URI): URI {
        if (!uri.scheme.equals("http", ignoreCase = true)) return uri
        val rawPath = uri.rawPath ?: return uri
        if (!rawPath.startsWith(MARKER_PREFIX)) return uri

        val encodedTarget = rawPath.removePrefix(MARKER_PREFIX)
        val separator = encodedTarget.indexOf('/')
        val portText = if (separator >= 0) encodedTarget.substring(0, separator) else encodedTarget
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return uri
        val upstreamPath = if (separator >= 0) encodedTarget.substring(separator) else "/"
        val host = uri.host ?: return uri
        val authority = formatHost(host) + if (port == 443) "" else ":$port"

        return URI(
            buildString {
                append("https://")
                append(authority)
                append(upstreamPath)
                uri.rawQuery?.let { append('?').append(it) }
                uri.rawFragment?.let { append('#').append(it) }
            },
        )
    }

    internal fun formatHost(host: String): String {
        val normalized = host.removePrefix("[").removeSuffix("]")
        return if (normalized.contains(':')) "[$normalized]" else normalized
    }

    internal fun effectivePort(uri: URI): Int =
        when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
}

internal class LegacyOriginRouteTable {
    private data class Key(
        val generation: Long,
        val peerAddress: String,
        val legacyHost: String,
        val legacyPort: Int,
    )

    private data class Origin(
        val scheme: String,
        val host: String,
        val port: Int,
    ) {
        fun resolvePathFrom(uri: URI): URI =
            URI(
                buildString {
                    append(scheme)
                    append("://")
                    append(LegacyHttpsUrlCodec.formatHost(host))
                    if (!isDefaultPort()) append(':').append(port)
                    append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                    uri.rawQuery?.let { append('?').append(it) }
                    uri.rawFragment?.let { append('#').append(it) }
                },
            )

        fun asDisplayString(): String =
            buildString {
                append(scheme)
                append("://")
                append(LegacyHttpsUrlCodec.formatHost(host))
                if (!isDefaultPort()) append(':').append(port)
            }

        private fun isDefaultPort(): Boolean =
            (scheme == "http" && port == 80) || (scheme == "https" && port == 443)

        companion object {
            fun from(uri: URI): Origin =
                Origin(
                    scheme = requireNotNull(uri.scheme).lowercase(Locale.ROOT),
                    host = normalizeHost(requireNotNull(uri.host)),
                    port = LegacyHttpsUrlCodec.effectivePort(uri),
                )

            private fun normalizeHost(host: String): String =
                host.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        }
    }

    private val mappings = ConcurrentHashMap<Key, Origin>()

    fun resolve(flow: TcpProxyFlow, legacyUri: URI): URI {
        val bootstrapTarget = LegacyHttpsUrlCodec.decodeLegacyUri(legacyUri)
        if (bootstrapTarget != legacyUri) return bootstrapTarget
        return mappings[key(flow, legacyUri)]?.resolvePathFrom(legacyUri) ?: legacyUri
    }

    fun remember(flow: TcpProxyFlow, legacyUri: URI, upstreamUri: URI): Pair<String, String>? {
        val key = key(flow, legacyUri)
        val legacyOrigin = Origin.from(legacyUri)
        val upstreamOrigin = Origin.from(upstreamUri)
        val previous =
            if (legacyOrigin == upstreamOrigin) {
                mappings.remove(key)
            } else {
                mappings.put(key, upstreamOrigin)
            }
        return if (previous == upstreamOrigin || (previous == null && legacyOrigin == upstreamOrigin)) {
            null
        } else {
            legacyOrigin.asDisplayString() to upstreamOrigin.asDisplayString()
        }
    }

    fun cleanHttpReference(flow: TcpProxyFlow, upstreamHttpsUri: URI): String? {
        val legacyUri = httpMirrorOf(upstreamHttpsUri)
        val mapped = mappings[key(flow, legacyUri)] ?: return null
        return if (mapped == Origin.from(upstreamHttpsUri)) legacyUri.toString() else null
    }

    fun invalidateBefore(generation: Long) {
        mappings.keys.removeIf { it.generation < generation }
    }

    fun clear() {
        mappings.clear()
    }

    private fun httpMirrorOf(uri: URI): URI =
        URI(
            buildString {
                append("http://")
                append(LegacyHttpsUrlCodec.formatHost(requireNotNull(uri.host)))
                append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                uri.rawQuery?.let { append('?').append(it) }
                uri.rawFragment?.let { append('#').append(it) }
            },
        )

    private fun key(flow: TcpProxyFlow, legacyUri: URI): Key =
        Key(
            generation = flow.generation,
            peerAddress = flow.key.peerAddress.toString(),
            legacyHost = requireNotNull(legacyUri.host).removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT),
            legacyPort = LegacyHttpsUrlCodec.effectivePort(legacyUri),
        )
}

class SystemRoutingTcpProxy(
    private val httpConfig: PppHttpCompatibilityConfig = PppHttpCompatibilityConfig(),
    private val logger: (String) -> Unit = {},
    private val eventSink: (YameEvent) -> Unit = {},
    private val directProxy: TcpProxy = SystemTcpProxy(),
    private val httpProxy: TcpProxy = SystemHttpCompatibilityProxy(httpConfig, logger, eventSink = eventSink),
) : TcpProxy {
    private val routes = ConcurrentHashMap<TcpProxyFlow, TcpProxy>()

    override fun connect(flow: TcpProxyFlow, onEvent: (TcpProxyEvent) -> Unit) {
        val proxy = if (httpConfig.enabled && flow.key.remotePort == HTTP_PORT) {
            logger("HTTP compatibility <= ${flow.key.peerAddress}:${flow.key.peerPort} -> ${flow.key.remoteAddress}:${flow.key.remotePort}")
            emitEvent(
                YameEvent.HttpProxyAction(
                    flowId = flowId(flow),
                    kind = HttpProxyActionKind.ROUTED,
                    message = "${flow.key.peerAddress}:${flow.key.peerPort} -> ${flow.key.remoteAddress}:${flow.key.remotePort}",
                ),
            )
            httpProxy
        } else directProxy
        val previous = routes.putIfAbsent(flow, proxy)
        if (previous != null) {
            onEvent(TcpProxyEvent.Failure(IllegalStateException("TCP flow is already routed")))
            return
        }
        proxy.connect(flow, onEvent)
    }

    override fun send(flow: TcpProxyFlow, payload: ByteArray): Result<Unit> =
        route(flow)?.send(flow, payload) ?: Result.failure(IllegalStateException("TCP flow is not routed"))
    override fun shutdownOutput(flow: TcpProxyFlow): Result<Unit> =
        route(flow)?.shutdownOutput(flow) ?: Result.failure(IllegalStateException("TCP flow is not routed"))
    override fun availableWriteCapacity(flow: TcpProxyFlow): Int = route(flow)?.availableWriteCapacity(flow) ?: 0
    override fun pauseReads(flow: TcpProxyFlow) { route(flow)?.pauseReads(flow) }
    override fun resumeReads(flow: TcpProxyFlow) { route(flow)?.resumeReads(flow) }
    override fun closeFlow(flow: TcpProxyFlow) { routes.remove(flow)?.closeFlow(flow) }
    override fun invalidateBefore(generation: Long) {
        directProxy.invalidateBefore(generation)
        httpProxy.invalidateBefore(generation)
        routes.keys.removeIf { it.generation < generation }
    }
    override fun close() {
        routes.clear()
        directProxy.close()
        if (httpProxy !== directProxy) httpProxy.close()
    }
    private fun route(flow: TcpProxyFlow): TcpProxy? = routes[flow]
    private fun emitEvent(event: YameEvent) { runCatching { eventSink(event) } }
    private fun flowId(flow: TcpProxyFlow): String =
        "${flow.key.peerAddress}:${flow.key.peerPort}->${flow.key.remoteAddress}:${flow.key.remotePort}"
    private companion object { const val HTTP_PORT = 80 }
}

class SystemHttpCompatibilityProxy(
    private val config: PppHttpCompatibilityConfig = PppHttpCompatibilityConfig(enabled = true),
    private val logger: (String) -> Unit = {},
    private val eventSink: (YameEvent) -> Unit = {},
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.requestTimeoutMillis))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : TcpProxy {
    private data class ClientCookie(val name: String, val value: String)
    private data class CookieOverride(val name: String, val domain: String, val path: String, val secure: Boolean)
    private data class FlowState(
        val flow: TcpProxyFlow,
        val onEvent: (TcpProxyEvent) -> Unit,
        val request: ByteArrayOutputStream = ByteArrayOutputStream(),
        val readMonitor: java.lang.Object = java.lang.Object(),
        val slotReleased: AtomicBoolean = AtomicBoolean(),
        val taskStarted: AtomicBoolean = AtomicBoolean(),
        val cookieManager: CookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER),
        val clientCookies: MutableList<ClientCookie> = mutableListOf(),
        val cookieOverrides: MutableList<CookieOverride> = mutableListOf(),
        @Volatile var processing: Boolean = false,
        @Volatile var readsPaused: Boolean = false,
        @Volatile var cancelled: Boolean = false,
        @Volatile var expectContinueSent: Boolean = false,
        @Volatile var task: Future<*>? = null,
        @Volatile var interimTask: Future<*>? = null,
    )

    private data class LegacyRequest(val method: String, val target: String, val headers: List<Pair<String, String>>, val body: ByteArray)
    private data class FinalResponse(
        val uri: URI,
        val statusCode: Int,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
        val contentLength: Long,
        val exposeCookies: Boolean,
    )

    private enum class ExpectationDisposition { NONE, CONTINUE, UNSUPPORTED }

    private val executor = Executors.newFixedThreadPool(config.maxFlows) { runnable ->
        Thread(runnable, "http-compatibility-proxy").apply { isDaemon = true }
    }
    private val flowSlots = Semaphore(config.maxFlows)
    private val flows = ConcurrentHashMap<TcpProxyFlow, FlowState>()
    private val originRoutes = LegacyOriginRouteTable()
    private val minimumGeneration = AtomicLong(Long.MIN_VALUE)
    @Volatile private var closed = false

    override fun connect(flow: TcpProxyFlow, onEvent: (TcpProxyEvent) -> Unit) {
        if (closed) {
            safeCallback(onEvent, TcpProxyEvent.Failure(IllegalStateException("HTTP compatibility proxy is closed")))
            return
        }
        if (flow.generation < minimumGeneration.get()) {
            safeCallback(onEvent, TcpProxyEvent.Failure(IllegalStateException("TCP flow belongs to an old IPCP generation")))
            return
        }
        if (!flowSlots.tryAcquire()) {
            safeCallback(onEvent, TcpProxyEvent.Failure(IllegalStateException("HTTP compatibility flow limit reached")))
            return
        }
        val state = FlowState(flow = flow, onEvent = onEvent)
        if (flows.putIfAbsent(flow, state) != null) {
            releaseSlot(state)
            safeCallback(onEvent, TcpProxyEvent.Failure(IllegalStateException("HTTP compatibility flow already exists")))
            return
        }
        safeCallback(onEvent, TcpProxyEvent.Connected)
    }

    override fun send(flow: TcpProxyFlow, payload: ByteArray): Result<Unit> {
        if (payload.isEmpty()) return Result.success(Unit)
        val state = flows[flow] ?: return Result.failure(IllegalStateException("HTTP compatibility flow is not connected"))
        var completeRequest: ByteArray? = null
        var sendContinue = false
        var rejectExpectation = false
        synchronized(state) {
            if (state.cancelled) return Result.failure(IllegalStateException("HTTP compatibility flow is closed"))
            if (state.processing) return Result.failure(IllegalStateException("HTTP pipelining is not supported by compatibility mode"))
            if (state.request.size() + payload.size > config.maxRequestBytes) {
                return Result.failure(IllegalStateException("HTTP request exceeds compatibility limit"))
            }
            state.request.write(payload)
            val buffered = state.request.toByteArray()
            val headersComplete = requestHeadersComplete(buffered)
            val expectation = if (headersComplete) expectationDisposition(buffered) else ExpectationDisposition.NONE
            if (expectation == ExpectationDisposition.UNSUPPORTED) {
                state.processing = true
                state.request.reset()
                rejectExpectation = true
            } else {
                val requestLength = requestLengthIfComplete(buffered)
                if (requestLength != null) {
                    if (buffered.size != requestLength) return Result.failure(IllegalStateException("HTTP pipelining is not supported by compatibility mode"))
                    state.processing = true
                    completeRequest = buffered
                    state.request.reset()
                } else if (expectation == ExpectationDisposition.CONTINUE && !state.expectContinueSent) {
                    state.expectContinueSent = true
                    sendContinue = true
                }
            }
        }
        safeCallback(state.onEvent, TcpProxyEvent.WriteCompleted(payload.size))
        if (sendContinue) {
            val scheduled = scheduleInterimResponse(state, "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            if (scheduled.isFailure) return scheduled
        }
        if (rejectExpectation) {
            return scheduleErrorResponse(state, 417, "Expectation Failed", "Unsupported HTTP Expect header")
        }
        if (completeRequest != null) {
            try {
                val requestBytes = requireNotNull(completeRequest)
                val task = FutureTask<Unit> {
                    state.taskStarted.set(true)
                    state.interimTask?.let { runCatching { it.get() } }
                    processRequest(state, requestBytes)
                }
                state.task = task
                executor.execute(task)
                if (state.cancelled || flows[state.flow] !== state) cancelQueuedOrRunningTask(state)
            } catch (error: Throwable) {
                synchronized(state) { state.processing = false; state.task = null }
                failFlow(state, error)
                return Result.failure(error)
            }
        }
        return Result.success(Unit)
    }

    private fun scheduleInterimResponse(state: FlowState, payload: ByteArray): Result<Unit> {
        return try {
            val task = FutureTask<Unit> {
                try {
                    emitBytes(state, payload)
                } finally {
                    if (state.interimTask === state.interimTask) state.interimTask = null
                }
            }
            state.interimTask = task
            executor.execute(task)
            if (state.cancelled || flows[state.flow] !== state) task.cancel(true)
            Result.success(Unit)
        } catch (error: Throwable) {
            state.interimTask = null
            failFlow(state, error)
            Result.failure(error)
        }
    }

    private fun scheduleErrorResponse(state: FlowState, status: Int, reason: String, message: String): Result<Unit> {
        return try {
            val task = FutureTask<Unit> {
                state.taskStarted.set(true)
                try {
                    emitErrorResponse(state, status, reason, message)
                } finally {
                    synchronized(state) { state.processing = false; state.task = null }
                    removeFlow(state)
                }
            }
            state.task = task
            executor.execute(task)
            if (state.cancelled || flows[state.flow] !== state) cancelQueuedOrRunningTask(state)
            Result.success(Unit)
        } catch (error: Throwable) {
            synchronized(state) { state.processing = false; state.task = null }
            failFlow(state, error)
            Result.failure(error)
        }
    }

    override fun shutdownOutput(flow: TcpProxyFlow): Result<Unit> {
        val state = flows[flow] ?: return Result.failure(IllegalStateException("HTTP compatibility flow is not connected"))
        var incomplete = false
        var empty = false
        synchronized(state) {
            if (!state.processing) {
                if (state.request.size() > 0) {
                    state.processing = true
                    state.request.reset()
                    incomplete = true
                } else {
                    empty = true
                }
            }
        }
        if (incomplete) return scheduleErrorResponse(state, 400, "Bad Request", "Incomplete HTTP request")
        if (empty) {
            finishFlow(state)
            removeFlow(state)
        }
        return Result.success(Unit)
    }

    override fun availableWriteCapacity(flow: TcpProxyFlow): Int {
        val state = flows[flow] ?: return 0
        synchronized(state) {
            if (state.processing || state.cancelled) return 0
            return (config.maxRequestBytes - state.request.size()).coerceAtLeast(0)
        }
    }
    override fun pauseReads(flow: TcpProxyFlow) {
        val state = flows[flow] ?: return
        synchronized(state.readMonitor) { state.readsPaused = true }
    }
    override fun resumeReads(flow: TcpProxyFlow) {
        val state = flows[flow] ?: return
        synchronized(state.readMonitor) { state.readsPaused = false; state.readMonitor.notifyAll() }
    }

    private fun processRequest(state: FlowState, bytes: ByteArray) {
        try {
            ensureActive(state)
            val request = parseRequest(bytes)
            val response = fetchFinalResponse(state, request)
            ensureActive(state)
            emitFinalResponse(state, response)
        } catch (_: CancellationException) {
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: BadLegacyRequest) {
            logger("HTTP compatibility !! bad request: ${error.message}")
            emitEvent(state, HttpProxyActionKind.ERROR, error.message ?: "Invalid HTTP request")
            emitErrorResponse(state, 400, "Bad Request", error.message ?: "Invalid HTTP request")
        } catch (error: ResponseTooLarge) {
            logger("HTTP compatibility !! ${error.message}")
            emitEvent(state, HttpProxyActionKind.ERROR, error.message ?: "Upstream response too large")
            emitErrorResponse(state, 502, "Bad Gateway", error.message ?: "Upstream response too large")
        } catch (error: Throwable) {
            logger("HTTP compatibility !! upstream failed: ${error.message ?: error.javaClass.simpleName}")
            emitEvent(state, HttpProxyActionKind.ERROR, error.message ?: error.javaClass.simpleName)
            emitErrorResponse(state, 502, "Bad Gateway", "YAME could not fetch the upstream resource")
        } finally {
            synchronized(state) { state.processing = false; state.task = null }
            if (flows[state.flow] !== state) releaseSlot(state)
        }
    }

    private fun fetchFinalResponse(state: FlowState, request: LegacyRequest): FinalResponse {
        val legacyUri = legacyUri(state.flow, request)
        var uri = originRoutes.resolve(state.flow, legacyUri)
        val originalUri = uri
        seedClientCookies(state, request.headers)
        var method = request.method
        var body = request.body
        var redirects = 0
        var forwardSensitiveHeaders = true
        val requestConnectionHeadersToStrip = connectionNominatedHeaders(request.headers)
        while (true) {
            ensureActive(state)
            requireSupportedScheme(uri)
            logger("HTTP compatibility => $method $uri")
            emitEvent(
                state,
                HttpProxyActionKind.REQUEST,
                "$method $uri",
            )
            val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(config.requestTimeoutMillis))
            request.headers.forEach { (name, value) ->
                val normalizedName = name.lowercase(Locale.ROOT)
                if (normalizedName !in REQUEST_HEADERS_TO_STRIP && normalizedName !in requestConnectionHeadersToStrip &&
                    (forwardSensitiveHeaders || normalizedName !in SENSITIVE_REQUEST_HEADERS)) {
                    builder.header(name, value)
                }
            }
            cookieHeaders(state, uri, forwardSensitiveHeaders).forEach { builder.header("Cookie", it) }
            builder.header("Accept-Encoding", "identity")
            builder.method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            ensureActiveOrClose(state, response)
            storeCookies(state, uri, response.headers().map())
            val status = response.statusCode()
            val location = response.headers().firstValue("location").orElse(null)
            if (status in REDIRECT_STATUS_CODES && location != null) {
                response.body().close()
                if (redirects >= config.maxRedirects) throw IllegalStateException("HTTP redirect limit (${config.maxRedirects}) exceeded")
                val next = uri.resolve(location)
                requireSupportedScheme(next)
                if (!sameOrigin(uri, next)) forwardSensitiveHeaders = false
                redirects++
                logger("HTTP compatibility .. redirect $status $uri -> $next")
                emitEvent(
                    state,
                    HttpProxyActionKind.REDIRECT,
                    "$status $uri -> $next",
                )
                uri = next
                if ((status == 303 && !method.equals("HEAD", true)) || ((status == 301 || status == 302) && method.equals("POST", true))) {
                    method = "GET"
                    body = ByteArray(0)
                }
                continue
            }
            response.body().use { input ->
                val isHead = request.method.equals("HEAD", true)
                val preservesRepresentationLength = isHead || status == 304
                val upstreamContentLength = response.headers().firstValueAsLong("content-length").orElse(-1L)
                if (!preservesRepresentationLength && upstreamContentLength > config.maxResponseBytes.toLong()) {
                    throw ResponseTooLarge("Upstream response is $upstreamContentLength bytes; limit is ${config.maxResponseBytes}")
                }
                val responseBody = if (preservesRepresentationLength) ByteArray(0) else readBounded(state, input, config.maxResponseBytes)
                originRoutes.remember(state.flow, legacyUri, uri)?.let { (legacyOrigin, upstreamOrigin) ->
                    logger("HTTP compatibility .. session route $legacyOrigin -> $upstreamOrigin")
                    emitEvent(state, HttpProxyActionKind.REDIRECT, "session $legacyOrigin -> $upstreamOrigin")
                }
                return FinalResponse(
                    uri = uri,
                    statusCode = status,
                    headers = response.headers().map(),
                    body = responseBody,
                    contentLength = if (preservesRepresentationLength && upstreamContentLength >= 0L) upstreamContentLength else responseBody.size.toLong(),
                    exposeCookies = sameOrigin(originalUri, uri),
                )
            }
        }
    }

    private fun emitFinalResponse(state: FlowState, response: FinalResponse) {
        val legacyHeaders = rewriteLegacyHeaders(state.flow, response.uri, response.headers)
        val legacyBody = rewriteLegacyBody(state.flow, response.uri, response.headers, response.body)
        val rewritten = legacyHeaders != response.headers || legacyBody !== response.body
        val contentLength =
            if (response.contentLength != response.body.size.toLong()) response.contentLength
            else legacyBody.size.toLong()
        val nominated = connectionNominatedHeaders(legacyHeaders.flatMap { (name, values) -> values.map { name to it } })
        val head = buildString {
            append("HTTP/1.0 ${response.statusCode} ${reasonPhrase(response.statusCode)}\r\n")
            legacyHeaders.forEach { (name, values) ->
                val normalized = name.lowercase(Locale.ROOT)
                val hiddenCrossOriginCookie = !response.exposeCookies && normalized in RESPONSE_COOKIE_HEADERS
                if (normalized !in RESPONSE_HEADERS_TO_STRIP && normalized !in nominated && !hiddenCrossOriginCookie) {
                    values.forEach { append("$name: $it\r\n") }
                }
            }
            append("Content-Length: $contentLength\r\nConnection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        val rewriteSuffix = if (rewritten) ", HTTPS references rewritten for legacy client" else ""
        logger(
            "HTTP compatibility <= ${response.statusCode} ${response.uri} " +
                "(${legacyBody.size} bytes, TLS hidden from peer$rewriteSuffix)",
        )
        emitEvent(
            state,
            HttpProxyActionKind.RESPONSE,
            "${response.statusCode} ${response.uri} · ${legacyBody.size} bytes · TLS hidden" +
                if (rewritten) " · HTTPS links rewritten" else "",
        )
        emitBytes(state, head)
        var offset = 0
        while (offset < legacyBody.size) {
            val end = minOf(offset + RESPONSE_CHUNK_BYTES, legacyBody.size)
            emitBytes(state, legacyBody.copyOfRange(offset, end)); offset = end
        }
        finishFlow(state)
    }

    private fun rewriteLegacyHeaders(
        flow: TcpProxyFlow,
        upstreamBase: URI,
        headers: Map<String, List<String>>,
    ): Map<String, List<String>> =
        headers.mapValues { (name, values) ->
            if (name.lowercase(Locale.ROOT) in URI_RESPONSE_HEADERS_TO_REWRITE) {
                values.map { rewriteHttpsReferences(flow, upstreamBase, it) }
            } else {
                values
            }
        }

    private fun rewriteLegacyBody(
        flow: TcpProxyFlow,
        upstreamBase: URI,
        headers: Map<String, List<String>>,
        body: ByteArray,
    ): ByteArray {
        if (body.isEmpty() || !bodyCanContainNavigableUrls(headers)) return body
        val source = body.toString(StandardCharsets.ISO_8859_1)
        val rewritten = rewriteHttpsReferences(flow, upstreamBase, source)
        return if (rewritten == source) body else rewritten.toByteArray(StandardCharsets.ISO_8859_1)
    }

    private fun bodyCanContainNavigableUrls(headers: Map<String, List<String>>): Boolean {
        val contentEncoding = firstHeader(headers, "content-encoding")
        if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) return false

        val contentType = firstHeader(headers, "content-type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return false
        return contentType in REWRITABLE_CONTENT_TYPES
    }

    private fun firstHeader(headers: Map<String, List<String>>, name: String): String? =
        headers.entries
            .firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

    private fun rewriteHttpsReferences(flow: TcpProxyFlow, upstreamBase: URI, value: String): String {
        var rewritten = HTTPS_URL_PATTERN.replace(value) { match ->
            val target = runCatching { URI(match.value) }.getOrNull()
            if (target != null && target.scheme.equals("https", ignoreCase = true) && target.host != null) {
                originRoutes.cleanHttpReference(flow, target) ?: LegacyHttpsUrlCodec.encode(target)
            } else {
                match.value
            }
        }

        if (upstreamBase.scheme.equals("https", ignoreCase = true)) {
            rewritten = PROTOCOL_RELATIVE_URL_PATTERN.replace(rewritten) { match ->
                val target = runCatching { URI("https:${match.value}") }.getOrNull()
                if (target != null && target.host != null) {
                    originRoutes.cleanHttpReference(flow, target) ?: LegacyHttpsUrlCodec.encode(target)
                } else {
                    match.value
                }
            }
        }
        return rewritten
    }

    private fun emitErrorResponse(state: FlowState, status: Int, reason: String, message: String) {
        if (!isActive(state)) return
        val body = "$status $reason\r\n$message\r\n".toByteArray(StandardCharsets.US_ASCII)
        emitBytes(state, ("HTTP/1.0 $status $reason\r\nContent-Type: text/plain; charset=us-ascii\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
        emitBytes(state, body)
        finishFlow(state)
    }
    private fun emitBytes(state: FlowState, payload: ByteArray) {
        if (payload.isEmpty() || !isActive(state)) return
        awaitReadsEnabled(state)
        if (isActive(state)) safeCallback(state.onEvent, TcpProxyEvent.Payload(payload.copyOf()))
    }
    private fun awaitReadsEnabled(state: FlowState) {
        synchronized(state.readMonitor) { while (state.readsPaused && isActive(state)) state.readMonitor.wait() }
    }
    private fun finishFlow(state: FlowState) { if (isActive(state)) safeCallback(state.onEvent, TcpProxyEvent.EndOfStream) }
    private fun failFlow(state: FlowState, error: Throwable) {
        if (removeFlow(state) && !closed) safeCallback(state.onEvent, TcpProxyEvent.Failure(error))
    }

    private fun legacyUri(flow: TcpProxyFlow, request: LegacyRequest): URI {
        val absolute = runCatching { URI(request.target) }.getOrNull()
        if (absolute?.isAbsolute == true) {
            if (!absolute.scheme.equals("http", true)) throw BadLegacyRequest("Port 80 compatibility requests must start as HTTP")
            return absolute
        }
        val host = request.headers.firstOrNull { it.first.equals("Host", true) }?.second?.trim()?.takeIf { it.isNotEmpty() }
            ?: flow.key.remoteAddress.toString()
        val target = if (request.target.startsWith('/')) request.target else "/${request.target}"
        return try {
            URI("http://$host$target")
        } catch (_: Exception) {
            throw BadLegacyRequest("Invalid HTTP Host or request target")
        }
    }

    private fun parseRequest(bytes: ByteArray): LegacyRequest {
        val headerEnd = findHeaderEnd(bytes)
        if (headerEnd < 0) throw BadLegacyRequest("Incomplete HTTP request headers")
        val lines = String(bytes, 0, headerEnd, StandardCharsets.ISO_8859_1).split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ', limit = 3) ?: throw BadLegacyRequest("Missing HTTP request line")
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/")) throw BadLegacyRequest("Malformed HTTP request line")
        val method = requestLine[0].uppercase(Locale.ROOT)
        if (!METHOD_PATTERN.matches(method)) throw BadLegacyRequest("Invalid HTTP method")
        if (method == "CONNECT") throw BadLegacyRequest("CONNECT is not valid in HTTP compatibility mode")
        val headers = lines.drop(1).map { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) throw BadLegacyRequest("Malformed HTTP header")
            val name = line.substring(0, separator).trim(); val value = line.substring(separator + 1).trim()
            if (!HEADER_NAME_PATTERN.matches(name) || value.any { it == '\r' || it == '\n' || it.code == 0 }) throw BadLegacyRequest("Invalid HTTP header")
            name to value
        }
        if (headers.any { it.first.equals("Transfer-Encoding", true) }) throw BadLegacyRequest("Chunked legacy HTTP requests are not supported")
        val lengths = headers.filter { it.first.equals("Content-Length", true) }.map { it.second.toLongOrNull() ?: throw BadLegacyRequest("Invalid Content-Length") }.distinct()
        if (lengths.size > 1) throw BadLegacyRequest("Conflicting Content-Length headers")
        val contentLength = lengths.singleOrNull() ?: 0L
        if (contentLength < 0 || contentLength > config.maxRequestBytes) throw BadLegacyRequest("HTTP request body exceeds compatibility limit")
        val bodyStart = headerEnd + HEADER_DELIMITER.size
        if (bytes.size - bodyStart != contentLength.toInt()) throw BadLegacyRequest("HTTP request body length does not match Content-Length")
        return LegacyRequest(method, requestLine[1], headers, bytes.copyOfRange(bodyStart, bytes.size))
    }

    private fun requestHeadersComplete(bytes: ByteArray) = findHeaderEnd(bytes) >= 0
    private fun expectationDisposition(bytes: ByteArray): ExpectationDisposition {
        val headerEnd = findHeaderEnd(bytes); if (headerEnd < 0) return ExpectationDisposition.NONE
        val values = String(bytes, 0, headerEnd, StandardCharsets.ISO_8859_1).split("\r\n").drop(1).flatMap { line ->
            val separator = line.indexOf(':')
            if (separator <= 0 || !line.substring(0, separator).trim().equals("Expect", true)) emptyList()
            else line.substring(separator + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        return when {
            values.isEmpty() -> ExpectationDisposition.NONE
            values.all { it.equals("100-continue", true) } -> ExpectationDisposition.CONTINUE
            else -> ExpectationDisposition.UNSUPPORTED
        }
    }
    private fun requestLengthIfComplete(bytes: ByteArray): Int? {
        val headerEnd = findHeaderEnd(bytes); if (headerEnd < 0) return null
        val headerBytes = headerEnd + HEADER_DELIMITER.size
        val values = String(bytes, 0, headerEnd, StandardCharsets.ISO_8859_1).split("\r\n").drop(1).mapNotNull { line ->
            val separator = line.indexOf(':'); if (separator <= 0 || !line.substring(0, separator).trim().equals("Content-Length", true)) null
            else line.substring(separator + 1).trim().toLongOrNull()
        }.distinct()
        if (values.size > 1) return headerBytes
        val length = values.singleOrNull() ?: 0L
        if (length < 0 || length > config.maxRequestBytes) return headerBytes
        val total = headerBytes.toLong() + length
        if (total > config.maxRequestBytes) return headerBytes
        return if (bytes.size.toLong() >= total) total.toInt() else null
    }
    private fun findHeaderEnd(bytes: ByteArray): Int {
        if (bytes.size < 4) return -1
        for (i in 0..bytes.size - 4) if (bytes[i] == 13.toByte() && bytes[i + 1] == 10.toByte() && bytes[i + 2] == 13.toByte() && bytes[i + 3] == 10.toByte()) return i
        return -1
    }

    private fun seedClientCookies(state: FlowState, headers: List<Pair<String, String>>) {
        headers.filter { it.first.equals("Cookie", true) || it.first.equals("Cookie2", true) }.forEach { (_, value) ->
            value.split(';').map { it.trim() }.forEach { pair ->
                val separator = pair.indexOf('=')
                if (separator > 0) {
                    val name = pair.substring(0, separator).trim()
                    val cookieValue = pair.substring(separator + 1).trim()
                    if (name.isNotEmpty() && !name.startsWith('$')) state.clientCookies += ClientCookie(name, cookieValue)
                }
            }
        }
    }

    private fun cookieHeaders(state: FlowState, uri: URI, includeClientCookies: Boolean): List<String> {
        val result = mutableListOf<String>()
        if (includeClientCookies && state.clientCookies.isNotEmpty()) {
            val remaining = state.clientCookies.filterNot { client ->
                state.cookieOverrides.any { replacement ->
                    replacement.name.equals(client.name, true) && cookieOverrideApplies(replacement, uri)
                }
            }
            if (remaining.isNotEmpty()) result += remaining.joinToString("; ") { "${it.name}=${it.value}" }
        }
        result += runCatching { state.cookieManager.get(uri, emptyMap())["Cookie"].orEmpty() }.getOrDefault(emptyList())
        return result
    }

    private fun storeCookies(state: FlowState, uri: URI, headers: Map<String, List<String>>) {
        runCatching { state.cookieManager.put(uri, headers) }
        headers.entries
            .filter { (name, _) -> name.equals("Set-Cookie", true) || name.equals("Set-Cookie2", true) }
            .flatMap { it.value }
            .mapNotNull { parseCookieOverride(uri, it) }
            .forEach { state.cookieOverrides += it }
    }

    private fun parseCookieOverride(uri: URI, value: String): CookieOverride? {
        val parts = value.split(';').map { it.trim() }
        val pair = parts.firstOrNull() ?: return null
        val separator = pair.indexOf('=')
        if (separator <= 0) return null
        val name = pair.substring(0, separator).trim()
        if (name.isEmpty()) return null
        var domain = uri.host ?: return null
        var path = defaultCookiePath(uri.path)
        var secure = false
        parts.drop(1).forEach { attribute ->
            val attrSeparator = attribute.indexOf('=')
            val attrName = (if (attrSeparator >= 0) attribute.substring(0, attrSeparator) else attribute).trim()
            val attrValue = if (attrSeparator >= 0) attribute.substring(attrSeparator + 1).trim() else ""
            when {
                attrName.equals("Domain", true) && attrValue.isNotEmpty() -> domain = attrValue.trimStart('.')
                attrName.equals("Path", true) && attrValue.startsWith('/') -> path = attrValue
                attrName.equals("Secure", true) -> secure = true
            }
        }
        if (!domainMatches(uri.host ?: return null, domain)) return null
        return CookieOverride(name, domain.lowercase(Locale.ROOT), path, secure)
    }

    private fun cookieOverrideApplies(override: CookieOverride, uri: URI): Boolean {
        val host = uri.host ?: return false
        if (!domainMatches(host, override.domain)) return false
        if (!pathMatches(uri.path.ifEmpty { "/" }, override.path)) return false
        if (override.secure && !uri.scheme.equals("https", true)) return false
        return true
    }

    private fun domainMatches(host: String, domain: String): Boolean {
        val normalizedHost = host.lowercase(Locale.ROOT)
        val normalizedDomain = domain.trimStart('.').lowercase(Locale.ROOT)
        return normalizedHost == normalizedDomain || normalizedHost.endsWith(".$normalizedDomain")
    }

    private fun pathMatches(requestPath: String, cookiePath: String): Boolean {
        if (requestPath == cookiePath) return true
        if (!requestPath.startsWith(cookiePath)) return false
        return cookiePath.endsWith('/') || requestPath.getOrNull(cookiePath.length) == '/'
    }

    private fun defaultCookiePath(requestPath: String): String {
        if (!requestPath.startsWith('/') || requestPath == "/") return "/"
        val lastSlash = requestPath.lastIndexOf('/')
        return if (lastSlash <= 0) "/" else requestPath.substring(0, lastSlash)
    }

    private fun requireSupportedScheme(uri: URI) {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") throw IllegalStateException("Unsupported redirect scheme: ${uri.scheme}")
        if (uri.host.isNullOrBlank()) throw IllegalStateException("HTTP upstream URI has no host")
    }
    private fun connectionNominatedHeaders(headers: Iterable<Pair<String, String>>): Set<String> = headers
        .filter { (name, _) -> name.equals("Connection", true) || name.equals("Proxy-Connection", true) }
        .flatMap { it.second.split(',') }.map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() && HEADER_NAME_PATTERN.matches(it) }.toSet()
    private fun sameOrigin(a: URI, b: URI) = a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && effectivePort(a) == effectivePort(b)
    private fun effectivePort(uri: URI) = when { uri.port >= 0 -> uri.port; uri.scheme.equals("https", true) -> 443; else -> 80 }

    private fun readBounded(state: FlowState, input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024)); val buffer = ByteArray(RESPONSE_CHUNK_BYTES); var total = 0
        while (true) {
            ensureActive(state); val count = input.read(buffer); if (count < 0) break; if (count == 0) continue
            if (total + count > limit) throw ResponseTooLarge("Upstream response exceeds compatibility limit of $limit bytes")
            output.write(buffer, 0, count); total += count
        }
        return output.toByteArray()
    }
    private fun ensureActive(state: FlowState) {
        if (!isActive(state) || Thread.currentThread().isInterrupted) throw CancellationException("HTTP compatibility flow closed")
    }
    private fun <T> ensureActiveOrClose(state: FlowState, response: HttpResponse<T>) {
        if (!isActive(state) || Thread.currentThread().isInterrupted) {
            (response.body() as? AutoCloseable)?.let { runCatching { it.close() } }
            throw CancellationException("HTTP compatibility flow closed")
        }
    }
    private fun isActive(state: FlowState) = !closed && !state.cancelled && state.flow.generation >= minimumGeneration.get() && flows[state.flow] === state
    override fun closeFlow(flow: TcpProxyFlow) { flows[flow]?.let(::removeFlow) }

    private fun cancelQueuedOrRunningTask(state: FlowState) {
        val task = state.task ?: return
        if (task.cancel(true) && !state.taskStarted.get()) {
            synchronized(state) { state.processing = false; state.task = null }
            releaseSlot(state)
        }
    }
    private fun removeFlow(state: FlowState): Boolean {
        if (!flows.remove(state.flow, state)) return false
        state.cancelled = true
        state.interimTask?.cancel(true)
        state.interimTask = null
        cancelQueuedOrRunningTask(state)
        synchronized(state.readMonitor) { state.readsPaused = false; state.readMonitor.notifyAll() }
        if (!state.processing) releaseSlot(state)
        return true
    }
    private fun releaseSlot(state: FlowState) { if (state.slotReleased.compareAndSet(false, true)) flowSlots.release() }
    override fun invalidateBefore(generation: Long) {
        minimumGeneration.accumulateAndGet(generation, ::maxOf)
        originRoutes.invalidateBefore(generation)
        flows.values.filter { it.flow.generation < generation }.forEach(::removeFlow)
    }
    override fun close() {
        if (closed) return
        closed = true
        originRoutes.clear()
        flows.values.toList().forEach(::removeFlow)
        executor.shutdownNow()
        runCatching { executor.awaitTermination(CLOSE_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
    }
    private fun emitEvent(
        state: FlowState,
        kind: HttpProxyActionKind,
        message: String,
    ) {
        runCatching {
            eventSink(
                YameEvent.HttpProxyAction(
                    flowId = "${state.flow.key.peerAddress}:${state.flow.key.peerPort}->${state.flow.key.remoteAddress}:${state.flow.key.remotePort}",
                    kind = kind,
                    message = message,
                ),
            )
        }
    }
    private fun safeCallback(callback: (TcpProxyEvent) -> Unit, event: TcpProxyEvent) { runCatching { callback(event) } }
    private class BadLegacyRequest(message: String) : IllegalArgumentException(message)
    private class ResponseTooLarge(message: String) : IllegalStateException(message)

    private companion object {
        val HEADER_DELIMITER = byteArrayOf(13, 10, 13, 10)
        val METHOD_PATTERN = Regex("[A-Z!#$%&'*+.^_`|~-]+")
        val HEADER_NAME_PATTERN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        val REQUEST_HEADERS_TO_STRIP = setOf("host", "connection", "proxy-connection", "proxy-authorization", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade", "content-length", "accept-encoding", "expect", "cookie", "cookie2")
        val SENSITIVE_REQUEST_HEADERS = setOf("authorization", "cookie", "cookie2")
        val RESPONSE_HEADERS_TO_STRIP = setOf("connection", "proxy-connection", "keep-alive", "transfer-encoding", "trailer", "upgrade", "content-length")
        val RESPONSE_COOKIE_HEADERS = setOf("set-cookie", "set-cookie2")
        val URI_RESPONSE_HEADERS_TO_REWRITE = setOf("location", "content-location", "refresh", "link")
        val HTTPS_URL_PATTERN =
            Regex("""https://(?:\[[^\]]+\]|[^\s/:?#"'<>]+)(?::\d+)?(?:[/?#][^\s"'<>\)]*)?""", RegexOption.IGNORE_CASE)
        val PROTOCOL_RELATIVE_URL_PATTERN =
            Regex("""(?<!:)//(?:\[[^\]]+\]|(?:[A-Za-z0-9-]+\.)+[A-Za-z0-9-]+|(?:\d{1,3}\.){3}\d{1,3})(?::\d+)?(?:[/?#][^\s"'<>\)]*)?""")
        val REWRITABLE_CONTENT_TYPES = setOf(
            "text/html",
            "application/xhtml+xml",
            "text/css",
            "text/javascript",
            "application/javascript",
            "application/x-javascript",
        )
        const val RESPONSE_CHUNK_BYTES = 4 * 1024
        const val CLOSE_JOIN_TIMEOUT_MILLIS = 1_000L
        fun reasonPhrase(status: Int) = when (status) {
            200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"; 206 -> "Partial Content"
            300 -> "Multiple Choices"; 301 -> "Moved Permanently"; 302 -> "Found"; 303 -> "See Other"; 304 -> "Not Modified"
            307 -> "Temporary Redirect"; 308 -> "Permanent Redirect"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 408 -> "Request Timeout"
            409 -> "Conflict"; 410 -> "Gone"; 413 -> "Content Too Large"; 417 -> "Expectation Failed"; 429 -> "Too Many Requests"
            500 -> "Internal Server Error"; 501 -> "Not Implemented"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
            504 -> "Gateway Timeout"; else -> "Upstream Response"
        }
    }
}
