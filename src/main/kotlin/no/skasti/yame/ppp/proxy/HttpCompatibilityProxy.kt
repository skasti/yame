package no.skasti.yame.ppp.proxy

import no.skasti.yame.observer.YameEvent
import no.skasti.yame.ppp.http.HttpTcpProxy
import no.skasti.yame.ppp.proxy.transform.ResourceTransformationPipeline
import no.skasti.yame.ppp.proxy.transform.defaultResourceTransformationPipeline
import no.skasti.yame.ppp.tcp.TcpProxy
import no.skasti.yame.ppp.tcp.TcpProxyEvent
import no.skasti.yame.ppp.tcp.TcpProxyFlow
import java.net.http.HttpClient
import java.time.Duration

internal class SystemHttpCompatibilityProxy(
    config: PppHttpCompatibilityConfig = PppHttpCompatibilityConfig.fromConfigStore(),
    logger: (String) -> Unit = {},
    diagnosticSink: (YameEvent) -> Unit = {},
    transformerSettings: (() -> no.skasti.yame.ppp.proxy.transform.ResourceTransformationSettings)? = null,
    resourceGraphs: NavigationResourceRegistry =
        NavigationResourceRegistry(
            maxContexts = config.maxResourceContexts,
            maxNodesPerHost = config.maxResourceNodesPerContext,
            maxEdgesPerContext = config.maxResourceEdgesPerContext,
        ),
    httpClient: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.requestTimeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
    resourceTransformations: ResourceTransformationPipeline =
        defaultResourceTransformationPipeline(config.maxResponseBytes, config.elementSimplifier, logger),
) : TcpProxy {
    private val handler =
        SystemHttpCompatibilityHandler(
            config = config,
            logger = logger,
            diagnosticSink = diagnosticSink,
            transformerSettings = transformerSettings,
            resourceGraphs = resourceGraphs,
            httpClient = httpClient,
            resourceTransformations = resourceTransformations,
        )

    private val http =
        HttpTcpProxy(
            handler = handler,
            maxFlows = config.maxFlows,
            maxRequestBytes = config.maxRequestBytes,
            responseChunkBytes = RESPONSE_CHUNK_BYTES,
        )

    override fun connect(
        flow: TcpProxyFlow,
        onEvent: (TcpProxyEvent) -> Unit,
    ) = http.connect(flow, onEvent)

    override fun send(
        flow: TcpProxyFlow,
        payload: ByteArray,
    ): Result<Unit> = http.send(flow, payload)

    override fun shutdownOutput(flow: TcpProxyFlow): Result<Unit> =
        http.shutdownOutput(flow)

    override fun availableWriteCapacity(flow: TcpProxyFlow): Int =
        http.availableWriteCapacity(flow)

    override fun pauseReads(flow: TcpProxyFlow) =
        http.pauseReads(flow)

    override fun resumeReads(flow: TcpProxyFlow) =
        http.resumeReads(flow)

    override fun closeFlow(flow: TcpProxyFlow) =
        http.closeFlow(flow)

    override fun invalidateBefore(generation: Long) =
        http.invalidateBefore(generation)

    internal fun resourceGraphSnapshots(): List<NavigationResourceGraphSnapshot> =
        handler.resourceGraphSnapshots()

    override fun close() = http.close()
    override fun awaitQuiescence(timeout: Duration): Boolean = http.awaitQuiescence(timeout)

    private companion object {
        const val RESPONSE_CHUNK_BYTES = 4 * 1024
    }
}
