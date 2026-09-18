package no.skasti.yame.ppp.proxy

import no.skasti.yame.observer.HttpProxyActionKind
import no.skasti.yame.observer.YameEvent
import no.skasti.yame.ppp.http.HttpConnection
import no.skasti.yame.ppp.http.HttpRequest as LegacyHttpRequest
import no.skasti.yame.ppp.http.HttpRequestHandler
import no.skasti.yame.ppp.http.HttpResponse as LegacyHttpResponse
import no.skasti.yame.ppp.proxy.cookies.BoundedCookieOverrides
import no.skasti.yame.ppp.proxy.cookies.BoundedCookieStore
import no.skasti.yame.ppp.proxy.cookies.CookieOverride
import no.skasti.yame.ppp.proxy.routing.LegacyOriginRouteTable
import no.skasti.yame.ppp.proxy.transform.ImageResourceTransformer
import no.skasti.yame.ppp.proxy.transform.ImageTagTransformer
import no.skasti.yame.ppp.proxy.transform.ImageOptimizationPolicy
import no.skasti.yame.ppp.proxy.transform.LegacyTextResourceTransformer
import no.skasti.yame.ppp.proxy.transform.InFlightResourceWork
import no.skasti.yame.ppp.proxy.transform.CachedResource
import no.skasti.yame.ppp.proxy.transform.Resource
import no.skasti.yame.ppp.proxy.transform.ResourceCache
import no.skasti.yame.ppp.proxy.transform.ResourceCacheKey
import no.skasti.yame.ppp.proxy.transform.ResourceWorkKey
import no.skasti.yame.ppp.proxy.transform.ResourceFetchKey
import no.skasti.yame.ppp.proxy.transform.InFlightResourceFetchWork
import no.skasti.yame.ppp.proxy.transform.resourceRequestFingerprint
import no.skasti.yame.ppp.proxy.transform.ResourceRepresentation
import no.skasti.yame.ppp.proxy.transform.cachePolicyFrom
import no.skasti.yame.ppp.proxy.transform.validatorsFrom
import no.skasti.yame.ppp.proxy.transform.sourceFingerprint
import no.skasti.yame.ppp.proxy.transform.ResourceTransformationContext
import no.skasti.yame.ppp.proxy.transform.ResourceTransformationPipeline
import no.skasti.yame.ppp.tcp.TcpProxyFlow
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

