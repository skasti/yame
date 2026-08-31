package no.skasti.serialmodem.ppp

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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class PppHttpCompatibilityConfig(
    val enabled: Boolean = false,
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

class SystemRoutingTcpProxy(
    private val httpConfig: PppHttpCompatibilityConfig = PppHttpCompatibilityConfig(),
    private val logger: (String) -> Unit = {},
    private val directProxy: TcpProxy = SystemTcpProxy(),
    private val httpProxy: TcpProxy = SystemHttpCompatibilityProxy(httpConfig, logger),
) : TcpProxy {
    private val routes = ConcurrentHashMap<TcpProxyFlow, TcpProxy>()

    override fun connect(flow: TcpProxyFlow, onEvent: (TcpProxyEvent) -> Unit) {
        val proxy = if (httpConfig.enabled && flow.key.remotePort == HTTP_PORT) {
            logger("HTTP compatibility <= ${flow.key.peerAddress}:${flow.key.peerPort} -> ${flow.key.remoteAddress}:${flow.key.remotePort}")
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
    private companion object { const val HTTP_PORT = 80 }
}

class SystemHttpCompatibilityProxy(
    private val config: PppHttpCompatibilityConfig = PppHttpCompatibilityConfig(enabled = true),
    private val logger: (String) -> Unit = {},
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.requestTimeoutMillis))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : TcpProxy {
    private data class FlowState(
        val flow: TcpProxyFlow,
        val onEvent: (TcpProxyEvent) -> Unit,
        val request: ByteArrayOutputStream = ByteArrayOutputStream(),
        val readMonitor: java.lang.Object = java.lang.Object(),
        val slotReleased: AtomicBoolean = AtomicBoolean(),
        val taskStarted: AtomicBoolean = AtomicBoolean(),
        val cookieManager: CookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER),
        val clientCookies: LinkedHashMap<String, String> = linkedMapOf(),
        @Volatile var processing: Boolean = false,
        @Volatile var readsPaused: Boolean = false,
        @Volatile var cancelled: Boolean = false,
        @Volatile var expectContinueSent: Boolean = false,
        @Volatile var task: Future<*>? = null,
    )

    private data class LegacyRequest(val method: String, val target: String, val headers: List<Pair<String, String>>, val body: ByteArray)
    private data class FinalResponse(
        val uri: URI,
        val statusCode: Int,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
        val contentLength: Long,
    )

    private enum class ExpectationDisposition { NONE, CONTINUE, UNSUPPORTED }

    private val executor = Executors.newFixedThreadPool(config.maxFlows) { runnable ->
        Thread(runnable, "http-compatibility-proxy").apply { isDaemon = true }
    }
    private val flowSlots = Semaphore(config.maxFlows)
    private val flows = ConcurrentHashMap<TcpProxyFlow, FlowState>()
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
            val requestLength = requestLengthIfComplete(buffered)
            if (requestLength != null) {
                if (buffered.size != requestLength) return Result.failure(IllegalStateException("HTTP pipelining is not supported by compatibility mode"))
                state.processing = true
                completeRequest = buffered
                state.request.reset()
            } else if (requestHeadersComplete(buffered)) {
                when (expectationDisposition(buffered)) {
                    ExpectationDisposition.CONTINUE -> if (!state.expectContinueSent) {
                        state.expectContinueSent = true
                        sendContinue = true
                    }
                    ExpectationDisposition.UNSUPPORTED -> {
                        state.processing = true
                        state.request.reset()
                        rejectExpectation = true
                    }
                    ExpectationDisposition.NONE -> Unit
                }
            }
        }
        safeCallback(state.onEvent, TcpProxyEvent.WriteCompleted(payload.size))
        if (sendContinue) emitBytes(state, "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        if (rejectExpectation) {
            emitErrorResponse(state, 417, "Expectation Failed", "Unsupported HTTP Expect header")
            synchronized(state) { state.processing = false }
            removeFlow(state)
            return Result.success(Unit)
        }
        if (completeRequest != null) {
            try {
                val task = executor.submit {
                    state.taskStarted.set(true)
                    processRequest(state, requireNotNull(completeRequest))
                }
                state.task = task
                if (state.cancelled || flows[state.flow] !== state) cancelQueuedOrRunningTask(state)
            } catch (error: Throwable) {
                synchronized(state) { state.processing = false }
                failFlow(state, error)
                return Result.failure(error)
            }
        }
        return Result.success(Unit)
    }

    override fun shutdownOutput(flow: TcpProxyFlow): Result<Unit> {
        val state = flows[flow] ?: return Result.failure(IllegalStateException("HTTP compatibility flow is not connected"))
        val idle = synchronized(state) { !state.processing }
        if (idle) {
            val hasBytes = synchronized(state) { state.request.size() > 0 }
            if (hasBytes) emitErrorResponse(state, 400, "Bad Request", "Incomplete HTTP request") else finishFlow(state)
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
            emitErrorResponse(state, 400, "Bad Request", error.message ?: "Invalid HTTP request")
        } catch (error: ResponseTooLarge) {
            logger("HTTP compatibility !! ${error.message}")
            emitErrorResponse(state, 502, "Bad Gateway", error.message ?: "Upstream response too large")
        } catch (error: Throwable) {
            logger("HTTP compatibility !! upstream failed: ${error.message ?: error.javaClass.simpleName}")
            emitErrorResponse(state, 502, "Bad Gateway", "YAME could not fetch the upstream resource")
        } finally {
            synchronized(state) { state.processing = false; state.task = null }
            if (flows[state.flow] !== state) releaseSlot(state)
        }
    }

    private fun fetchFinalResponse(state: FlowState, request: LegacyRequest): FinalResponse {
        var uri = initialUri(state.flow, request)
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
                return FinalResponse(
                    uri, status, response.headers().map(), responseBody,
                    if (preservesRepresentationLength && upstreamContentLength >= 0L) upstreamContentLength else responseBody.size.toLong(),
                )
            }
        }
    }

    private fun emitFinalResponse(state: FlowState, response: FinalResponse) {
        val nominated = connectionNominatedHeaders(response.headers.flatMap { (name, values) -> values.map { name to it } })
        val head = buildString {
            append("HTTP/1.0 ${response.statusCode} ${reasonPhrase(response.statusCode)}\r\n")
            response.headers.forEach { (name, values) ->
                val normalized = name.lowercase(Locale.ROOT)
                if (normalized !in RESPONSE_HEADERS_TO_STRIP && normalized !in nominated) {
                    values.forEach { append("$name: $it\r\n") }
                }
            }
            append("Content-Length: ${response.contentLength}\r\nConnection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        logger("HTTP compatibility <= ${response.statusCode} ${response.uri} (${response.body.size} bytes, TLS hidden from peer)")
        emitBytes(state, head)
        var offset = 0
        while (offset < response.body.size) {
            val end = minOf(offset + RESPONSE_CHUNK_BYTES, response.body.size)
            emitBytes(state, response.body.copyOfRange(offset, end)); offset = end
        }
        finishFlow(state)
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

    private fun initialUri(flow: TcpProxyFlow, request: LegacyRequest): URI {
        val absolute = runCatching { URI(request.target) }.getOrNull()
        if (absolute?.isAbsolute == true) {
            if (!absolute.scheme.equals("http", true)) throw BadLegacyRequest("Port 80 compatibility requests must start as HTTP")
            return absolute
        }
        val host = request.headers.firstOrNull { it.first.equals("Host", true) }?.second?.trim()?.takeIf { it.isNotEmpty() }
            ?: flow.key.remoteAddress.toString()
        val target = if (request.target.startsWith('/')) request.target else "/${request.target}"
        return try { URI("http://$host$target") } catch (_: Exception) { throw BadLegacyRequest("Invalid HTTP Host or request target") }
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
                    if (name.isNotEmpty() && !name.startsWith('$')) state.clientCookies[name] = cookieValue
                }
            }
        }
    }

    private fun cookieHeaders(state: FlowState, uri: URI, includeClientCookies: Boolean): List<String> {
        val result = mutableListOf<String>()
        if (includeClientCookies && state.clientCookies.isNotEmpty()) {
            result += state.clientCookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
        }
        result += runCatching { state.cookieManager.get(uri, emptyMap())["Cookie"].orEmpty() }.getOrDefault(emptyList())
        return result
    }

    private fun storeCookies(state: FlowState, uri: URI, headers: Map<String, List<String>>) {
        headers.entries
            .filter { (name, _) -> name.equals("Set-Cookie", true) || name.equals("Set-Cookie2", true) }
            .flatMap { it.value }
            .forEach { value ->
                val separator = value.indexOf('=')
                if (separator > 0) state.clientCookies.remove(value.substring(0, separator).trim())
            }
        runCatching { state.cookieManager.put(uri, headers) }
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
        cancelQueuedOrRunningTask(state)
        synchronized(state.readMonitor) { state.readsPaused = false; state.readMonitor.notifyAll() }
        if (!state.processing) releaseSlot(state)
        return true
    }
    private fun releaseSlot(state: FlowState) { if (state.slotReleased.compareAndSet(false, true)) flowSlots.release() }
    override fun invalidateBefore(generation: Long) {
        minimumGeneration.accumulateAndGet(generation, ::maxOf)
        flows.values.filter { it.flow.generation < generation }.forEach(::removeFlow)
    }
    override fun close() {
        if (closed) return
        closed = true
        flows.values.toList().forEach(::removeFlow)
        executor.shutdownNow()
        runCatching { executor.awaitTermination(CLOSE_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
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