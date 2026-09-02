package no.skasti.serialmodem.ppp.proxy

import no.skasti.serialmodem.observer.HttpProxyActionKind
import no.skasti.serialmodem.observer.YameEvent
import no.skasti.serialmodem.ppp.proxy.cookies.BoundedCookieOverrides
import no.skasti.serialmodem.ppp.proxy.cookies.BoundedCookieStore
import no.skasti.serialmodem.ppp.proxy.cookies.CookieOverride
import no.skasti.serialmodem.ppp.proxy.routing.LegacyOriginRouteTable
import no.skasti.serialmodem.ppp.proxy.transform.LegacyTextResourceTransformer
import no.skasti.serialmodem.ppp.proxy.transform.InFlightResourceWork
import no.skasti.serialmodem.ppp.proxy.transform.CachedResource
import no.skasti.serialmodem.ppp.proxy.transform.Resource
import no.skasti.serialmodem.ppp.proxy.transform.ResourceCache
import no.skasti.serialmodem.ppp.proxy.transform.ResourceCacheKey
import no.skasti.serialmodem.ppp.proxy.transform.ResourceWorkKey
import no.skasti.serialmodem.ppp.proxy.transform.ResourceRepresentation
import no.skasti.serialmodem.ppp.proxy.transform.cachePolicyFrom
import no.skasti.serialmodem.ppp.proxy.transform.validatorsFrom
import no.skasti.serialmodem.ppp.proxy.transform.sameSourceRepresentation
import no.skasti.serialmodem.ppp.proxy.transform.sourceFingerprint
import no.skasti.serialmodem.ppp.proxy.transform.ResourceTransformationContext
import no.skasti.serialmodem.ppp.proxy.transform.ResourceTransformationPipeline
import no.skasti.serialmodem.ppp.tcp.TcpProxy
import no.skasti.serialmodem.ppp.tcp.TcpProxyEvent
import no.skasti.serialmodem.ppp.tcp.TcpProxyFlow
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
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