internal class SystemHttpCompatibilityHandler(
    private val config: PppHttpCompatibilityConfig = PppHttpCompatibilityConfig(enabled = true),
    private val logger: (String) -> Unit = {},
    private val eventSink: (YameEvent) -> Unit = {},
    private val resourceRegistryHooks: ResourceRegistryHooks = ResourceRegistryHooks(),
    private val resourceGraphs: NavigationResourceRegistry =
        NavigationResourceRegistry(
            maxContexts = config.maxResourceContexts,
            maxNodesPerHost = config.maxResourceNodesPerContext,
            maxEdgesPerContext = config.maxResourceEdgesPerContext,
            hooks = resourceRegistryHooks,
        ),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.requestTimeoutMillis))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : HttpRequestHandler {
    private data class ClientCookie(val name: String, val value: String)
    private data class SessionKey(val generation: Long, val peerAddress: String)
    private data class SessionState(
        val cookieManager: CookieManager = CookieManager(BoundedCookieStore(), CookiePolicy.ACCEPT_ORIGINAL_SERVER),
        val cookieOverrides: BoundedCookieOverrides = BoundedCookieOverrides(),
    )
    private data class FlowState(
        val flow: TcpProxyFlow,
        val session: SessionState,
        val active: () -> Boolean,
        val clientCookies: MutableList<ClientCookie> = mutableListOf(),
    )

    // Compatibility responses are deliberately fully acquired before they cross the
    // slow legacy boundary. Keep the body as owned bytes here: introducing a streaming
    // body would couple upstream HTTP progress to TCP/PPP/serial backpressure again.
    private data class FinalResponse(
        val legacyUri: URI,
        val uri: URI,
        val statusCode: Int,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
        val contentLength: Long?,
        val exposeCookies: Boolean,
        val locationAlreadyLegacy: Boolean = false,
        val effectiveBaseUri: URI? = null,
        val referenceRole: ReferenceRole = ReferenceRole.NAVIGATION,
        val establishesNavigationGraph: Boolean = false,
        val requestHeaders: Map<String, List<String>> = emptyMap(),
        val requestMethod: String = "GET",
    )

    private val sessionStates = ConcurrentHashMap<SessionKey, SessionState>()
    private val originRoutes = LegacyOriginRouteTable()
    private val imageOptimizationPolicy = ImageOptimizationPolicy()
    private val resourceTransformations =
        ResourceTransformationPipeline(
            listOf(
                LegacyTextResourceTransformer(),
                ImageTagTransformer(imageOptimizationPolicy),
                ImageResourceTransformer(imageOptimizationPolicy),
            ),
        )
    private val resourceCache = ResourceCache(
        maxEntries = config.maxRepresentationCacheEntries,
        maxBytes = config.maxRepresentationCacheBytes,
    )
    private val inFlightResourceWork = InFlightResourceWork()
    private val inFlightResourceFetchWork = InFlightResourceFetchWork<FinalResponse>()
    @Volatile private var closed = false

    override fun handle(
        connection: HttpConnection,
        request: LegacyHttpRequest,
    ): LegacyHttpResponse {
        if (closed || !connection.isActive) {
            throw CancellationException("HTTP compatibility connection closed")
        }
        val flow = connection.flow
        val state =
            FlowState(
                flow = flow,
                session =
                    sessionStates.computeIfAbsent(sessionKey(flow)) {
                        SessionState()
                    },
                active = { connection.isActive },
            )
        return processRequest(state, request)
    }

    private fun processRequest(
        state: FlowState,
        request: LegacyHttpRequest,
    ): LegacyHttpResponse {
        var fetchAttempts: Map<NavigationResourceGraph, ResourceFetchAttempt> = emptyMap()
        var fetchLegacyUri: URI? = null
        var fetchUpstreamUri: URI? = null
        try {
            ensureActive(state)
            val legacyUri = legacyUri(state.flow, request)
            val upstreamUri = originRoutes.resolve(state.flow, legacyUri)
            fetchLegacyUri = legacyUri
            fetchUpstreamUri = upstreamUri
            if (!request.method.equals("HEAD", ignoreCase = true)) {
                resourceGraphs.ensureResourceContext(legacyUri)
            }
            fetchAttempts =
                resourceGraphs.contextsFor(legacyUri)
                    .mapNotNull { graph ->
                        resourceGraphs.beginFetch(
                            graph = graph,
                            legacyUri = legacyUri,
                            upstreamUri = upstreamUri,
                            contentBase = upstreamUri,
                        )?.let { attempt -> graph to attempt }
                    }.toMap()
            seedClientCookies(state, request.headers)
            val response =
                if (request.method.equals("GET", ignoreCase = true)) {
                    val followedExactMapping =
                        originRoutes.isExactMapping(state.flow, legacyUri, upstreamUri)
                    val referenceRole =
                        if (followedExactMapping) {
                            originRoutes.referenceRole(state.flow, legacyUri)
                                ?: ReferenceRole.NAVIGATION
                        } else {
                            ReferenceRole.NAVIGATION
                        }
                    val usesTargetAsContentBase =
                        followedExactMapping &&
                            referenceRole == ReferenceRole.SUBRESOURCE &&
                            originRoutes.usesTargetAsContentBase(state.flow, legacyUri)
                    val forwardSensitiveHeaders =
                        canForwardSensitiveHeaders(legacyUri, upstreamUri)
                    val fetchKey =
                        ResourceFetchKey(
                            scope = resourceWorkScope(state.flow),
                            legacyUri = legacyUri,
                            upstreamUri = upstreamUri,
                            role = referenceRole,
                            usesTargetAsContentBase = usesTargetAsContentBase,
                            requestFingerprint =
                                resourceRequestFingerprint(
                                    method = request.method,
                                    headers = request.headers,
                                    body = request.body,
                                    effectiveCookieHeaders =
                                        cookieHeaders(
                                            state,
                                            upstreamUri,
                                            forwardSensitiveHeaders,
                                        ),
                                ),
                        )
                    inFlightResourceFetchWork.getOrStart(fetchKey) {
                        fetchFinalResponse(state, request)
                    }
                } else {
                    fetchFinalResponse(state, request)
                }
            ensureActive(state)
            return prepareFinalResponse(state, response, fetchAttempts)
        } catch (error: CancellationException) {
            markFetchFailed(fetchAttempts, fetchLegacyUri, fetchUpstreamUri)
            throw error
        } catch (error: InterruptedException) {
            markFetchFailed(fetchAttempts, fetchLegacyUri, fetchUpstreamUri)
            Thread.currentThread().interrupt()
            throw CancellationException("HTTP compatibility request interrupted")
        } catch (error: BadLegacyRequest) {
            markFetchFailed(fetchAttempts, fetchLegacyUri, fetchUpstreamUri)
            logger("HTTP compatibility !! bad request: ${error.message}")
            emitEvent(state, HttpProxyActionKind.ERROR, error.message ?: "Invalid HTTP request")
            return errorResponse(400, "Bad Request", error.message ?: "Invalid HTTP request")
        } catch (error: ResponseTooLarge) {
            markFetchFailed(fetchAttempts, fetchLegacyUri, fetchUpstreamUri)
            logger("HTTP compatibility !! ${error.message}")
            emitEvent(state, HttpProxyActionKind.ERROR, error.message ?: "Upstream response too large")
            return errorResponse(502, "Bad Gateway", error.message ?: "Upstream response too large")
        } catch (error: Throwable) {
            markFetchFailed(fetchAttempts, fetchLegacyUri, fetchUpstreamUri)
            logger(
                "HTTP compatibility !! upstream failed: " +
                    (error.message ?: error.javaClass.simpleName),
            )
            emitEvent(state, HttpProxyActionKind.ERROR, error.message ?: error.javaClass.simpleName)
            return errorResponse(502, "Bad Gateway", "YAME could not fetch the upstream resource")
        }
    }

    private fun markFetchFailed(
        attempts: Map<NavigationResourceGraph, ResourceFetchAttempt>,
        legacyUri: URI?,
        upstreamUri: URI?,
    ) {
        if (legacyUri == null || upstreamUri == null) return
        attempts.forEach { (graph, attempt) ->
            resourceGraphs.markFetchState(
                graph = graph,
                attempt = attempt,
                legacyUri = legacyUri,
                upstreamUri = upstreamUri,
                contentBase = upstreamUri,
                state = ResourceState.FAILED,
            )
        }
    }

    private fun fetchFinalResponse(state: FlowState, request: LegacyHttpRequest): FinalResponse {
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
                    val upstreamContentLength =
                        response.headers().firstValueAsLong("content-length").orElse(-1L)
                    val isHead = request.method.equals("HEAD", true)
                    val redirectBody =
                        if (isHead) {
                            response.body().close()
                            ByteArray(0)
                        } else {
                            if (upstreamContentLength > config.maxResponseBytes.toLong()) {
                                response.body().close()
                                throw ResponseTooLarge(
                                    "Upstream response is $upstreamContentLength bytes; limit is ${config.maxResponseBytes}",
                                )
                            }
                            response.body().use { input ->
                                readBounded(state, input, config.maxResponseBytes)
                            }
                        }
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
                        body = redirectBody,
                        contentLength =
                            if (isHead && upstreamContentLength >= 0L) {
                                upstreamContentLength
                            } else {
                                redirectBody.size.toLong()
                            },
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
                    ByteArray(0)
                } else {
                    if (upstreamContentLength > config.maxResponseBytes.toLong()) {
                        response.body().close()
                        throw ResponseTooLarge(
                            "Upstream response is $upstreamContentLength bytes; limit is ${config.maxResponseBytes}",
                        )
                    }
                    response.body().use { input ->
                        readBounded(state, input, config.maxResponseBytes)
                    }
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
                        else -> responseBody.size.toLong()
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

    private fun prepareFinalResponse(
        state: FlowState,
        response: FinalResponse,
        initialFetchAttempts: Map<NavigationResourceGraph, ResourceFetchAttempt>,
    ): LegacyHttpResponse {
        val graphs =
            if (response.establishesNavigationGraph) {
                resourceGraphs.startNavigation(response.legacyUri).also { graph ->
                    resourceGraphs.discover(
                        graph = graph,
                        parentLegacyUri = graph.rootLegacyUri,
                        childLegacyUri = response.legacyUri,
                        upstreamUri = response.uri,
                        relation = ResourceRelation.ROOT,
                        kind = ResourceKind.DOCUMENT,
                    )
                }.let(::listOf)
            } else {
                resourceGraphs.contextsFor(response.legacyUri)
            }
        val responseKind = resourceKindForResponse(response.headers, response.referenceRole)
        graphs.forEach { graph ->
            resourceGraphs.updateResourceKind(graph, response.legacyUri, responseKind)
        }
        val fetchAttempts =
            graphs.mapNotNull { graph ->
                val attempt =
                    initialFetchAttempts[graph]
                        ?: resourceGraphs.beginFetch(
                            graph = graph,
                            legacyUri = response.legacyUri,
                            upstreamUri = response.uri,
                            contentBase = response.effectiveBaseUri ?: response.uri,
                        )
                attempt?.let { graph to it }
            }.toMap()
        fetchAttempts.forEach { (graph, attempt) ->
            resourceGraphs.markFetchState(
                graph = graph,
                attempt = attempt,
                legacyUri = response.legacyUri,
                upstreamUri = response.uri,
                contentBase = response.effectiveBaseUri ?: response.uri,
                state = ResourceState.SOURCE_READY,
            )
        }
        var resourceReady = false
        try {
            fetchAttempts.forEach { (graph, attempt) ->
                resourceGraphs.markFetchState(
                    graph = graph,
                    attempt = attempt,
                    legacyUri = response.legacyUri,
                    upstreamUri = response.uri,
                    contentBase = response.effectiveBaseUri ?: response.uri,
                    state = ResourceState.TRANSFORMING,
                )
            }
            val preparedResource =
                prepareLegacyResource(
                    flow = state.flow,
                    session = state.session,
                    graphs = graphs,
                    parentLegacyUri = response.legacyUri,
                    upstreamUri = response.uri,
                    statusCode = response.statusCode,
                    requestHeaders = response.requestHeaders,
                    headers = response.headers,
                    body = response.body,
                    resolutionBaseUri = response.effectiveBaseUri ?: response.uri,
                    forceAbsoluteRelativeReferences = response.effectiveBaseUri != null,
                    referenceRole = response.referenceRole,
                    requestMethod = response.requestMethod,
                )
            resourceGraphs.recordTransformations(
                response.legacyUri,
                preparedResource.transformed?.transformations.orEmpty(),
            )
            val sourceRepresentation = preparedResource.source
            val clientRepresentation = preparedResource.representation
            val legacyHeaders =
                rewriteLegacyHeaders(
                    state.flow,
                    clientRepresentation.headers,
                    response.locationAlreadyLegacy,
                )
            val legacyBody = clientRepresentation.body
            val bodyRewritten = !legacyBody.contentEquals(sourceRepresentation.body)
            val rewritten = legacyHeaders != response.headers || bodyRewritten
        val contentLength =
            when {
                response.contentLength != null &&
                    response.contentLength != response.body.size.toLong() -> response.contentLength
                else -> legacyBody.size.toLong()
            }
        val nominated =
            connectionNominatedHeaders(
                legacyHeaders.flatMap { (name, values) -> values.map { name to it } },
            )
        val clientHeaders =
            buildList {
                legacyHeaders.forEach { (name, values) ->
                    val normalized = name.lowercase(Locale.ROOT)
                    val hiddenCrossOriginCookie =
                        !response.exposeCookies && normalized in RESPONSE_COOKIE_HEADERS
                    if (
                        normalized !in RESPONSE_HEADERS_TO_STRIP &&
                        normalized !in nominated &&
                        !hiddenCrossOriginCookie
                    ) {
                        values.forEach { value -> add(name to value) }
                    }
                }
                add("Content-Length" to contentLength.toString())
                add("Connection" to "close")
            }
        val clientResponse =
            LegacyHttpResponse(
                statusCode = response.statusCode,
                reasonPhrase = reasonPhrase(response.statusCode),
                headers = clientHeaders,
                body = legacyBody,
            )
        val rewriteSuffix = if (rewritten) ", HTTPS references rewritten for legacy client" else ""
        val sizeDescription = "$contentLength bytes"
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
        // READY means the complete client representation exists locally. The HTTP
        // transport layer may now deliver it at legacy-client speed without involving
        // upstream acquisition or transformation.
        fetchAttempts.forEach { (graph, attempt) ->
            resourceGraphs.markFetchState(
                graph = graph,
                attempt = attempt,
                legacyUri = response.legacyUri,
                upstreamUri = response.uri,
                contentBase = response.effectiveBaseUri ?: response.uri,
                state = ResourceState.READY,
            )
        }
        resourceReady = true
        return clientResponse
        } catch (error: Throwable) {
            if (!resourceReady) {
                fetchAttempts.forEach { (graph, attempt) ->
                    resourceGraphs.markFetchState(
                        graph = graph,
                        attempt = attempt,
                        legacyUri = response.legacyUri,
                        upstreamUri = response.uri,
                        contentBase = response.effectiveBaseUri ?: response.uri,
                        state = ResourceState.FAILED,
                    )
                }
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

    private fun prepareLegacyResource(
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
    ): Resource {
        val source =
            Resource(
                upstreamUri = upstreamUri,
                source = ResourceRepresentation(
                    statusCode = statusCode,
                    headers = headers,
                    body = body,
                ),
            )
        if (statusCode == 206) return source

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

        val cacheEligible = requestMethod.equals("GET", ignoreCase = true) && statusCode != 304
        if (!cacheEligible) {
            return resourceTransformations.transform(context, source).resource
        }

        val cacheKey =
            ResourceCacheKey(
                legacyUri = parentLegacyUri,
                upstreamUri = upstreamUri,
                profile = config.transformationProfile,
            )
        val fingerprint = sourceFingerprint(source.source)
        resourceCache.getIfSourceFingerprint(cacheKey, fingerprint)
            ?.let { return it.resource }

        val workKey =
            ResourceWorkKey(
                cacheKey = cacheKey,
                sourceFingerprint = fingerprint,
                scope = resourceWorkScope(flow),
            )
        val transformed =
            inFlightResourceWork.getOrStart(workKey) {
                resourceCache.getIfSourceFingerprint(cacheKey, fingerprint)
                    ?.let {
                        return@getOrStart no.skasti.yame.ppp.proxy.transform.ResourceTransformationState(it.resource)
                    }

                val produced = resourceTransformations.transform(context, source)
                if (produced.cacheable) {
                    resourceCache.put(
                        cacheKey,
                        CachedResource(
                            resource = produced.resource,
                            storedAt = Instant.now(),
                            cachePolicy = cachePolicyFrom(headers),
                            validators = validatorsFrom(headers),
                        ),
                        sourceFingerprint = fingerprint,
                    )
                }
                produced
            }

        return transformed.resource
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
                resourceGraphs.discover(
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
            resourceGraphs.discover(
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

    private fun errorResponse(
        status: Int,
        reason: String,
        message: String,
    ): LegacyHttpResponse {
        val body =
            "$status $reason\r\n$message\r\n"
                .toByteArray(StandardCharsets.US_ASCII)
        return LegacyHttpResponse(
            statusCode = status,
            reasonPhrase = reason,
            headers =
                listOf(
                    "Content-Type" to "text/plain; charset=us-ascii",
                    "Content-Length" to body.size.toString(),
                    "Connection" to "close",
                ),
            body = body,
        )
    }

    private fun legacyUri(flow: TcpProxyFlow, request: LegacyHttpRequest): URI {
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
    private fun isActive(state: FlowState) = !closed && state.active()

    private fun resourceWorkScope(flow: TcpProxyFlow): String =
        "${flow.generation}:${flow.key.peerAddress}"

    internal fun resourceGraphSnapshots(): List<NavigationResourceGraphSnapshot> =
        resourceGraphs.snapshots()

    override fun invalidateBefore(generation: Long) {
        originRoutes.invalidateBefore(generation)
        sessionStates.keys.removeIf { it.generation < generation }
        // Resource graphs deliberately survive PPP generations, TCP flows, and
        // browser restarts. They are process-lifetime HTTP proxy knowledge.
    }

    override fun close() {
        if (closed) return
        closed = true
        originRoutes.clear()
        sessionStates.clear()
        resourceCache.clear()
        inFlightResourceWork.clear()
        inFlightResourceFetchWork.clear()
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
    private class BadLegacyRequest(message: String) : IllegalArgumentException(message)
    private class ResponseTooLarge(message: String) : IllegalStateException(message)

    private companion object {
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
