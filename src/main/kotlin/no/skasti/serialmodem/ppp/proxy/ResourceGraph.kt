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
    SOURCE_READY,
    TRANSFORMING,
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
    val resourceLegacyUri: URI,
)

internal class ResourceRegistryEvent<T>(
    private val dispatch: ((() -> Unit) -> Unit),
) {
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
        snapshot.forEach { handler ->
            runCatching {
                dispatch {
                    runCatching { handler(value) }
                }
            }
        }
    }
}

internal class ResourceRegistryHooks(
    private val dispatch: ((() -> Unit) -> Unit) = { task -> task() },
) {
    val onRootAdded = ResourceRegistryEvent<ResourceRegistryRoot>(dispatch)
    val onRootRemoved = ResourceRegistryEvent<ResourceRegistryRoot>(dispatch)
    val onRootUsed = ResourceRegistryEvent<ResourceRegistryRoot>(dispatch)
    val onResourceAdded = ResourceRegistryEvent<ResourceRegistryResource>(dispatch)
    val onResourceRemoved = ResourceRegistryEvent<ResourceRegistryResource>(dispatch)
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
    fun contains(legacyUri: URI): Boolean = key(legacyUri) in nodes

    @Synchronized
    fun nodeSnapshot(legacyUri: URI): ResourceNodeSnapshot? =
        nodes[key(legacyUri)]?.let {
            ResourceNodeSnapshot(
                legacyUri = it.legacyUri,
                upstreamUri = it.upstreamUri,
                role = it.role,
                kind = it.kind,
                contentBase = it.contentBase,
                state = it.state,
            )
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

    private sealed interface PendingEvent {
        fun fire(hooks: ResourceRegistryHooks)

        data class RootAdded(val value: ResourceRegistryRoot) : PendingEvent {
            override fun fire(hooks: ResourceRegistryHooks) = hooks.onRootAdded.fire(value)
        }
        data class RootRemoved(val value: ResourceRegistryRoot) : PendingEvent {
            override fun fire(hooks: ResourceRegistryHooks) = hooks.onRootRemoved.fire(value)
        }
        data class RootUsed(val value: ResourceRegistryRoot) : PendingEvent {
            override fun fire(hooks: ResourceRegistryHooks) = hooks.onRootUsed.fire(value)
        }
        data class ResourceAdded(val value: ResourceRegistryResource) : PendingEvent {
            override fun fire(hooks: ResourceRegistryHooks) = hooks.onResourceAdded.fire(value)
        }
        data class ResourceRemoved(val value: ResourceRegistryResource) : PendingEvent {
            override fun fire(hooks: ResourceRegistryHooks) = hooks.onResourceRemoved.fire(value)
        }
    }

    private val lock = Any()
    private val graphs = linkedMapOf<Long, NavigationResourceGraph>()
    private val contextByLegacyUri = mutableMapOf<String, LinkedHashSet<Long>>()

    fun startNavigation(rootLegacyUri: URI): NavigationResourceGraph {
        val pending = mutableListOf<PendingEvent>()
        val graph = synchronized(lock) {
            while (graphs.size >= maxContexts) {
                evictOldestLocked(pending)
            }
            NavigationResourceGraph(
                id = nextId.getAndIncrement(),
                rootLegacyUri = rootLegacyUri,
                maxNodes = maxNodesPerContext,
                maxEdges = maxEdgesPerContext,
            ).also { graph ->
                graphs[graph.id] = graph
                associateLocked(rootLegacyUri, graph.id)
                val root = ResourceRegistryRoot(graph.id, rootLegacyUri)
                pending += PendingEvent.RootAdded(root)
                pending += PendingEvent.RootUsed(root)
            }
        }
        pending.forEach { it.fire(hooks) }
        return graph
    }

    fun contextsFor(legacyUri: URI): List<NavigationResourceGraph> =
        synchronized(lock) {
            contextByLegacyUri[LegacyHttpUrl.requestObservableKey(legacyUri)]
                ?.mapNotNull(graphs::get)
                .orEmpty()
        }

    fun discover(
        graph: NavigationResourceGraph,
        parentLegacyUri: URI,
        childLegacyUri: URI,
        upstreamUri: URI,
        relation: ResourceRelation,
        kind: ResourceKind,
    ) {
        val pending = mutableListOf<PendingEvent>()
        synchronized(lock) {
            val wasKnown = graph.contains(childLegacyUri)
            if (graph.discover(parentLegacyUri, childLegacyUri, upstreamUri, relation, kind)) {
                associateLocked(childLegacyUri, graph.id)
                pending += PendingEvent.RootUsed(ResourceRegistryRoot(graph.id, graph.rootLegacyUri))
                if (!wasKnown) {
                    graph.nodeSnapshot(childLegacyUri)?.let { resource ->
                        pending += PendingEvent.ResourceAdded(
                            ResourceRegistryResource(graph.id, graph.rootLegacyUri, resource.legacyUri),
                        )
                    }
                }
            }
        }
        pending.forEach { it.fire(hooks) }
    }

    fun snapshots(): List<NavigationResourceGraphSnapshot> =
        synchronized(lock) { graphs.values.map { it.snapshot() } }

    private fun evictOldestLocked(pending: MutableList<PendingEvent>) {
        val oldestId = graphs.keys.firstOrNull() ?: return
        val removed = graphs.remove(oldestId) ?: return
        val removedSnapshot = removed.snapshot()
        pending += PendingEvent.RootRemoved(
            ResourceRegistryRoot(removedSnapshot.id, removedSnapshot.rootLegacyUri),
        )
        removedSnapshot.nodes.forEach { resource ->
            pending += PendingEvent.ResourceRemoved(
                ResourceRegistryResource(removedSnapshot.id, removedSnapshot.rootLegacyUri, resource.legacyUri),
            )
        }
        val iterator = contextByLegacyUri.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.remove(oldestId)
            if (entry.value.isEmpty()) iterator.remove()
        }
    }

    private fun associateLocked(uri: URI, graphId: Long) {
        contextByLegacyUri
            .getOrPut(LegacyHttpUrl.requestObservableKey(uri)) { linkedSetOf() }
            .add(graphId)
    }

    private companion object {
        val nextId = AtomicLong(1)
    }
}
