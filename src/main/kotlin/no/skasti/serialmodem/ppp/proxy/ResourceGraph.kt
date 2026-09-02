package no.skasti.serialmodem.ppp.proxy

import java.net.URI
import java.util.concurrent.atomic.AtomicLong

internal enum class ResourceKind {
    DOCUMENT,
    STYLESHEET,
    IMAGE,
    SCRIPT,
    FRAME,
    FORM,
    OTHER,
}

internal enum class ResourceRelation(val role: ReferenceRole) {
    ROOT(ReferenceRole.NAVIGATION),
    A_HREF(ReferenceRole.NAVIGATION),
    AREA_HREF(ReferenceRole.NAVIGATION),
    FORM_ACTION(ReferenceRole.NAVIGATION),
    META_REFRESH(ReferenceRole.NAVIGATION),
    BASE_HREF(ReferenceRole.SUBRESOURCE),
    LINK_STYLESHEET(ReferenceRole.SUBRESOURCE),
    IMG_SRC(ReferenceRole.SUBRESOURCE),
    SCRIPT_SRC(ReferenceRole.SUBRESOURCE),
    FRAME_SRC(ReferenceRole.SUBRESOURCE),
    CSS_URL(ReferenceRole.SUBRESOURCE),
    CSS_IMPORT(ReferenceRole.SUBRESOURCE),
    OTHER_SUBRESOURCE(ReferenceRole.SUBRESOURCE),
}

internal enum class ResourceState {
    DISCOVERED,
    FETCHING,
    READY,
    FAILED,
}

internal data class ResourceNodeSnapshot(
    val legacyUri: URI,
    val upstreamUri: URI?,
    val role: ReferenceRole,
    val kind: ResourceKind,
    val contentBase: URI?,
    val state: ResourceState,
)

internal data class ResourceEdgeSnapshot(
    val parentLegacyUri: URI,
    val childLegacyUri: URI,
    val relation: ResourceRelation,
)

internal data class NavigationResourceGraphSnapshot(
    val id: Long,
    val rootLegacyUri: URI,
    val nodes: List<ResourceNodeSnapshot>,
    val edges: List<ResourceEdgeSnapshot>,
)

internal data class ResourceRegistryRoot(
    val graphId: Long,
    val rootLegacyUri: URI,
)

internal data class ResourceRegistryResource(
    val graphId: Long,
    val rootLegacyUri: URI,
    val resource: ResourceNodeSnapshot,
)

internal class ResourceRegistryEvent<T> {
    private val handlers = linkedSetOf<(T) -> Unit>()

    @Synchronized
    operator fun plusAssign(handler: (T) -> Unit) {
        handlers += handler
    }

    @Synchronized
    operator fun minusAssign(handler: (T) -> Unit) {
        handlers -= handler
    }

    fun fire(value: T) {
        val snapshot = synchronized(this) { handlers.toList() }
        snapshot.forEach { handler -> runCatching { handler(value) } }
    }
}

internal class ResourceRegistryHooks {
    val onRootAdded = ResourceRegistryEvent<ResourceRegistryRoot>()
    val onRootRemoved = ResourceRegistryEvent<ResourceRegistryRoot>()
    val onRootUsed = ResourceRegistryEvent<ResourceRegistryRoot>()
    val onResourceAdded = ResourceRegistryEvent<ResourceRegistryResource>()
    val onResourceRemoved = ResourceRegistryEvent<ResourceRegistryResource>()
}

internal class NavigationResourceGraph(
    val id: Long,
    val rootLegacyUri: URI,
    private val maxNodes: Int,
    private val maxEdges: Int,
) {
    init {
        require(maxNodes > 0) { "Resource graph node limit must be positive" }
        require(maxEdges > 0) { "Resource graph edge limit must be positive" }
    }
    private data class MutableNode(
        val legacyUri: URI,
        var upstreamUri: URI?,
        var role: ReferenceRole,
        var kind: ResourceKind,
        var contentBase: URI?,
        var state: ResourceState,
    )

    private val nodes = linkedMapOf<String, MutableNode>()
    private val edges = linkedSetOf<ResourceEdgeSnapshot>()

    init {
        upsertNode(
            legacyUri = rootLegacyUri,
            upstreamUri = null,
            role = ReferenceRole.NAVIGATION,
            kind = ResourceKind.DOCUMENT,
            contentBase = null,
            state = ResourceState.DISCOVERED,
        )
    }

    @Synchronized
    fun discover(
        parentLegacyUri: URI,
        childLegacyUri: URI,
        upstreamUri: URI,
        relation: ResourceRelation,
        kind: ResourceKind,
    ): Boolean {
        val key = key(childLegacyUri)
        if (key !in nodes && nodes.size >= maxNodes) return false
        upsertNode(
            legacyUri = childLegacyUri,
            upstreamUri = upstreamUri,
            role = relation.role,
            kind = kind,
            contentBase = null,
            state = ResourceState.DISCOVERED,
        )
        val edge = ResourceEdgeSnapshot(parentLegacyUri, childLegacyUri, relation)
        if (edge in edges || edges.size < maxEdges) edges += edge
        return true
    }

    @Synchronized
    fun markFetched(
        legacyUri: URI,
        upstreamUri: URI,
        contentBase: URI?,
        state: ResourceState,
    ) {
        val key = key(legacyUri)
        val existing = nodes[key]
        if (existing != null) {
            existing.upstreamUri = upstreamUri
            existing.contentBase = contentBase
            existing.state = state
        } else {
            upsertNode(
                legacyUri = legacyUri,
                upstreamUri = upstreamUri,
                role = ReferenceRole.SUBRESOURCE,
                kind = ResourceKind.OTHER,
                contentBase = contentBase,
                state = state,
            )
        }
    }

    @Synchronized
    fun snapshot(): NavigationResourceGraphSnapshot =
        NavigationResourceGraphSnapshot(
            id = id,
            rootLegacyUri = rootLegacyUri,
            nodes = nodes.values.map {
                ResourceNodeSnapshot(
                    legacyUri = it.legacyUri,
                    upstreamUri = it.upstreamUri,
                    role = it.role,
                    kind = it.kind,
                    contentBase = it.contentBase,
                    state = it.state,
                )
            },
            edges = edges.toList(),
        )

    private fun upsertNode(
        legacyUri: URI,
        upstreamUri: URI?,
        role: ReferenceRole,
        kind: ResourceKind,
        contentBase: URI?,
        state: ResourceState,
    ) {
        val key = key(legacyUri)
        val previous = nodes[key]
        if (previous == null) {
            nodes[key] = MutableNode(legacyUri, upstreamUri, role, kind, contentBase, state)
        } else {
            if (role == ReferenceRole.NAVIGATION) {
                previous.role = ReferenceRole.NAVIGATION
                previous.kind = kind
            } else if (previous.role != ReferenceRole.NAVIGATION && previous.kind == ResourceKind.OTHER) {
                previous.kind = kind
            }
            if (upstreamUri != null) previous.upstreamUri = upstreamUri
            if (contentBase != null) previous.contentBase = contentBase
            if (state.ordinal > previous.state.ordinal) previous.state = state
        }
    }

    private fun key(uri: URI): String = LegacyHttpUrl.requestObservableKey(uri)
}

