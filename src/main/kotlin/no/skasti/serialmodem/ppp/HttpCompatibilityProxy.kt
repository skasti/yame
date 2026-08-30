package no.skasti.serialmodem.ppp

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Optional compatibility layer for HTTP-only legacy clients.
 *
 * The PPP peer still opens an ordinary TCP connection to port 80 and sends an
 * ordinary HTTP request. YAME consumes that request on the host side, follows
 * redirects itself (including HTTP -> HTTPS), and returns only the final HTTP
 * response to the peer. TLS therefore exists only between YAME and the modern
 * upstream server.
 */
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

/**
 * Routes normal TCP flows through the transparent socket proxy and, when the
 * compatibility option is enabled, routes destination port 80 through the
 * application-level HTTP compatibility proxy instead.
 */
class SystemRoutingTcpProxy(
    private val httpConfig: PppHttpCompatibilityConfig = PppHttpCompatibilityConfig(),
    private val logger: (String) -> Unit = {},
    private val directProxy: TcpProxy = SystemTcpProxy(),
    private val httpProxy: TcpProxy = SystemHttpCompatibilityProxy(httpConfig, logger),
) : TcpProxy {
    private val routes = ConcurrentHashMap<TcpProxyFlow, TcpProxy>()

    override fun connect(flow: TcpProxyFlow, onEvent: (TcpProxyEvent) -> Unit) {
        val proxy = if (httpConfig.enabled && flow.key.remotePort == HTTP_PORT) {
            logger(
                "HTTP compatibility <= ${flow.key.peerAddress}:${flow.key.peerPort} -> " +
                    "${flow.key.remoteAddress}:${flow.key.remotePort}",
            )
            httpProxy
        } else {
            directProxy
        }

        val previous = routes.putIfAbsent(flow, proxy)
        if (previous != null) {
            onEvent(TcpProxyEvent.Failure(IllegalStateException("TCP flow is already routed")))
            return
        }
        proxy.connect(flow, onEvent)
    }

    override fun send(flow: TcpProxyFlow, payload: ByteArray): Result<Unit> =
        route(flow)?.send(flow, payload)
            ?: Result.failure(IllegalStateException("TCP flow is not routed"))

    override fun shutdownOutput(flow: TcpProxyFlow): Result<Unit> =
        route(flow)?.shutdownOutput(flow)
            ?: Result.failure(IllegalStateException("TCP flow is not routed"))

    override fun availableWriteCapacity(flow: TcpProxyFlow): Int =
        route(flow)?.availableWriteCapacity(flow) ?: 0

    override fun pauseReads(flow: TcpProxyFlow) {
        route(flow)?.pauseReads(flow)
    }

    override fun resumeReads(flow: TcpProxyFlow) {
        route(flow)?.resumeReads(flow)
    }

    override fun closeFlow(flow: TcpProxyFlow) {
        routes.remove(flow)?.closeFlow(flow)
    }

    override fun invalidateBefore(generation: Long) {
        directProxy.invalidateBefore(generation)
        httpProxy.invalidateBefore(generation)
        routes.keys.removeIf { it.generation < generation }
    }

    override fun close() {
        routes.clear()
        directProxy.close()
        if (httpProxy !== directProxy) {
            httpProxy.close()
        }
    }

    private fun route(flow: TcpProxyFlow): TcpProxy? = routes[flow]

    private companion object {
        const val HTTP_PORT = 80
    }
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
        @Volatile var processing: Boolean = false,
        @Volatile var readsPaused: Boolean = false,
    )

    private data class LegacyRequest(
        val method: String,
        val target: String,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
    )

    private data class FinalResponse(
        val uri: URI,
        val statusCode: Int,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    )

    private val executor = Executors.newFixedThreadPool(config.maxFlows) { runnable ->
        Thread(runnable, "http-compatibility-proxy").apply { isDaemon = true }
    }
    private val flowSlots = Semaphore(config.maxFlows)
    private val flows = ConcurrentHashMap<TcpProxyFlow, FlowState>()
    private val minimumGeneration = AtomicLong(Long.MIN_VALUE)

    @Volatile
    private var closed = false

    override fun connect(flow: TcpProxyFlow, onEvent: (TcpProxyEvent) -> Unit) {
        if (closed) {
            safeCallback(onEvent, TcpProxyEvent.Failure(IllegalStateException("HTTP compatibility proxy is closed")))
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
            safeCallback(
                onEvent,
                TcpProxyEvent.Failure(IllegalStateException("HTTP compatibility flow limit reached")),
            )
            return
        }

        val state = FlowState(flow = flow, onEvent = onEvent)
        if (flows.putIfAbsent(flow, state) != null) {
            flowSlots.release()
            safeCallback(
                onEvent,
                TcpProxyEvent.Failure(IllegalStateException("HTTP compatibility flow already exists")),
            )
            return
        }

        // There is deliberately no upstream TCP connection yet. We need the HTTP
        // Host header before YAME can choose the modern HTTP/HTTPS endpoint.
        safeCallback(onEvent, TcpProxyEvent.Connected)
    }

    override fun send(flow: TcpProxyFlow, payload: ByteArray): Result<Unit> {
        if (payload.isEmpty()) return Result.success(Unit)
        val state = flows[flow]
            ?: return Result.failure(IllegalStateException("HTTP compatibility flow is not connected"))

        var completeRequest: ByteArray? = null
        synchronized(state) {
            if (state.processing) {
                return Result.failure(
                    IllegalStateException("HTTP pipelining is not supported by compatibility mode"),
                )
            }
            if (state.request.size() + payload.size > config.maxRequestBytes) {
                return Result.failure(IllegalStateException("HTTP request exceeds compatibility limit"))
            }

            state.request.write(payload)
            val buffered = state.request.toByteArray()
            val requestLength = requestLengthIfComplete(buffered)
            if (requestLength != null) {
                if (buffered.size != requestLength) {
                    return Result.failure(
                        IllegalStateException("HTTP pipelining is not supported by compatibility mode"),
                    )
                }
                state.processing = true
                completeRequest = buffered
                state.request.reset()
            }
        }

        if (completeRequest != null) {
            safeCallback(state.onEvent, TcpProxyEvent.WriteCompleted(payload.size))
            try {
                executor.execute { processRequest(state, requireNotNull(completeRequest)) }
            } catch (error: Throwable) {
                failFlow(state, error)
                return Result.failure(error)
            }
        }
        return Result.success(Unit)
    }

    override fun shutdownOutput(flow: TcpProxyFlow): Result<Unit> =
        if (flows.containsKey(flow)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("HTTP compatibility flow is not connected"))
        }

    override fun availableWriteCapacity(flow: TcpProxyFlow): Int {
        val state = flows[flow] ?: return 0
        synchronized(state) {
            if (state.processing) return 0
            return (config.maxRequestBytes - state.request.size()).coerceAtLeast(0)
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

    private fun processRequest(state: FlowState, bytes: ByteArray) {
        try {
            val request = parseRequest(bytes)
            val response = fetchFinalResponse(state, request)
            emitFinalResponse(state, response)
        } catch (error: BadLegacyRequest) {
            logger("HTTP compatibility !! bad request: ${error.message}")
            emitErrorResponse(state, 400, "Bad Request", error.message ?: "Invalid HTTP request")
        } catch (error: ResponseTooLarge) {
            logger("HTTP compatibility !! ${error.message}")
            emitErrorResponse(state, 502, "Bad Gateway", error.message ?: "Upstream response too large")
        } catch (error: Throwable) {
            val reason = error.message ?: error.javaClass.simpleName
            logger("HTTP compatibility !! upstream failed: $reason")
            emitErrorResponse(state, 502, "Bad Gateway", "YAME could not fetch the upstream resource")
        }
    }

    private fun fetchFinalResponse(state: FlowState, request: LegacyRequest): FinalResponse {
        var uri = initialUri(state.flow, request)
        var method = request.method
        var body = request.body
        var redirects = 0

        while (true) {
            requireSupportedScheme(uri)
            logger("HTTP compatibility => $method $uri")

            val builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(config.requestTimeoutMillis))

            request.headers.forEach { (name, value) ->
                if (name.lowercase(Locale.ROOT) !in REQUEST_HEADERS_TO_STRIP) {
                    builder.header(name, value)
                }
            }
            builder.header("Accept-Encoding", "identity")
            builder.method(
                method,
                if (body.isEmpty()) {
                    HttpRequest.BodyPublishers.noBody()
                } else {
                    HttpRequest.BodyPublishers.ofByteArray(body)
                },
            )

            val response = httpClient.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            val status = response.statusCode()
            val location = response.headers().firstValue("location").orElse(null)

            if (status in REDIRECT_STATUS_CODES && location != null) {
                response.body().close()
                if (redirects >= config.maxRedirects) {
                    throw IllegalStateException("HTTP redirect limit (${config.maxRedirects}) exceeded")
                }

                val next = uri.resolve(location)
                requireSupportedScheme(next)
                redirects++
                logger("HTTP compatibility .. redirect $status $uri -> $next")
                uri = next

                if (status == 303 || ((status == 301 || status == 302) && method.equals("POST", true))) {
                    method = "GET"
                    body = ByteArray(0)
                }
                continue
            }

            response.body().use { input ->
                val contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L)
                if (contentLength > config.maxResponseBytes.toLong()) {
                    throw ResponseTooLarge(
                        "Upstream response is $contentLength bytes; limit is ${config.maxResponseBytes}",
                    )
                }
                val responseBody = readBounded(input, config.maxResponseBytes)
                return FinalResponse(
                    uri = uri,
                    statusCode = status,
                    headers = response.headers().map(),
                    body = responseBody,
                )
            }
        }
    }

    private fun emitFinalResponse(state: FlowState, response: FinalResponse) {
        val head = buildString {
            append("HTTP/1.0 ")
            append(response.statusCode)
            append(' ')
            append(reasonPhrase(response.statusCode))
            append("\r\n")

            response.headers.forEach { (name, values) ->
                if (name.lowercase(Locale.ROOT) !in RESPONSE_HEADERS_TO_STRIP) {
                    values.forEach { value ->
                        append(name)
                        append(": ")
                        append(value)
                        append("\r\n")
                    }
                }
            }
            append("Content-Length: ${response.body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)

        logger(
            "HTTP compatibility <= ${response.statusCode} ${response.uri} " +
                "(${response.body.size} bytes, TLS hidden from peer)",
        )
        emitBytes(state, head)
        var offset = 0
        while (offset < response.body.size) {
            val end = minOf(offset + RESPONSE_CHUNK_BYTES, response.body.size)
            emitBytes(state, response.body.copyOfRange(offset, end))
            offset = end
        }
        finishFlow(state)
    }

    private fun emitErrorResponse(state: FlowState, status: Int, reason: String, message: String) {
        val body = "$status $reason\r\n$message\r\n".toByteArray(StandardCharsets.US_ASCII)
        val response = buildString {
            append("HTTP/1.0 $status $reason\r\n")
            append("Content-Type: text/plain; charset=us-ascii\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        emitBytes(state, response)
        emitBytes(state, body)
        finishFlow(state)
    }

    private fun emitBytes(state: FlowState, payload: ByteArray) {
        if (payload.isEmpty() || flows[state.flow] !== state) return
        awaitReadsEnabled(state)
        if (closed || flows[state.flow] !== state || state.flow.generation < minimumGeneration.get()) return
        safeCallback(state.onEvent, TcpProxyEvent.Payload(payload.copyOf()))
    }

    private fun awaitReadsEnabled(state: FlowState) {
        synchronized(state.readMonitor) {
            while (
                state.readsPaused &&
                !closed &&
                flows[state.flow] === state &&
                state.flow.generation >= minimumGeneration.get()
            ) {
                state.readMonitor.wait()
            }
        }
    }

    private fun finishFlow(state: FlowState) {
        if (flows[state.flow] === state) {
            safeCallback(state.onEvent, TcpProxyEvent.EndOfStream)
        }
    }

    private fun failFlow(state: FlowState, error: Throwable) {
        if (removeFlow(state) && !closed) {
            safeCallback(state.onEvent, TcpProxyEvent.Failure(error))
        }
    }

    private fun initialUri(flow: TcpProxyFlow, request: LegacyRequest): URI {
        val absolute = runCatching { URI(request.target) }.getOrNull()
        if (absolute?.isAbsolute == true) {
            if (!absolute.scheme.equals("http", ignoreCase = true)) {
                throw BadLegacyRequest("Port 80 compatibility requests must start as HTTP")
            }
            return absolute
        }

        val host = request.headers
            .firstOrNull { it.first.equals("Host", ignoreCase = true) }
            ?.second
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: flow.key.remoteAddress.toString()
        val target = if (request.target.startsWith('/')) request.target else "/${request.target}"
        return try {
            URI("http://$host$target")
        } catch (error: Exception) {
            throw BadLegacyRequest("Invalid HTTP Host or request target")
        }
    }

    private fun parseRequest(bytes: ByteArray): LegacyRequest {
        val headerEnd = findHeaderEnd(bytes)
        if (headerEnd < 0) throw BadLegacyRequest("Incomplete HTTP request headers")

        val text = String(bytes, 0, headerEnd, StandardCharsets.ISO_8859_1)
        val lines = text.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ', limit = 3)
            ?: throw BadLegacyRequest("Missing HTTP request line")
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/")) {
            throw BadLegacyRequest("Malformed HTTP request line")
        }

        val method = requestLine[0].uppercase(Locale.ROOT)
        if (!METHOD_PATTERN.matches(method)) throw BadLegacyRequest("Invalid HTTP method")
        if (method == "CONNECT") throw BadLegacyRequest("CONNECT is not valid in HTTP compatibility mode")

        val headers = lines.drop(1).map { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) throw BadLegacyRequest("Malformed HTTP header")
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (!HEADER_NAME_PATTERN.matches(name) || value.any { it == '\r' || it == '\n' || it.code == 0 }) {
                throw BadLegacyRequest("Invalid HTTP header")
            }
            name to value
        }

        if (headers.any { it.first.equals("Transfer-Encoding", ignoreCase = true) }) {
            throw BadLegacyRequest("Chunked legacy HTTP requests are not supported")
        }
        val contentLengths = headers
            .filter { it.first.equals("Content-Length", ignoreCase = true) }
            .map { it.second.toLongOrNull() ?: throw BadLegacyRequest("Invalid Content-Length") }
            .distinct()
        if (contentLengths.size > 1) throw BadLegacyRequest("Conflicting Content-Length headers")
        val contentLength = contentLengths.singleOrNull() ?: 0L
        if (contentLength < 0 || contentLength > config.maxRequestBytes) {
            throw BadLegacyRequest("HTTP request body exceeds compatibility limit")
        }

        val bodyStart = headerEnd + HEADER_DELIMITER.size
        if (bytes.size - bodyStart != contentLength.toInt()) {
            throw BadLegacyRequest("HTTP request body length does not match Content-Length")
        }

        return LegacyRequest(
            method = method,
            target = requestLine[1],
            headers = headers,
            body = bytes.copyOfRange(bodyStart, bytes.size),
        )
    }

    private fun requestLengthIfComplete(bytes: ByteArray): Int? {
        val headerEnd = findHeaderEnd(bytes)
        if (headerEnd < 0) return null
        val headerBytes = headerEnd + HEADER_DELIMITER.size
        val text = String(bytes, 0, headerEnd, StandardCharsets.ISO_8859_1)
        val contentLengthValues = text.split("\r\n")
            .drop(1)
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                if (!line.substring(0, separator).trim().equals("Content-Length", ignoreCase = true)) {
                    return@mapNotNull null
                }
                line.substring(separator + 1).trim().toLongOrNull()
            }
            .distinct()
        if (contentLengthValues.size > 1) return headerBytes
        val contentLength = contentLengthValues.singleOrNull() ?: 0L
        if (contentLength < 0 || contentLength > config.maxRequestBytes) return headerBytes
        val total = headerBytes.toLong() + contentLength
        if (total > config.maxRequestBytes) return headerBytes
        return if (bytes.size >= total) total.toInt() else null
    }

    private fun findHeaderEnd(bytes: ByteArray): Int {
        if (bytes.size < HEADER_DELIMITER.size) return -1
        for (index in 0..bytes.size - HEADER_DELIMITER.size) {
            if (
                bytes[index] == '\r'.code.toByte() &&
                bytes[index + 1] == '\n'.code.toByte() &&
                bytes[index + 2] == '\r'.code.toByte() &&
                bytes[index + 3] == '\n'.code.toByte()
            ) {
                return index
            }
        }
        return -1
    }

    private fun requireSupportedScheme(uri: URI) {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            throw IllegalStateException("Unsupported redirect scheme: ${uri.scheme}")
        }
        if (uri.host.isNullOrBlank()) {
            throw IllegalStateException("HTTP upstream URI has no host")
        }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(RESPONSE_CHUNK_BYTES)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (total + count > limit) {
                throw ResponseTooLarge("Upstream response exceeds compatibility limit of $limit bytes")
            }
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    override fun closeFlow(flow: TcpProxyFlow) {
        flows[flow]?.let(::removeFlow)
    }

    private fun removeFlow(state: FlowState): Boolean {
        if (!flows.remove(state.flow, state)) return false
        synchronized(state.readMonitor) {
            state.readsPaused = false
            state.readMonitor.notifyAll()
        }
        flowSlots.release()
        return true
    }

    override fun invalidateBefore(generation: Long) {
        minimumGeneration.accumulateAndGet(generation, ::maxOf)
        flows.values
            .filter { it.flow.generation < generation }
            .forEach(::removeFlow)
    }

    override fun close() {
        if (closed) return
        closed = true
        flows.values.toList().forEach(::removeFlow)
        executor.shutdownNow()
        runCatching { executor.awaitTermination(CLOSE_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
    }

    private fun safeCallback(callback: (TcpProxyEvent) -> Unit, event: TcpProxyEvent) {
        runCatching { callback(event) }
    }

    private class BadLegacyRequest(message: String) : IllegalArgumentException(message)
    private class ResponseTooLarge(message: String) : IllegalStateException(message)

    private companion object {
        val HEADER_DELIMITER = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        val METHOD_PATTERN = Regex("[A-Z!#$%&'*+.^_`|~-]+")
        val HEADER_NAME_PATTERN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        val REQUEST_HEADERS_TO_STRIP = setOf(
            "host",
            "connection",
            "proxy-connection",
            "keep-alive",
            "transfer-encoding",
            "te",
            "trailer",
            "upgrade",
            "content-length",
            "accept-encoding",
        )
        val RESPONSE_HEADERS_TO_STRIP = setOf(
            "connection",
            "proxy-connection",
            "keep-alive",
            "transfer-encoding",
            "trailer",
            "upgrade",
            "content-length",
        )
        const val RESPONSE_CHUNK_BYTES = 4 * 1024
        const val CLOSE_JOIN_TIMEOUT_MILLIS = 1_000L

        fun reasonPhrase(status: Int): String = when (status) {
            200 -> "OK"
            201 -> "Created"
            202 -> "Accepted"
            204 -> "No Content"
            206 -> "Partial Content"
            300 -> "Multiple Choices"
            301 -> "Moved Permanently"
            302 -> "Found"
            303 -> "See Other"
            304 -> "Not Modified"
            307 -> "Temporary Redirect"
            308 -> "Permanent Redirect"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            408 -> "Request Timeout"
            409 -> "Conflict"
            410 -> "Gone"
            413 -> "Content Too Large"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> "Upstream Response"
        }
    }
}
