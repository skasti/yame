package no.skasti.yame.ppp.proxy

import no.skasti.yame.observer.YameEvent
import no.skasti.yame.observer.YameEvents
import no.skasti.yame.ppp.tcp.SystemTcpProxy
import no.skasti.yame.ppp.tcp.TcpProxy
import no.skasti.yame.ppp.tcp.TcpProxyEvent
import no.skasti.yame.ppp.tcp.TcpProxyFlow
import java.util.concurrent.ConcurrentHashMap

internal class SystemRoutingTcpProxy(
    private val httpConfig: PppHttpCompatibilityConfig = PppHttpCompatibilityConfig(),
    subscribeToHttpConfiguration: Boolean = false,
    private val logger: (String) -> Unit = {},
    private val diagnosticSink: (YameEvent) -> Unit = {},
    private val transformerSettings: (() -> no.skasti.yame.ppp.proxy.transform.HtmlTransformerSettings)? = null,
    private val resourceGraphs: NavigationResourceRegistry =
        NavigationResourceRegistry(
            maxContexts = httpConfig.maxResourceContexts,
            maxNodesPerHost = httpConfig.maxResourceNodesPerContext,
            maxEdgesPerContext = httpConfig.maxResourceEdgesPerContext,
        ),
    private val directProxy: TcpProxy = SystemTcpProxy(),
    private val httpProxy: TcpProxy = SystemHttpCompatibilityProxy(
        httpConfig,
        subscribeToConfiguration = subscribeToHttpConfiguration,
        logger = logger,
        diagnosticSink = diagnosticSink,
        transformerSettings = transformerSettings,
        resourceGraphs = resourceGraphs,
    ),
) : TcpProxy {
    private val routes = ConcurrentHashMap<TcpProxyFlow, TcpProxy>()

    override fun connect(flow: TcpProxyFlow, onEvent: (TcpProxyEvent) -> Unit) {
        val proxy = if (httpConfig.enabled && flow.key.remotePort == HTTP_PORT) {
            logger("HTTP compatibility <= ${flow.key.peerAddress}:${flow.key.peerPort} -> ${flow.key.remoteAddress}:${flow.key.remotePort}")
            emitEvent(
                HttpProxyAction(
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
    override fun awaitQuiescence(timeout: java.time.Duration): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        return directProxy.awaitQuiescence(timeout) &&
            httpProxy.awaitQuiescence(java.time.Duration.ofNanos((deadline - System.nanoTime()).coerceAtLeast(0)))
    }
    private fun route(flow: TcpProxyFlow): TcpProxy? = routes[flow]
    private fun emitEvent(event: YameEvent) { runCatching { diagnosticSink(event) }.onFailure {
            System.err.println("YAME independent event diagnostic failed: ${it.javaClass.simpleName}")
        }
        YameEvents.publish(event) }
    private fun flowId(flow: TcpProxyFlow): String =
        "${flow.key.peerAddress}:${flow.key.peerPort}->${flow.key.remoteAddress}:${flow.key.remotePort}"
    private companion object { const val HTTP_PORT = 80 }
}