internal class NavigationResourceRegistry(
    private val maxContexts: Int = 64,
    private val maxNodesPerContext: Int = 1_024,
    private val maxEdgesPerContext: Int = 2_048,
    val hooks: ResourceRegistryHooks = ResourceRegistryHooks(),
) {
    init {
        require(maxContexts > 0) { "Navigation context limit must be positive" }
        require(maxNodesPerContext > 0) { "Resource graph node limit must be positive" }
        require(maxEdgesPerContext > 0) { "Resource graph edge limit must be positive" }
    }

    private val nextId = AtomicLong(1)
    private val graphs = linkedMapOf<Long, NavigationResourceGraph>()
    private val contextByLegacyUri = mutableMapOf<String, LinkedHashSet<Long>>()

    @Synchronized
    fun startNavigation(rootLegacyUri: URI): NavigationResourceGraph {
        while (graphs.size >= maxContexts) evictOldest()
        val graph = NavigationResourceGraph(
            id = nextId.getAndIncrement(),
            rootLegacyUri = rootLegacyUri,
            maxNodes = maxNodesPerContext,
            maxEdges = maxEdgesPerContext,
        )
        graphs[graph.id] = graph
        associate(rootLegacyUri, graph.id)
        val root = ResourceRegistryRoot(graph.id, rootLegacyUri)
        hooks.onRootAdded.fire(root)
        hooks.onRootUsed.fire(root)
        return graph
    }

    @Synchronized
    fun contextsFor(legacyUri: URI): List<NavigationResourceGraph> =
        contextByLegacyUri[LegacyHttpUrl.requestObservableKey(legacyUri)]
            ?.mapNotNull(graphs::get)
            .orEmpty()

    @Synchronized
    fun discover(
        graph: NavigationResourceGraph,
        parentLegacyUri: URI,
        childLegacyUri: URI,
        upstreamUri: URI,
        relation: ResourceRelation,
        kind: ResourceKind,
    ) {
        val before = graph.snapshot().nodes.associateBy { LegacyHttpUrl.requestObservableKey(it.legacyUri) }
        if (graph.discover(parentLegacyUri, childLegacyUri, upstreamUri, relation, kind)) {
            associate(childLegacyUri, graph.id)
            hooks.onRootUsed.fire(ResourceRegistryRoot(graph.id, graph.rootLegacyUri))
            val key = LegacyHttpUrl.requestObservableKey(childLegacyUri)
            if (key !in before) {
                graph.snapshot().nodes.firstOrNull { LegacyHttpUrl.requestObservableKey(it.legacyUri) == key }?.let { resource ->
                    hooks.onResourceAdded.fire(
                        ResourceRegistryResource(graph.id, graph.rootLegacyUri, resource),
                    )
                }
            }
        }
    }

    @Synchronized
    fun snapshots(): List<NavigationResourceGraphSnapshot> = graphs.values.map { it.snapshot() }

    private fun evictOldest() {
        val oldestId = graphs.keys.firstOrNull() ?: return
        val removed = graphs.remove(oldestId) ?: return
        val removedSnapshot = removed.snapshot()
        removedSnapshot.nodes.forEach { resource ->
            hooks.onResourceRemoved.fire(
                ResourceRegistryResource(removedSnapshot.id, removedSnapshot.rootLegacyUri, resource),
            )
        }
        hooks.onRootRemoved.fire(ResourceRegistryRoot(removedSnapshot.id, removedSnapshot.rootLegacyUri))
        val iterator = contextByLegacyUri.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.remove(oldestId)
            if (entry.value.isEmpty()) iterator.remove()
        }
    }

    private fun associate(uri: URI, graphId: Long) {
        contextByLegacyUri
            .getOrPut(LegacyHttpUrl.requestObservableKey(uri)) { linkedSetOf() }
            .add(graphId)
    }
}
