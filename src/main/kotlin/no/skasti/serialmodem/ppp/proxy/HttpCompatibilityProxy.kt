package no.skasti.serialmodem.ppp.proxy

import no.skasti.serialmodem.observer.YameEvent
import no.skasti.serialmodem.ppp.http.HttpTcpProxy
import no.skasti.serialmodem.ppp.tcp.TcpProxy
import no.skasti.serialmodem.ppp.tcp.TcpProxyEvent
import no.skasti.serialmodem.ppp.tcp.TcpProxyFlow
import java.net.http.HttpClient
import java.time.Duration

internal class SystemHttpCompatibilityProxy(
    config: PppHttpCompatibilityConfig = PppHttpCompatibilityConfig(enabled = true),
    logger: (String) -> Unit = {},
    eventSink: (YameEvent) -> Unit = {},
    resourceRegistryHooks: ResourceRegistryHooks = ResourceRegistryHooks(),
    httpClient: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.requestTimeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
) : TcpProxy {
    private val handler =
        SystemHttpCompatibilityHandler(
            config = config,
            logger = logger,
            eventSink = eventSink,
            resourceRegistryHooks = resourceRegistryHooks,
            httpClient = httpClient,
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

    override fun close() =
        http.close()

    private companion object {
        const val RESPONSE_CHUNK_BYTES = 4 * 1024
    }
}