internal class SystemHttpCompatibilityProxy(
    private val config: PppHttpCompatibilityConfig = PppHttpCompatibilityConfig(enabled = true),
    private val logger: (String) -> Unit = {},
    private val eventSink: (YameEvent) -> Unit = {},
    private val resourceRegistryHooks: ResourceRegistryHooks = ResourceRegistryHooks(),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.requestTimeoutMillis))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : TcpProxy {
    private data class ClientCookie(val name: String, val value: String)
    private data class SessionKey(val generation: Long, val peerAddress: String)
    private data class SessionState(
        val cookieManager: CookieManager = CookieManager(BoundedCookieStore(), CookiePolicy.ACCEPT_ORIGINAL_SERVER),
        val cookieOverrides: BoundedCookieOverrides = BoundedCookieOverrides(),
        val resources: NavigationResourceRegistry,
    )
    private data class FlowState(
        val flow: TcpProxyFlow,
        val onEvent: (TcpProxyEvent) -> Unit,
        val session: SessionState,
        val request: ByteArrayOutputStream = ByteArrayOutputStream(),
        val readMonitor: Object = Object(),
        val slotReleased: AtomicBoolean = AtomicBoolean(),
        val taskStarted: AtomicBoolean = AtomicBoolean(),
        val clientCookies: MutableList<ClientCookie> = mutableListOf(),
        @Volatile var processing: Boolean = false,
        @Volatile var readsPaused: Boolean = false,
        @Volatile var cancelled: Boolean = false,
        @Volatile var responseStarted: Boolean = false,
        @Volatile var expectContinueSent: Boolean = false,
        @Volatile var task: Future<*>? = null,
        @Volatile var interimTask: Future<*>? = null,
    )

    private data class LegacyRequest(val method: String, val target: String, val headers: List<Pair<String, String>>, val body: ByteArray)
    private sealed interface FinalResponseBody {
        data class Buffered(val bytes: ByteArray) : FinalResponseBody
        data class Streaming(val input: InputStream) : FinalResponseBody
    }
    private data class FinalResponse(
        val legacyUri: URI,
        val uri: URI,
        val statusCode: Int,
        val headers: Map<String, List<String>>,
        val body: FinalResponseBody,
        val contentLength: Long?,
        val exposeCookies: Boolean,
        val locationAlreadyLegacy: Boolean = false,
        val effectiveBaseUri: URI? = null,
        val referenceRole: ReferenceRole = ReferenceRole.NAVIGATION,
        val establishesNavigationGraph: Boolean = false,
        val requestHeaders: Map<String, List<String>> = emptyMap(),
        val requestMethod: String = "GET",
    )

    private enum class ExpectationDisposition { NONE, CONTINUE, UNSUPPORTED }

    private val executor = Executors.newFixedThreadPool(config.maxFlows) { runnable ->
        Thread(runnable, "http-compatibility-proxy").apply { isDaemon = true }
    }
    private val flowSlots = Semaphore(config.maxFlows)
    private val flows = ConcurrentHashMap<TcpProxyFlow, FlowState>()
    private val sessionStates = ConcurrentHashMap<SessionKey, SessionState>()
    private val originRoutes = LegacyOriginRouteTable()
    private val resourceTransformations = ResourceTransformationPipeline(listOf(LegacyTextResourceTransformer()))
    private val resourceCache = ResourceCache(
        maxEntries = config.maxRepresentationCacheEntries,
        maxBytes = config.maxRepresentationCacheBytes,
    )
    private val inFlightResourceWork = InFlightResourceWork()
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
        val state =
            FlowState(
                flow = flow,
                onEvent = onEvent,
                session = sessionStates.computeIfAbsent(sessionKey(flow)) {
                    SessionState(
                        resources = NavigationResourceRegistry(
                            maxContexts = config.maxResourceContexts,
                            maxNodesPerContext = config.maxResourceNodesPerContext,
                            maxEdgesPerContext = config.maxResourceEdgesPerContext,
                            hooks = resourceRegistryHooks,
                        ),
                    )
                },
            )
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
        var fetchGraphs: List<NavigationResourceGraph> = emptyList()
        var fetchLegacyUri: URI? = null
        var fetchUpstreamUri: URI? = null
        try {
            ensureActive(state)
            val request = parseRequest(bytes)
            val legacyUri = legacyUri(state.flow, request)
            val upstreamUri = originRoutes.resolve(state.flow, legacyUri)
            fetchLegacyUri = legacyUri
            fetchUpstreamUri = upstreamUri
            fetchGraphs = state.session.resources.contextsFor(legacyUri)
            fetchGraphs.forEach { graph ->
                graph.markFetched(
                    legacyUri = legacyUri,
                    upstreamUri = upstreamUri,
                    contentBase = upstreamUri,
                    state = ResourceState.FETCHING,
                )
            }
            val response = fetchFinalResponse(state, request)
            ensureActive(state)
            emitFinalResponse(state, response)
        } catch (_: CancellationException) {
            markFetchFailed(fetchGraphs, fetchLegacyUri, fetchUpstreamUri)
        } catch (error: InterruptedException) {
            markFetchFailed(fetchGraphs, fetchLegacyUri, fetchUpstreamUri)
            Thread.currentThread().interrupt()
        } catch (error: BadLegacyRequest) {
            markFetchFailed(fetchGraphs, fetchLegacyUri, fetchUpstreamUri)
            logger("HTTP compatibility !! bad request: ${error.message}")
            emitEvent(state, HttpProxyActionKind.ERROR, error.message ?: "Invalid HTTP request")
            emitErrorUnlessStarted(state, 400, "Bad Request", error.message ?: "Invalid HTTP request")
        } catch (error: ResponseTooLarge) {
            markFetchFailed(fetchGraphs, fetchLegacyUri, fetchUpstreamUri)
            logger("HTTP compatibility !! ${error.message}")
            emitEvent(state, HttpProxyActionKind.ERROR, error.message ?: "Upstream response too large")
            emitErrorUnlessStarted(state, 502, "Bad Gateway", error.message ?: "Upstream response too large")
        } catch (error: Throwable) {
            markFetchFailed(fetchGraphs, fetchLegacyUri, fetchUpstreamUri)
            logger("HTTP compatibility !! upstream failed: ${error.message ?: error.javaClass.simpleName}")
            emitEvent(state, HttpProxyActionKind.ERROR, error.message ?: error.javaClass.simpleName)
            emitErrorUnlessStarted(state, 502, "Bad Gateway", "YAME could not fetch the upstream resource")
        } finally {
            synchronized(state) { state.processing = false; state.task = null }
            if (flows[state.flow] !== state) releaseSlot(state)
        }
    }

    private fun markFetchFailed(
        graphs: List<NavigationResourceGraph>,
        legacyUri: URI?,
        upstreamUri: URI?,
    ) {
        if (legacyUri == null || upstreamUri == null) return
        graphs.forEach { graph ->
            graph.markFetched(
                legacyUri = legacyUri,
                upstreamUri = upstreamUri,
                contentBase = upstreamUri,
                state = ResourceState.FAILED,
            )
        }
    }

    private fun fetchFinalResponse(state: FlowState, request: LegacyRequest): FinalResponse {
        val legacyUri = legacyUri(state.flow, request)
        var uri = originRoutes.resolve(state.flow, legacyUri)
        val initialUpstreamUri = uri
        val followedExactMapping = originRoutes.isExactMapping(state.flow, legacyUri, uri)
        val referenceRole =
            if (followedExactMapping) {
                originRoutes.referenceRole(state.flow, legacyUri) ?: ReferenceRole.NAVIGATION
            } else {
                ReferenceRole.NAVIGATION
            }
        val hideExactRedirect = referenceRole == ReferenceRole.SUBRESOURCE
        val mappedContentBase =
            followedExactMapping && hideExactRedirect && originRoutes.usesTargetAsContentBase(state.flow, legacyUri)
        val originalUri = legacyUri
        seedClientCookies(state, request.headers)
        var method = request.method
        var body = request.body
        var redirects = 0
        var forwardSensitiveHeaders = canForwardSensitiveHeaders(legacyUri, uri)
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
                val next = uri.resolve(location)
                requireSupportedScheme(next)
                logger("HTTP compatibility .. redirect $status $uri -> $next")
                emitEvent(
                    state,
                    HttpProxyActionKind.REDIRECT,
                    "$status $uri -> $next",
                )

                val legacyNext = legacyRedirectUri(next)
                if (!hideExactRedirect && shouldExposeRedirect(legacyUri, next)) {
                    response.body().close()
                    originRoutes.rememberExact(state.flow, legacyNext, next)
                    logger("HTTP compatibility <= client redirect $status $legacyNext")
                    emitEvent(
                        state,
                        HttpProxyActionKind.ROUTED,
                        "client redirect ${LegacyHttpUrl.withoutFragment(legacyNext)} -> ${LegacyHttpUrl.withoutFragment(next)}",
                    )
                    return FinalResponse(
                        legacyUri = legacyUri,
                        uri = uri,
                        statusCode = status,
                        headers = response.headers().map().withHeader("location", legacyNext.toString()),
                        body = FinalResponseBody.Buffered(ByteArray(0)),
                        contentLength = 0,
                        exposeCookies = sameOrigin(originalUri, uri),
                        locationAlreadyLegacy = true,
                        referenceRole = referenceRole,
                        requestMethod = request.method,
                    )
                }

                response.body().close()
                if (redirects >= config.maxRedirects) {
                    throw IllegalStateException("HTTP redirect limit (${config.maxRedirects}) exceeded")
                }
                if (!sameOrigin(uri, next)) forwardSensitiveHeaders = false
                redirects++
                uri = next
                val nextMethod = redirectedMethod(status, method)
                if (!nextMethod.equals(method, ignoreCase = true)) body = ByteArray(0)
                method = nextMethod
                continue
            }
            val responseHeaders = response.headers().map()
            val isHead = request.method.equals("HEAD", true)
            val preservesRepresentationLength = isHead || status == 304
            val upstreamContentLength = response.headers().firstValueAsLong("content-length").orElse(-1L)
            val responseBody =
                if (preservesRepresentationLength) {
                    response.body().close()
                    FinalResponseBody.Buffered(ByteArray(0))
                } else {
                    if (upstreamContentLength > config.maxResponseBytes.toLong()) {
                        response.body().close()
                        throw ResponseTooLarge(
                            "Upstream response is $upstreamContentLength bytes; limit is ${config.maxResponseBytes}",
                        )
                    }
                    FinalResponseBody.Buffered(
                        response.body().use { input -> readBounded(state, input, config.maxResponseBytes) },
                    )
                }
            val navigationLikeResponse = responseEstablishesNavigationOrigin(method, status, responseHeaders)
            when {
                navigationLikeResponse && referenceRole == ReferenceRole.NAVIGATION -> {
                    originRoutes.remember(state.flow, legacyUri, uri)?.let { (legacyOrigin, upstreamOrigin) ->
                        logger("HTTP compatibility .. session route $legacyOrigin -> $upstreamOrigin")
                        emitEvent(state, HttpProxyActionKind.ROUTED, "session $legacyOrigin -> $upstreamOrigin")
                    }
                }
                redirects > 0 && uri != initialUpstreamUri -> {
                    originRoutes.rememberExact(
                        state.flow,
                        legacyUri,
                        uri,
                        role = referenceRole,
                        useTargetAsContentBase = referenceRole == ReferenceRole.SUBRESOURCE,
                    )
                    logger("HTTP compatibility .. exact route $legacyUri -> $uri")
                    emitEvent(state, HttpProxyActionKind.ROUTED, "exact $legacyUri -> $uri")
                }
            }
            return FinalResponse(
                legacyUri = legacyUri,
                uri = uri,
                statusCode = status,
                headers = responseHeaders,
                body = responseBody,
                contentLength =
                    when {
                        preservesRepresentationLength && upstreamContentLength >= 0L -> upstreamContentLength
                        responseBody is FinalResponseBody.Buffered -> responseBody.bytes.size.toLong()
                        upstreamContentLength >= 0L -> upstreamContentLength
                        else -> null
                    },
                exposeCookies = sameOrigin(originalUri, uri),
                effectiveBaseUri =
                    if (mappedContentBase || (hideExactRedirect && redirects > 0 && uri != initialUpstreamUri)) uri else null,
                referenceRole = referenceRole,
                establishesNavigationGraph =
                    referenceRole == ReferenceRole.NAVIGATION &&
                        responseIsNavigationDocument(method, responseHeaders),
                requestHeaders =
                    request.headers.groupBy(
                        { it.first.lowercase(Locale.ROOT) },
                        { it.second },
                    ),
                requestMethod = request.method,
            )
        }
    }

    private fun emitFinalResponse(state: FlowState, response: FinalResponse) {
        val resourceGraphs =
            if (response.establishesNavigationGraph) {
                listOf(state.session.resources.startNavigation(response.legacyUri))
            } else {
                state.session.resources.contextsFor(response.legacyUri)
            }
        resourceGraphs.forEach { graph ->
            graph.markFetched(
                legacyUri = response.legacyUri,
                upstreamUri = response.uri,
                contentBase = response.effectiveBaseUri ?: response.uri,
                state = ResourceState.FETCHING,
            )
        }
        try {
            val bufferedBody = (response.body as? FinalResponseBody.Buffered)?.bytes
            val transformedRepresentation =
                bufferedBody?.let {
                    rewriteLegacyRepresentation(
                        flow = state.flow,
                        session = state.session,
                        graphs = resourceGraphs,
                        parentLegacyUri = response.legacyUri,
                        upstreamUri = response.uri,
                        statusCode = response.statusCode,
                        requestHeaders = response.requestHeaders,
                        headers = response.headers,
                        body = it,
                        resolutionBaseUri = response.effectiveBaseUri ?: response.uri,
                        forceAbsoluteRelativeReferences = response.effectiveBaseUri != null,
                        referenceRole = response.referenceRole,
                        requestMethod = response.requestMethod,
                    )
                }
            val transformedHeaders = transformedRepresentation?.headers ?: response.headers
            val legacyHeaders = rewriteLegacyHeaders(state.flow, transformedHeaders, response.locationAlreadyLegacy)
            val legacyBody = transformedRepresentation?.body
            val bodyRewritten = bufferedBody != null && legacyBody != null && !legacyBody.contentEquals(bufferedBody)
            val rewritten = legacyHeaders != response.headers || bodyRewritten
        val contentLength =
            when {
                bufferedBody != null && response.contentLength != null &&
                    response.contentLength != bufferedBody.size.toLong() -> response.contentLength
                legacyBody != null -> legacyBody.size.toLong()
                else -> response.contentLength
            }
        val nominated = connectionNominatedHeaders(legacyHeaders.flatMap { (name, values) -> values.map { name to it } })
        val head = buildString {
            append("HTTP/1.0 ${response.statusCode} ${reasonPhrase(response.statusCode)}\r\n")
            legacyHeaders.forEach { (name, values) ->
                val normalized = name.lowercase(Locale.ROOT)
                val hiddenCrossOriginCookie = !response.exposeCookies && normalized in RESPONSE_COOKIE_HEADERS
                if (normalized !in RESPONSE_HEADERS_TO_STRIP && normalized !in nominated &&
                    !hiddenCrossOriginCookie) {
                    values.forEach { append("$name: $it\r\n") }
                }
            }
            contentLength?.let { append("Content-Length: $it\r\n") }
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        val rewriteSuffix = if (rewritten) ", HTTPS references rewritten for legacy client" else ""
        val sizeDescription = contentLength?.let { "$it bytes" } ?: "streaming response"
        logger(
            "HTTP compatibility <= ${response.statusCode} ${response.uri} " +
                "($sizeDescription, TLS hidden from peer$rewriteSuffix)",
        )
        emitEvent(
            state,
            HttpProxyActionKind.RESPONSE,
            "${response.statusCode} ${response.uri} · $sizeDescription · TLS hidden" +
                if (rewritten) " · HTTPS links rewritten" else "",
        )
        state.responseStarted = true
        emitBytes(state, head)
        when (val body = response.body) {
            is FinalResponseBody.Buffered -> {
                val bytes = requireNotNull(legacyBody)
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(offset + RESPONSE_CHUNK_BYTES, bytes.size)
                    emitBytes(state, bytes.copyOfRange(offset, end))
                    offset = end
                }
            }
            is FinalResponseBody.Streaming -> {
                body.input.use { input ->
                    val buffer = ByteArray(RESPONSE_CHUNK_BYTES)
                    while (true) {
                        ensureActive(state)
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        emitBytes(state, buffer.copyOf(count))
                    }
                }
            }
        }
            resourceGraphs.forEach { graph ->
                graph.markFetched(
                    legacyUri = response.legacyUri,
                    upstreamUri = response.uri,
                    contentBase = response.effectiveBaseUri ?: response.uri,
                    state = ResourceState.READY,
                )
            }
            finishFlow(state)
        } catch (error: Throwable) {
            resourceGraphs.forEach { graph ->
                graph.markFetched(
                    legacyUri = response.legacyUri,
                    upstreamUri = response.uri,
                    contentBase = response.effectiveBaseUri ?: response.uri,
                    state = ResourceState.FAILED,
                )
            }
            throw error
        }
    }

    private fun rewriteLegacyHeaders(
        flow: TcpProxyFlow,
        headers: Map<String, List<String>>,
        locationAlreadyLegacy: Boolean,
    ): Map<String, List<String>> =
        headers.mapValues { (name, values) ->
            if (locationAlreadyLegacy && name.equals("location", ignoreCase = true)) {
                values
            } else if (name.lowercase(Locale.ROOT) in URI_RESPONSE_HEADERS_TO_REWRITE) {
                values.map { rewriteAbsoluteUrls(flow, it, hideRedirect = false) }
            } else {
                values
            }
        }

    private fun rewriteLegacyRepresentation(
        flow: TcpProxyFlow,
        session: SessionState,
        graphs: List<NavigationResourceGraph>,
        parentLegacyUri: URI,
        upstreamUri: URI,
        statusCode: Int,
        requestHeaders: Map<String, List<String>>,
        headers: Map<String, List<String>>,
        body: ByteArray,
        resolutionBaseUri: URI,
        forceAbsoluteRelativeReferences: Boolean,
        referenceRole: ReferenceRole,
        requestMethod: String,
    ): ResourceRepresentation {
        val source =
            Resource(
                upstreamUri = upstreamUri,
                source = ResourceRepresentation(
                    statusCode = statusCode,
                    headers = headers,
                    body = body,
                ),
            )
        val context =
            ResourceTransformationContext(
                navigationIds = graphs.map { it.id }.toSet(),
                legacyUri = parentLegacyUri,
                upstreamUri = upstreamUri,
                kind = graphs.firstNotNullOfOrNull { it.nodeSnapshot(parentLegacyUri)?.kind }
                    ?: resourceKindForResponse(headers, referenceRole),
                relation = graphs.asSequence()
                    .flatMap { it.snapshot().edges.asSequence() }
                    .firstOrNull { it.childLegacyUri == parentLegacyUri }
                    ?.relation,
                role = graphs.firstNotNullOfOrNull { it.nodeSnapshot(parentLegacyUri)?.role }
                    ?: referenceRole,
                requestHeaders = requestHeaders,
                requestMethod = requestMethod,
                transformationProfile = config.transformationProfile,
                rewriteText = { sourceText ->
                    rewriteBodyUrlsWithBase(
                        flow = flow,
                        session = session,
                        graphs = graphs,
                        parentLegacyUri = parentLegacyUri,
                        headers = headers,
                        source = sourceText,
                        baseUri = resolutionBaseUri,
                        forceAbsoluteRelativeReferences = forceAbsoluteRelativeReferences,
                    )
                },
            )

        val cacheKey =
            ResourceCacheKey(
                legacyUri = parentLegacyUri,
                upstreamUri = upstreamUri,
                profile = config.transformationProfile,
            )
        resourceCache.get(cacheKey)
            ?.takeIf { cached -> sameSourceRepresentation(cached.resource.source, source.source) }
            ?.let { return it.resource.representation }

        val workKey =
            ResourceWorkKey(
                cacheKey = cacheKey,
                sourceFingerprint = sourceFingerprint(source.source),
            )
        val transformed =
            inFlightResourceWork.getOrStart(workKey) {
                resourceCache.get(cacheKey)
                    ?.takeIf { cached -> sameSourceRepresentation(cached.resource.source, source.source) }
                    ?.let {
                        return@getOrStart no.skasti.serialmodem.ppp.proxy.transform.ResourceTransformationState(it.resource)
                    }
                resourceTransformations.transform(context, source)
            }

        if (transformed.cacheable && requestMethod.equals("GET", ignoreCase = true) && statusCode != 304) {
            resourceCache.put(
                cacheKey,
                CachedResource(
                    resource = transformed.resource,
                    storedAt = Instant.now(),
                    cachePolicy = cachePolicyFrom(headers),
                    validators = validatorsFrom(headers),
                ),
            )
        }
        return transformed.resource.representation
    }

    private fun resourceKindForResponse(
        headers: Map<String, List<String>>,
        role: ReferenceRole,
    ): ResourceKind {
        val contentType =
            firstHeader(headers, "content-type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.ROOT)
        return when {
            contentType == "text/css" -> ResourceKind.STYLESHEET
            contentType?.startsWith("image/") == true -> ResourceKind.IMAGE
            contentType in setOf("text/javascript", "application/javascript", "application/x-javascript") -> ResourceKind.SCRIPT
            contentType in setOf("text/html", "application/xhtml+xml") && role == ReferenceRole.NAVIGATION -> ResourceKind.DOCUMENT
            contentType in setOf("text/html", "application/xhtml+xml") -> ResourceKind.FRAME
            else -> ResourceKind.OTHER
        }
    }

    private fun recordAbsoluteBodyReferences(
        flow: TcpProxyFlow,
        session: SessionState,
        graphs: List<NavigationResourceGraph>,
        parentLegacyUri: URI,
        headers: Map<String, List<String>>,
        source: String,
    ) {
        if (graphs.isEmpty()) return
        val contentType = firstHeader(headers, "content-type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        ABSOLUTE_HTTP_URL_PATTERN.findAll(source).forEach { match ->
            val upstreamUri = runCatching { URI(match.value) }.getOrNull() ?: return@forEach
            val relation =
                when (contentType) {
                    "text/html", "application/xhtml+xml" -> bodyReferenceRelation(source, match.range.first)
                    "text/css" -> ResourceRelation.OTHER_SUBRESOURCE
                    else -> ResourceRelation.OTHER_SUBRESOURCE
                }
            val legacyValue = rewriteAbsoluteUrl(
                flow = flow,
                rawUrl = match.value,
                hideRedirect = relation.role == ReferenceRole.SUBRESOURCE,
            ) ?: return@forEach
            val legacyUri = runCatching { URI(legacyValue) }.getOrNull() ?: return@forEach
            graphs.forEach { graph ->
                session.resources.discover(
                    graph = graph,
                    parentLegacyUri = parentLegacyUri,
                    childLegacyUri = legacyUri,
                    upstreamUri = upstreamUri,
                    relation = relation,
                    kind = resourceKindForRelation(relation),
                )
            }
        }
    }

    private fun rewriteBodyUrlsWithBase(
        flow: TcpProxyFlow,
        session: SessionState,
        graphs: List<NavigationResourceGraph>,
        parentLegacyUri: URI,
        headers: Map<String, List<String>>,
        source: String,
        baseUri: URI,
        forceAbsoluteRelativeReferences: Boolean,
    ): String {
        val contentType = firstHeader(headers, "content-type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return rewriteBodyAbsoluteUrls(flow, source)
        return when (contentType) {
            "text/css" -> rewriteCssUrlsWithBase(flow, session, graphs, parentLegacyUri, source, baseUri, forceAbsoluteRelativeReferences)
            "text/html", "application/xhtml+xml" ->
                rewriteHtmlUrlsWithBase(flow, session, graphs, parentLegacyUri, source, baseUri, forceAbsoluteRelativeReferences)
            else -> rewriteBodyAbsoluteUrls(flow, source)
        }
    }

    private fun rewriteCssUrlsWithBase(
        flow: TcpProxyFlow,
        session: SessionState,
        graphs: List<NavigationResourceGraph>,
        parentLegacyUri: URI,
        source: String,
        baseUri: URI,
        forceAbsoluteRelativeReferences: Boolean,
    ): String {
        var rewritten = CSS_IMPORT_URL_REFERENCE_PATTERN.replace(source) { match ->
            replaceDiscoveredUrlReferenceInMatch(
                flow, session, graphs, parentLegacyUri, match, listOf(1, 2, 3), baseUri,
                ResourceRelation.CSS_IMPORT, ResourceKind.STYLESHEET, forceAbsoluteRelativeReferences,
            )
        }
        rewritten = CSS_IMPORT_REFERENCE_PATTERN.replace(rewritten) { match ->
            replaceDiscoveredUrlReferenceInMatch(
                flow, session, graphs, parentLegacyUri, match, listOf(1, 2), baseUri,
                ResourceRelation.CSS_IMPORT, ResourceKind.STYLESHEET, forceAbsoluteRelativeReferences,
            )
        }
        rewritten = CSS_URL_REFERENCE_PATTERN.replace(rewritten) { match ->
            if (isCssImportUrl(rewritten, match.range.first)) {
                match.value
            } else {
                replaceDiscoveredUrlReferenceInMatch(
                    flow, session, graphs, parentLegacyUri, match, listOf(1, 2, 3), baseUri,
                    ResourceRelation.CSS_URL, ResourceKind.OTHER, forceAbsoluteRelativeReferences,
                )
            }
        }
        return rewriteBodyAbsoluteUrlsOutsideGeneratedMappings(flow, rewritten)
    }

    private fun rewriteHtmlUrlsWithBase(
        flow: TcpProxyFlow,
        session: SessionState,
        graphs: List<NavigationResourceGraph>,
        parentLegacyUri: URI,
        source: String,
        baseUri: URI,
        forceAbsoluteRelativeReferences: Boolean,
    ): String {
        val documentBaseUri = htmlDocumentBaseUri(source, baseUri)
        var rewritten = HTML_URL_ATTRIBUTE_PATTERN.replace(source) { match ->
            val urlGroup = listOf(1, 2, 3).mapNotNull { match.groups[it] }.firstOrNull()
                ?: return@replace match.value
            val tagStart = source.lastIndexOf('<', startIndex = match.range.first)
            val tagPrefix =
                if (tagStart >= 0) source.substring(tagStart, match.range.first).lowercase(Locale.ROOT)
                else ""
            val referenceBase =
                if (Regex("""<\s*base\b""").containsMatchIn(tagPrefix)) baseUri else documentBaseUri
            val relation = bodyReferenceRelation(source, urlGroup.range.first)
            val replacement = rewriteAndDiscoverUrlReference(
                flow = flow,
                session = session,
                graphs = graphs,
                parentLegacyUri = parentLegacyUri,
                baseUri = referenceBase,
                rawUrl = urlGroup.value,
                relation = relation,
                kind = resourceKindForRelation(relation),
                forceAbsoluteRelativeReferences = forceAbsoluteRelativeReferences,
            ) ?: return@replace match.value
            replaceMatchGroup(match, urlGroup, replacement)
        }
        rewritten = rewriteInlineCssWithBase(
            flow, session, graphs, parentLegacyUri, rewritten, documentBaseUri, forceAbsoluteRelativeReferences,
        )
        rewritten = rewriteMetaRefreshWithBase(
            flow, session, graphs, parentLegacyUri, rewritten, documentBaseUri, forceAbsoluteRelativeReferences,
        )
        return rewriteBodyAbsoluteUrlsOutsideGeneratedMappings(flow, rewritten)
    }

    private fun rewriteMetaRefreshWithBase(
        flow: TcpProxyFlow,
        session: SessionState,
        graphs: List<NavigationResourceGraph>,
        parentLegacyUri: URI,
        source: String,
        baseUri: URI,
        forceAbsoluteRelativeReferences: Boolean,
    ): String =
        HTML_META_TAG_PATTERN.replace(source) { tagMatch ->
            if (!isMetaRefreshTag(tagMatch.value)) return@replace tagMatch.value
            val contentMatch = HTML_ATTRIBUTE_PATTERN.findAll(tagMatch.value)
                .firstOrNull { it.groupValues[1].equals("content", ignoreCase = true) }
                ?: return@replace tagMatch.value
            val contentGroup = contentMatch.groups.drop(2).filterNotNull().firstOrNull { it.value.isNotEmpty() }
                ?: return@replace tagMatch.value
            val rewrittenContent = META_REFRESH_URL_PATTERN.replace(contentGroup.value) { urlMatch ->
                val urlGroup = listOf(2, 3, 4).mapNotNull { urlMatch.groups[it] }.firstOrNull()
                    ?: return@replace urlMatch.value
                val replacement = rewriteAndDiscoverUrlReference(
                    flow = flow,
                    session = session,
                    graphs = graphs,
                    parentLegacyUri = parentLegacyUri,
                    baseUri = baseUri,
                    rawUrl = urlGroup.value,
                    relation = ResourceRelation.META_REFRESH,
                    kind = ResourceKind.DOCUMENT,
                    forceAbsoluteRelativeReferences = forceAbsoluteRelativeReferences,
                ) ?: return@replace urlMatch.value
                replaceMatchGroup(urlMatch, urlGroup, replacement)
            }
            if (rewrittenContent == contentGroup.value) {
                tagMatch.value
            } else {
                val rewrittenAttribute = replaceMatchGroup(contentMatch, contentGroup, rewrittenContent)
                tagMatch.value.replaceRange(
                    contentMatch.range.first,
                    contentMatch.range.last + 1,
                    rewrittenAttribute,
                )
            }
        }

    private fun htmlDocumentBaseUri(source: String, responseBaseUri: URI): URI {
        val baseTag = HTML_BASE_TAG_PATTERN.find(source) ?: return responseBaseUri
        val href = HTML_ATTRIBUTE_PATTERN.findAll(baseTag.value)
            .firstOrNull { it.groupValues[1].equals("href", ignoreCase = true) }
            ?.let { match -> match.groupValues.drop(2).firstOrNull { it.isNotEmpty() } }
            ?: return responseBaseUri
        val reference = runCatching { URI(href) }.getOrNull() ?: return responseBaseUri
        return runCatching {
            if (reference.isAbsolute) reference else responseBaseUri.resolve(reference)
        }.getOrDefault(responseBaseUri)
    }

    private fun rewriteInlineCssWithBase(
        flow: TcpProxyFlow,
        session: SessionState,
        graphs: List<NavigationResourceGraph>,
        parentLegacyUri: URI,
        source: String,
        baseUri: URI,
        forceAbsoluteRelativeReferences: Boolean,
    ): String {
        var rewritten = HTML_STYLE_BLOCK_PATTERN.replace(source) { match ->
            val cssGroup = match.groups[1] ?: return@replace match.value
            replaceMatchGroup(
                match,
                cssGroup,
                rewriteCssFragmentWithBase(flow, session, graphs, parentLegacyUri, cssGroup.value, baseUri, forceAbsoluteRelativeReferences),
            )
        }
        rewritten = HTML_STYLE_ATTRIBUTE_PATTERN.replace(rewritten) { match ->
            val cssGroup = listOf(1, 2).mapNotNull { match.groups[it] }.firstOrNull()
                ?: return@replace match.value
            replaceMatchGroup(
                match,
                cssGroup,
                rewriteCssFragmentWithBase(flow, session, graphs, parentLegacyUri, cssGroup.value, baseUri, forceAbsoluteRelativeReferences),
            )
        }
        return rewritten
    }

    private fun rewriteCssFragmentWithBase(
        flow: TcpProxyFlow,
        session: SessionState,
        graphs: List<NavigationResourceGraph>,
        parentLegacyUri: URI,
        source: String,
        baseUri: URI,
        forceAbsoluteRelativeReferences: Boolean,
    ): String {
        var rewritten = CSS_IMPORT_URL_REFERENCE_PATTERN.replace(source) { match ->
            replaceDiscoveredUrlReferenceInMatch(
                flow, session, graphs, parentLegacyUri, match, listOf(1, 2, 3), baseUri,
                ResourceRelation.CSS_IMPORT, ResourceKind.STYLESHEET, forceAbsoluteRelativeReferences,
            )
        }
        rewritten = CSS_IMPORT_REFERENCE_PATTERN.replace(rewritten) { match ->
            replaceDiscoveredUrlReferenceInMatch(
                flow, session, graphs, parentLegacyUri, match, listOf(1, 2), baseUri,
                ResourceRelation.CSS_IMPORT, ResourceKind.STYLESHEET, forceAbsoluteRelativeReferences,
            )
        }
        rewritten = CSS_URL_REFERENCE_PATTERN.replace(rewritten) { match ->
            if (isCssImportUrl(rewritten, match.range.first)) {
                match.value
            } else {
                replaceDiscoveredUrlReferenceInMatch(
                    flow, session, graphs, parentLegacyUri, match, listOf(1, 2, 3), baseUri,
                    ResourceRelation.CSS_URL, ResourceKind.OTHER, forceAbsoluteRelativeReferences,
                )
            }
        }
        return rewritten
    }

    private fun isCssImportUrl(source: String, urlStart: Int): Boolean {
        val prefix = source.substring(0, urlStart)
        return Regex("""(?is)@import\s*$""").containsMatchIn(prefix.takeLast(64))
    }

    private fun replaceDiscoveredUrlReferenceInMatch(
        flow: TcpProxyFlow,
        session: SessionState,
        graphs: List<NavigationResourceGraph>,
        parentLegacyUri: URI,
        match: MatchResult,
        rawGroups: List<Int>,
        baseUri: URI,
        relation: ResourceRelation,
        kind: ResourceKind,
        forceAbsoluteRelativeReferences: Boolean,
    ): String {
        val urlGroup = rawGroups.mapNotNull { match.groups[it] }.firstOrNull() ?: return match.value
        val replacement = rewriteAndDiscoverUrlReference(
            flow = flow,
            session = session,
            graphs = graphs,
            parentLegacyUri = parentLegacyUri,
            baseUri = baseUri,
            rawUrl = urlGroup.value,
            relation = relation,
            kind = kind,
            forceAbsoluteRelativeReferences = forceAbsoluteRelativeReferences,
        ) ?: return match.value
        return replaceMatchGroup(match, urlGroup, replacement)
    }

    private fun rewriteAndDiscoverUrlReference(
        flow: TcpProxyFlow,
        session: SessionState,
        graphs: List<NavigationResourceGraph>,
        parentLegacyUri: URI,
        baseUri: URI,
        rawUrl: String,
        relation: ResourceRelation,
        kind: ResourceKind,
        forceAbsoluteRelativeReferences: Boolean,
    ): String? {
        if (rawUrl.isBlank() || rawUrl.startsWith('#')) return null
        val reference = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val upstreamUri =
            if (reference.isAbsolute) reference else runCatching { baseUri.resolve(reference) }.getOrNull() ?: return null
        val rewritten = rewriteAbsoluteUrl(
            flow = flow,
            rawUrl = upstreamUri.toString(),
            hideRedirect = relation.role == ReferenceRole.SUBRESOURCE,
        ) ?: return null
        val legacyUri = runCatching { URI(rewritten) }.getOrNull() ?: return rewritten
        graphs.forEach { graph ->
            session.resources.discover(
                graph = graph,
                parentLegacyUri = parentLegacyUri,
                childLegacyUri = legacyUri,
                upstreamUri = upstreamUri,
                relation = relation,
                kind = kind,
            )
        }
        return if (!reference.isAbsolute && !forceAbsoluteRelativeReferences) rawUrl else rewritten
    }

    private fun replaceUrlReferenceInMatch(
        flow: TcpProxyFlow,
        match: MatchResult,
        rawGroups: List<Int>,
        baseUri: URI,
        hideRedirect: Boolean,
    ): String {
        val urlGroup = rawGroups.mapNotNull { match.groups[it] }.firstOrNull() ?: return match.value
        val replacement = rewriteUrlReference(flow, baseUri, urlGroup.value, hideRedirect)
            ?: return match.value
        return replaceMatchGroup(match, urlGroup, replacement)
    }

    private fun replaceMatchGroup(match: MatchResult, group: MatchGroup, replacement: String): String {
        val start = group.range.first - match.range.first
        val endExclusive = group.range.last - match.range.first + 1
        return match.value.substring(0, start) + replacement + match.value.substring(endExclusive)
    }

    private fun rewriteUrlReference(
        flow: TcpProxyFlow,
        baseUri: URI,
        rawUrl: String,
        hideRedirect: Boolean,
    ): String? {
        if (rawUrl.isBlank() || rawUrl.startsWith('#')) return null
        val reference = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val target = if (reference.isAbsolute) reference else runCatching { baseUri.resolve(reference) }.getOrNull() ?: return null
        return rewriteAbsoluteUrl(flow, target.toString(), hideRedirect)
    }

    private fun rewriteBodyAbsoluteUrlsOutsideGeneratedMappings(flow: TcpProxyFlow, value: String): String =
        ABSOLUTE_HTTP_URL_PATTERN.replace(value) { match ->
            val target = runCatching { URI(match.value) }.getOrNull()
            if (target != null && target.scheme.equals("http", ignoreCase = true) && originRoutes.resolve(flow, target) != target) {
                match.value
            } else {
                rewriteAbsoluteUrl(
                    flow = flow,
                    rawUrl = match.value,
                    hideRedirect = bodyUrlHidesRedirect(value, match.range.first),
                ) ?: match.value
            }
        }


    private fun responseIsNavigationDocument(
        method: String,
        headers: Map<String, List<String>>,
    ): Boolean {
        if (!method.equals("GET", ignoreCase = true)) return false
        val contentType = firstHeader(headers, "content-type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return false
        return contentType in NAVIGATION_CONTENT_TYPES
    }

    private fun responseEstablishesNavigationOrigin(
        method: String,
        status: Int,
        headers: Map<String, List<String>>,
    ): Boolean {
        if (!method.equals("GET", ignoreCase = true) || status !in 200..299 || status == 206) return false
        val contentType = firstHeader(headers, "content-type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return false
        return contentType in NAVIGATION_CONTENT_TYPES
    }

    private fun firstHeader(headers: Map<String, List<String>>, name: String): String? =
        headers.entries
            .firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

    private fun rewriteBodyAbsoluteUrls(flow: TcpProxyFlow, value: String): String =
        ABSOLUTE_HTTP_URL_PATTERN.replace(value) { match ->
            rewriteAbsoluteUrl(
                flow = flow,
                rawUrl = match.value,
                hideRedirect = bodyUrlHidesRedirect(value, match.range.first),
            ) ?: match.value
        }

    private fun rewriteAbsoluteUrls(
        flow: TcpProxyFlow,
        value: String,
        hideRedirect: Boolean,
    ): String =
        ABSOLUTE_HTTP_URL_PATTERN.replace(value) { match ->
            rewriteAbsoluteUrl(flow, match.value, hideRedirect) ?: match.value
        }

    private fun rewriteAbsoluteUrl(
        flow: TcpProxyFlow,
        rawUrl: String,
        hideRedirect: Boolean,
    ): String? {
        val target = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        if (target.host == null) return null
        return when {
            target.scheme.equals("https", ignoreCase = true) ->
                originRoutes.rememberHttpsReference(flow, target, hideRedirect)
            target.scheme.equals("http", ignoreCase = true) ->
                originRoutes.rememberHttpReference(flow, target, hideRedirect)
            else -> null
        }
    }

    private fun bodyReferenceRelation(source: String, urlStart: Int): ResourceRelation {
        val tagStart = source.lastIndexOf('<', startIndex = urlStart)
        val tagEnd = source.indexOf('>', startIndex = urlStart).takeIf { it >= 0 } ?: return ResourceRelation.OTHER_SUBRESOURCE
        if (tagStart < 0) return ResourceRelation.OTHER_SUBRESOURCE
        val tag = source.substring(tagStart, tagEnd + 1)
        val prefix = source.substring(tagStart, urlStart).lowercase(Locale.ROOT)
        return when {
            Regex("""<\s*base\b[^>]*\bhref\s*=\s*["']?[^"']*$""").containsMatchIn(prefix) -> ResourceRelation.BASE_HREF
            Regex("""<\s*a\b[^>]*\bhref\s*=\s*["']?[^"']*$""").containsMatchIn(prefix) -> ResourceRelation.A_HREF
            Regex("""<\s*area\b[^>]*\bhref\s*=\s*["']?[^"']*$""").containsMatchIn(prefix) -> ResourceRelation.AREA_HREF
            Regex("""<\s*form\b[^>]*\baction\s*=\s*["']?[^"']*$""").containsMatchIn(prefix) -> ResourceRelation.FORM_ACTION
            Regex("""<\s*(?:iframe|frame)\b[^>]*\bsrc\s*=\s*["']?[^"']*$""").containsMatchIn(prefix) -> ResourceRelation.FRAME_SRC
            Regex("""<\s*img\b[^>]*\bsrc\s*=\s*["']?[^"']*$""").containsMatchIn(prefix) -> ResourceRelation.IMG_SRC
            Regex("""<\s*script\b[^>]*\bsrc\s*=\s*["']?[^"']*$""").containsMatchIn(prefix) -> ResourceRelation.SCRIPT_SRC
            Regex("""<\s*link\b[^>]*\bhref\s*=\s*["']?[^"']*$""").containsMatchIn(prefix) &&
                tag.contains(Regex("""(?i)\brel\s*=\s*["']?stylesheet\b""")) -> ResourceRelation.LINK_STYLESHEET
            isMetaRefreshTag(tag) -> ResourceRelation.META_REFRESH
            else -> ResourceRelation.OTHER_SUBRESOURCE
        }
    }

    private fun resourceKindForRelation(relation: ResourceRelation): ResourceKind =
        when (relation) {
            ResourceRelation.ROOT,
            ResourceRelation.A_HREF,
            ResourceRelation.AREA_HREF,
            ResourceRelation.META_REFRESH -> ResourceKind.DOCUMENT
            ResourceRelation.BASE_HREF -> ResourceKind.OTHER
            ResourceRelation.FORM_ACTION -> ResourceKind.FORM
            ResourceRelation.LINK_STYLESHEET,
            ResourceRelation.CSS_IMPORT -> ResourceKind.STYLESHEET
            ResourceRelation.IMG_SRC -> ResourceKind.IMAGE
            ResourceRelation.SCRIPT_SRC -> ResourceKind.SCRIPT
            ResourceRelation.FRAME_SRC -> ResourceKind.FRAME
            ResourceRelation.CSS_URL,
            ResourceRelation.OTHER_SUBRESOURCE -> ResourceKind.OTHER
        }

    private fun bodyUrlHidesRedirect(source: String, urlStart: Int): Boolean {
        val tagStart = source.lastIndexOf('<', startIndex = urlStart)
        val tagEnd = source.indexOf('>', startIndex = urlStart).takeIf { it >= 0 } ?: return true
        if (tagStart < 0) return true
        val tag = source.substring(tagStart, tagEnd + 1)
        val prefix = source.substring(tagStart, urlStart).lowercase(Locale.ROOT)

        return when {
            Regex("""<\s*a\b[^>]*\bhref\s*=\s*["']?[^"']*$""").containsMatchIn(prefix) -> false
            Regex("""<\s*area\b[^>]*\bhref\s*=\s*["']?[^"']*$""").containsMatchIn(prefix) -> false
            Regex("""<\s*form\b[^>]*\baction\s*=\s*["']?[^"']*$""").containsMatchIn(prefix) -> false
            isMetaRefreshTag(tag) -> false
            else -> true
        }
    }

    private fun isMetaRefreshTag(tag: String): Boolean {
        if (!Regex("""(?i)^<\s*meta\b""").containsMatchIn(tag)) return false
        val httpEquiv = HTML_ATTRIBUTE_PATTERN.findAll(tag)
            .firstOrNull { it.groupValues[1].equals("http-equiv", ignoreCase = true) }
            ?.let { match -> match.groupValues.drop(2).firstOrNull { it.isNotEmpty() } }
        return httpEquiv.equals("refresh", ignoreCase = true)
    }

    private fun Map<String, List<String>>.withHeader(name: String, value: String): Map<String, List<String>> =
        buildMap {
            this@withHeader.forEach { (existingName, values) ->
                if (!existingName.equals(name, ignoreCase = true)) put(existingName, values)
            }
            put(name, listOf(value))
        }

    private fun emitErrorUnlessStarted(
        state: FlowState,
        status: Int,
        reason: String,
        message: String,
    ) {
        if (state.responseStarted) {
            logger("HTTP compatibility .. response already started; closing partial response")
            finishFlow(state)
        } else {
            emitErrorResponse(state, status, reason, message)
        }
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

    private fun sessionKey(flow: TcpProxyFlow): SessionKey =
        SessionKey(flow.generation, flow.key.peerAddress.toString())

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
                state.session.cookieOverrides.any { replacement ->
                    replacement.name.equals(client.name, true) && cookieOverrideApplies(replacement, uri)
                }
            }
            if (remaining.isNotEmpty()) result += remaining.joinToString("; ") { "${it.name}=${it.value}" }
        }
        result += runCatching { state.session.cookieManager.get(uri, emptyMap())["Cookie"].orEmpty() }.getOrDefault(emptyList())
        return result
    }

    private fun storeCookies(state: FlowState, uri: URI, headers: Map<String, List<String>>) {
        runCatching { state.session.cookieManager.put(uri, headers) }
        headers.entries
            .filter { (name, _) -> name.equals("Set-Cookie", true) || name.equals("Set-Cookie2", true) }
            .flatMap { it.value }
            .mapNotNull { parseCookieOverride(uri, it) }
            .forEach(state.session.cookieOverrides::put)
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
    private fun canForwardSensitiveHeaders(legacyUri: URI, upstreamUri: URI): Boolean =
        sameOrigin(legacyUri, upstreamUri) ||
            (legacyUri.scheme.equals("http", true) && upstreamUri.scheme.equals("https", true) &&
                legacyUri.host.equals(upstreamUri.host, true))
    private fun effectivePort(uri: URI) = when { uri.port >= 0 -> uri.port; uri.scheme.equals("https", true) -> 443; else -> 80 }

    private fun readBounded(state: FlowState, input: InputStream, limit: Int): ByteArray {
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
    internal fun resourceGraphSnapshots(flow: TcpProxyFlow): List<NavigationResourceGraphSnapshot> =
        sessionStates[sessionKey(flow)]?.resources?.snapshots().orEmpty()

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
        sessionStates.keys.removeIf { it.generation < generation }
        flows.values.filter { it.flow.generation < generation }.forEach(::removeFlow)
    }
    override fun close() {
        if (closed) return
        closed = true
        originRoutes.clear()
        sessionStates.clear()
        resourceCache.clear()
        inFlightResourceWork.clear()
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
        val CSS_URL_REFERENCE_PATTERN =
            Regex("""(?i)url\(\s*(?:"([^"]*)"|'([^']*)'|([^\s"'\)]+))\s*\)""")
        val CSS_IMPORT_REFERENCE_PATTERN =
            Regex("""(?i)@import\s+(?:"([^"]*)"|'([^']*)')""")
        val CSS_IMPORT_URL_REFERENCE_PATTERN =
            Regex("""(?i)@import\s+url\(\s*(?:"([^"]*)"|'([^']*)'|([^\s"'\)]+))\s*\)""")
        val HTML_URL_ATTRIBUTE_PATTERN =
            Regex("""(?i)\b(?:src|href|action|background|poster)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>]+))""")
        val HTML_ATTRIBUTE_PATTERN =
            Regex("""(?i)\b([a-z_:][-a-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>]+))""")
        val HTML_BASE_TAG_PATTERN =
            Regex("""(?is)<\s*base\b[^>]*>""")
        val HTML_META_TAG_PATTERN =
            Regex("""(?is)<\s*meta\b[^>]*>""")
        val META_REFRESH_URL_PATTERN =
            Regex("""(?i)(\burl\s*=\s*)(?:"([^"]*)"|'([^']*)'|([^;\s]+))""")
        val HTML_STYLE_BLOCK_PATTERN =
            Regex("""(?is)<\s*style\b[^>]*>(.*?)</\s*style\s*>""")
        val HTML_STYLE_ATTRIBUTE_PATTERN =
            Regex("""(?is)\bstyle\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        val NAVIGATION_CONTENT_TYPES = setOf("text/html", "application/xhtml+xml")
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
