package no.skasti.serialmodem.ppp.proxy.routing

import no.skasti.serialmodem.ppp.proxy.LegacyHttpUrl
import no.skasti.serialmodem.ppp.proxy.ReferenceRole
import no.skasti.serialmodem.ppp.tcp.TcpProxyFlow
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class LegacyOriginRouteTable(
    private val maxExactMappingsPerSession: Int = 4_096,
    private val maxOriginMappingsPerSession: Int = 256,
) {
    init {
        require(maxExactMappingsPerSession > 0) { "Exact mapping limit must be positive" }
        require(maxOriginMappingsPerSession > 0) { "Origin mapping limit must be positive" }
    }

    private data class RouteSessionKey(
        val generation: Long,
        val peerAddress: String,
    )

    private data class OriginKey(
        val generation: Long,
        val peerAddress: String,
        val legacyHost: String,
        val legacyPort: Int,
    )

    private data class ExactKey(
        val generation: Long,
        val peerAddress: String,
        val legacyUri: String,
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
                    append(LegacyHttpUrl.formatHost(host))
                    if (!isDefaultPort()) append(':').append(port)
                    append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                    uri.rawQuery?.let { append('?').append(it) }
                },
            )

        fun asDisplayString(): String =
            buildString {
                append(scheme)
                append("://")
                append(LegacyHttpUrl.formatHost(host))
                if (!isDefaultPort()) append(':').append(port)
            }

        private fun isDefaultPort(): Boolean =
            (scheme == "http" && port == 80) || (scheme == "https" && port == 443)

        companion object {
            fun from(uri: URI): Origin =
                Origin(
                    scheme = requireNotNull(uri.scheme).lowercase(Locale.ROOT),
                    host = normalizeHost(requireNotNull(uri.host)),
                    port = LegacyHttpUrl.effectivePort(uri),
                )

            private fun normalizeHost(host: String): String =
                host.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        }
    }

    private data class ExactMapping(
        val target: URI,
        val role: ReferenceRole,
        val useTargetAsContentBase: Boolean,
    )

    private val originMappings = ConcurrentHashMap<OriginKey, Origin>()
    private val exactMappings = ConcurrentHashMap<ExactKey, ExactMapping>()
    private val originInsertionOrder = mutableMapOf<RouteSessionKey, LinkedHashSet<OriginKey>>()
    private val exactInsertionOrder = mutableMapOf<RouteSessionKey, LinkedHashSet<ExactKey>>()
    private val insertionOrderLock = Any()

    fun resolve(flow: TcpProxyFlow, legacyUri: URI): URI =
        exactMappings[exactKey(flow, legacyUri)]?.target
            ?: originMappings[originKey(flow, legacyUri)]?.resolvePathFrom(legacyUri)
            ?: legacyUri

    fun isExactMapping(flow: TcpProxyFlow, legacyUri: URI, upstreamUri: URI): Boolean =
        exactMappings[exactKey(flow, legacyUri)]?.target == LegacyHttpUrl.withoutFragment(upstreamUri)

    fun referenceRole(flow: TcpProxyFlow, legacyUri: URI): ReferenceRole? =
        exactMappings[exactKey(flow, legacyUri)]?.role

    fun hidesRedirect(flow: TcpProxyFlow, legacyUri: URI): Boolean =
        referenceRole(flow, legacyUri) == ReferenceRole.SUBRESOURCE

    fun usesTargetAsContentBase(flow: TcpProxyFlow, legacyUri: URI): Boolean =
        exactMappings[exactKey(flow, legacyUri)]?.useTargetAsContentBase == true

    fun rememberExact(
        flow: TcpProxyFlow,
        legacyUri: URI,
        upstreamUri: URI,
        role: ReferenceRole = ReferenceRole.NAVIGATION,
        useTargetAsContentBase: Boolean = false,
    ) {
        rememberExactMapping(
            exactKey(flow, legacyUri),
            LegacyHttpUrl.withoutFragment(upstreamUri),
            role,
            useTargetAsContentBase,
        )
    }

    fun remember(flow: TcpProxyFlow, legacyUri: URI, upstreamUri: URI): Pair<String, String>? {
        val originKey = originKey(flow, legacyUri)
        val exactKey = exactKey(flow, legacyUri)
        val legacyOrigin = Origin.from(legacyUri)
        val upstreamOrigin = Origin.from(upstreamUri)
        val exactTarget = exactMappings[exactKey]?.target
        val previous = synchronized(insertionOrderLock) {
            when {
                legacyOrigin == upstreamOrigin && exactTarget != null -> originMappings[originKey]
                legacyOrigin == upstreamOrigin -> removeOriginMapping(originKey)
                else -> rememberOriginMapping(originKey, upstreamOrigin)
            }
        }

        return if (previous == upstreamOrigin || (previous == null && legacyOrigin == upstreamOrigin)) {
            null
        } else {
            legacyOrigin.asDisplayString() to upstreamOrigin.asDisplayString()
        }
    }

    fun rememberHttpsReference(
        flow: TcpProxyFlow,
        upstreamHttpsUri: URI,
        hideRedirect: Boolean = true,
    ): String {
        require(upstreamHttpsUri.scheme.equals("https", ignoreCase = true)) {
            "Compatibility reference target must be HTTPS"
        }
        val legacyUri = LegacyHttpUrl.mirrorOf(upstreamHttpsUri)
        rememberExactMapping(
            exactKey(flow, legacyUri),
            LegacyHttpUrl.withoutFragment(upstreamHttpsUri),
            if (hideRedirect) ReferenceRole.SUBRESOURCE else ReferenceRole.NAVIGATION,
            useTargetAsContentBase = false,
        )
        return legacyUri.toString()
    }

    fun rememberHttpReference(
        flow: TcpProxyFlow,
        upstreamHttpUri: URI,
        hideRedirect: Boolean = true,
    ): String {
        require(upstreamHttpUri.scheme.equals("http", ignoreCase = true)) {
            "Plain HTTP reference target must use HTTP"
        }
        rememberExactMapping(
            exactKey(flow, upstreamHttpUri),
            LegacyHttpUrl.withoutFragment(upstreamHttpUri),
            if (hideRedirect) ReferenceRole.SUBRESOURCE else ReferenceRole.NAVIGATION,
            useTargetAsContentBase = false,
        )
        return upstreamHttpUri.toString()
    }

    fun invalidateBefore(generation: Long) {
        synchronized(insertionOrderLock) {
            originMappings.keys.removeIf { it.generation < generation }
            exactMappings.keys.removeIf { it.generation < generation }
            originInsertionOrder.keys.removeIf { it.generation < generation }
            exactInsertionOrder.keys.removeIf { it.generation < generation }
        }
    }

    fun clear() {
        synchronized(insertionOrderLock) {
            originMappings.clear()
            exactMappings.clear()
            originInsertionOrder.clear()
            exactInsertionOrder.clear()
        }
    }

    private fun rememberExactMapping(
        key: ExactKey,
        target: URI,
        role: ReferenceRole,
        useTargetAsContentBase: Boolean,
    ) {
        synchronized(insertionOrderLock) {
            val previous = exactMappings[key]
            val effectiveRole =
                if (previous?.role == ReferenceRole.NAVIGATION || role == ReferenceRole.NAVIGATION) {
                    ReferenceRole.NAVIGATION
                } else {
                    ReferenceRole.SUBRESOURCE
                }
            exactMappings[key] = ExactMapping(
                target = target,
                role = effectiveRole,
                useTargetAsContentBase =
                    effectiveRole == ReferenceRole.SUBRESOURCE &&
                        ((previous?.useTargetAsContentBase ?: false) || useTargetAsContentBase),
            )
            val order = exactInsertionOrder.getOrPut(key.sessionKey()) { linkedSetOf() }
            order.remove(key)
            order.add(key)
            while (order.size > maxExactMappingsPerSession) {
                val oldest = order.first()
                order.remove(oldest)
                exactMappings.remove(oldest)
            }
        }
    }

    private fun rememberOriginMapping(key: OriginKey, target: Origin): Origin? {
        val previous = originMappings.put(key, target)
        val order = originInsertionOrder.getOrPut(key.sessionKey()) { linkedSetOf() }
        order.remove(key)
        order.add(key)
        while (order.size > maxOriginMappingsPerSession) {
            val oldest = order.first()
            order.remove(oldest)
            originMappings.remove(oldest)
        }
        return previous
    }

    private fun removeOriginMapping(key: OriginKey): Origin? {
        val removed = originMappings.remove(key)
        originInsertionOrder[key.sessionKey()]?.let { order ->
            order.remove(key)
            if (order.isEmpty()) originInsertionOrder.remove(key.sessionKey())
        }
        return removed
    }

    private fun OriginKey.sessionKey(): RouteSessionKey = RouteSessionKey(generation, peerAddress)

    private fun ExactKey.sessionKey(): RouteSessionKey = RouteSessionKey(generation, peerAddress)

    private fun originKey(flow: TcpProxyFlow, legacyUri: URI): OriginKey =
        OriginKey(
            generation = flow.generation,
            peerAddress = flow.key.peerAddress.toString(),
            legacyHost = requireNotNull(legacyUri.host).removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT),
            legacyPort = LegacyHttpUrl.effectivePort(legacyUri),
        )

    private fun exactKey(flow: TcpProxyFlow, legacyUri: URI): ExactKey =
        ExactKey(
            generation = flow.generation,
            peerAddress = flow.key.peerAddress.toString(),
            legacyUri = LegacyHttpUrl.requestObservableKey(legacyUri),
        )
}
