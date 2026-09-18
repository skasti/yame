package no.skasti.yame.ppp.proxy

import no.skasti.yame.ppp.proxy.transform.ResourceTransformationSummary
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
    val prefetched: Boolean = false,
    val transformations: List<ResourceTransformationSummary> = emptyList(),
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
    val state: ResourceState,
    val kind: ResourceKind,
    val prefetched: Boolean = false,
    val transformations: List<ResourceTransformationSummary> = emptyList(),
)

internal data class ResourceFetchAttempt(
    val graphId: Long,
    val legacyUri: URI,
    val id: Long,
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
    val onResourceUpdated = ResourceRegistryEvent<ResourceRegistryResource>(dispatch)
    val onResourceRemoved = ResourceRegistryEvent<ResourceRegistryResource>(dispatch)
}

internal class NavigationResourceGraph(
    val id: Long,
    val rootLegacyUri: URI,
    private val maxNodesPerHost: Int,
    private val maxEdges: Int,
) {
    init {
        require(maxNodesPerHost > 0) { "Resource graph node limit per host must be positive" }
        require(maxEdges > 0) { "Resource graph edge limit must be positive" }
    }

    private data class MutableNode(
        val legacyUri: URI,
        var upstreamUri: URI?,
        var role: ReferenceRole,
        var kind: ResourceKind,
        var contentBase: URI?,
        var state: ResourceState,
        var prefetched: Boolean,
        var transformations: List<ResourceTransformationSummary>,
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
            prefetched = false,
            transformations = emptyList(),
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
        if (!canAdmit(childLegacyUri)) return false
        upsertNode(
            legacyUri = childLegacyUri,
            upstreamUri = upstreamUri,
            role = relation.role,
            kind = kind,
            contentBase = null,
            state = ResourceState.DISCOVERED,
            prefetched = false,
            transformations = emptyList(),
        )
        val edge = ResourceEdgeSnapshot(parentLegacyUri, childLegacyUri, relation)
        if (edge in edges || edges.size < maxEdges) edges += edge
        return true
    }

    @Synchronized
    fun ensureDirectResource(legacyUri: URI): Boolean {
        if (!canAdmit(legacyUri)) return false
        if (key(legacyUri) !in nodes) {
            upsertNode(
                legacyUri = legacyUri,
                upstreamUri = null,
                role = ReferenceRole.SUBRESOURCE,
                kind = ResourceKind.OTHER,
                contentBase = null,
                state = ResourceState.DISCOVERED,
            )
        }
        return true
    }

    @Synchronized
    fun markFetched(
        legacyUri: URI,
        upstreamUri: URI,
        contentBase: URI?,
        state: ResourceState,
        prefetched: Boolean = false,
    ) {
        if (!canAdmit(legacyUri)) return
        val key = key(legacyUri)
        val existing = nodes[key]
        if (existing != null) {
            existing.upstreamUri = upstreamUri
            existing.contentBase = contentBase
            existing.state = state
            existing.prefetched = existing.prefetched || prefetched
        } else {
            upsertNode(
                legacyUri = legacyUri,
                upstreamUri = upstreamUri,
                role = ReferenceRole.SUBRESOURCE,
                kind = ResourceKind.OTHER,
                contentBase = contentBase,
                state = state,
                prefetched = prefetched,
                transformations = emptyList(),
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
                prefetched = it.prefetched,
                transformations = it.transformations.toList(),
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
                    prefetched = it.prefetched,
                    transformations = it.transformations.toList(),
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
        prefetched: Boolean = false,
        transformations: List<ResourceTransformationSummary> = emptyList(),
    ) {
        val key = key(legacyUri)
        val previous = nodes[key]
        if (previous == null) {
            nodes[key] =
                MutableNode(
                    legacyUri,
                    upstreamUri,
                    role,
                    kind,
                    contentBase,
                    state,
                    prefetched,
                    transformations,
                )
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
            previous.prefetched = previous.prefetched || prefetched
            if (transformations.isNotEmpty()) previous.transformations = transformations
        }
    }

    @Synchronized
    fun setTransformations(
        legacyUri: URI,
        transformations: List<ResourceTransformationSummary>,
    ): Boolean {
        val node = nodes[key(legacyUri)] ?: return false
        if (node.transformations == transformations) return false
        node.transformations = transformations
        return true
    }

    @Synchronized
    fun updateKind(legacyUri: URI, kind: ResourceKind): Boolean {
        val node = nodes[key(legacyUri)] ?: return false
        if (node.kind == kind) return false
        node.kind = kind
        return true
    }

    private fun canAdmit(legacyUri: URI): Boolean =
        key(legacyUri) in nodes || nodes.size < maxNodesPerHost

    private fun key(uri: URI): String = LegacyHttpUrl.requestObservableKey(uri)
}

internal class NavigationResourceRegistry(
    private val maxContexts: Int = 512,
    private val maxNodesPerHost: Int = 4_096,
    private val maxEdgesPerContext: Int = 2_048,
    val hooks: ResourceRegistryHooks = ResourceRegistryHooks(),
) {
    init {
        require(maxContexts > 0) { "Navigation context limit must be positive" }
        require(maxNodesPerHost > 0) { "Resource graph node limit per host must be positive" }
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
        data class ResourceUpdated(val value: ResourceRegistryResource) : PendingEvent {
            override fun fire(hooks: ResourceRegistryHooks) = hooks.onResourceUpdated.fire(value)
        }
        data class ResourceRemoved(val value: ResourceRegistryResource) : PendingEvent {
            override fun fire(hooks: ResourceRegistryHooks) = hooks.onResourceRemoved.fire(value)
        }
    }

    private val lock = Any()

    private data class FetchResourceKey(
        val graphId: Long,
        val legacyKey: String,
    )

    private val graphs = linkedMapOf<Long, NavigationResourceGraph>()
    private val graphByRootLegacyUri = mutableMapOf<String, Long>()
    private val contextByLegacyUri = mutableMapOf<String, LinkedHashSet<Long>>()
    private val currentFetchAttempt = mutableMapOf<FetchResourceKey, Long>()

    fun startNavigation(rootLegacyUri: URI): NavigationResourceGraph {
        val resourceRoot = LegacyHttpUrl.resourceRoot(rootLegacyUri)
        return getOrCreateGraph(resourceRoot, rootLegacyUri)
    }

    fun ensureResourceContext(resourceLegacyUri: URI): NavigationResourceGraph =
        getOrCreateGraph(LegacyHttpUrl.resourceRoot(resourceLegacyUri), resourceLegacyUri)

    private fun getOrCreateGraph(
        resourceRoot: URI,
        associatedResource: URI,
    ): NavigationResourceGraph {
        val pending = mutableListOf<PendingEvent>()
        val graph =
            synchronized(lock) {
                val rootKey = LegacyHttpUrl.requestObservableKey(resourceRoot)
                graphByRootLegacyUri[rootKey]
                    ?.let(graphs::get)
                    ?.also { existing ->
                        touchLocked(existing.id)
                        admitAssociatedResourceLocked(existing, associatedResource, pending)
                        pending +=
                            PendingEvent.RootUsed(
                                ResourceRegistryRoot(existing.id, existing.rootLegacyUri),
                            )
                    }
                    ?: NavigationResourceGraph(
                        id = nextId.getAndIncrement(),
                        rootLegacyUri = resourceRoot,
                        maxNodesPerHost = maxNodesPerHost,
                        maxEdges = maxEdgesPerContext,
                    ).also { created ->
                        while (graphs.size >= maxContexts) {
                            evictLeastRecentlyUsedLocked(pending)
                        }
                        graphs[created.id] = created
                        graphByRootLegacyUri[rootKey] = created.id
                        associateLocked(resourceRoot, created.id)
                        admitAssociatedResourceLocked(created, associatedResource, pending)
                        val root = ResourceRegistryRoot(created.id, resourceRoot)
                        pending += PendingEvent.RootAdded(root)
                        pending += PendingEvent.RootUsed(root)
                    }
            }
        pending.forEach { it.fire(hooks) }
        return graph
    }

    private fun admitAssociatedResourceLocked(
        graph: NavigationResourceGraph,
        resource: URI,
        pending: MutableList<PendingEvent>,
    ) {
        val wasKnown = graph.contains(resource)
        if (!graph.ensureDirectResource(resource)) return
        associateLocked(resource, graph.id)
        if (!wasKnown && resource != graph.rootLegacyUri) {
            registryResource(graph, resource)?.let {
                pending += PendingEvent.ResourceAdded(it)
            }
        }
    }

    private fun registryResource(
        graph: NavigationResourceGraph,
        legacyUri: URI,
    ): ResourceRegistryResource? =
        graph.nodeSnapshot(legacyUri)?.let { node ->
            ResourceRegistryResource(
                graphId = graph.id,
                rootLegacyUri = graph.rootLegacyUri,
                resourceLegacyUri = node.legacyUri,
                state = node.state,
                kind = node.kind,
                prefetched = node.prefetched,
                transformations = node.transformations,
            )
        }

    fun contextsFor(legacyUri: URI): List<NavigationResourceGraph> =
        synchronized(lock) {
            val graphIds =
                contextByLegacyUri[LegacyHttpUrl.requestObservableKey(legacyUri)]
                    ?.toList()
                    .orEmpty()
            val result = graphIds.mapNotNull(graphs::get)
            graphIds.forEach(::touchLocked)
            result
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
            if (graphs[graph.id] !== graph) return
            val previous = graph.nodeSnapshot(childLegacyUri)
            if (graph.discover(parentLegacyUri, childLegacyUri, upstreamUri, relation, kind)) {
                touchLocked(graph.id)
                associateLocked(childLegacyUri, graph.id)
                pending += PendingEvent.RootUsed(ResourceRegistryRoot(graph.id, graph.rootLegacyUri))
                val resource = graph.nodeSnapshot(childLegacyUri)
                if (previous == null) {
                    resource?.let {
                        pending += PendingEvent.ResourceAdded(
                            requireNotNull(registryResource(graph, it.legacyUri)),
                        )
                    }
                } else if (resource != null && resource != previous) {
                    pending += PendingEvent.ResourceUpdated(
                        requireNotNull(registryResource(graph, resource.legacyUri)),
                    )
                }
            }
        }
        pending.forEach { it.fire(hooks) }
    }

    fun beginFetch(
        graph: NavigationResourceGraph,
        legacyUri: URI,
        upstreamUri: URI,
        contentBase: URI?,
        prefetched: Boolean = false,
    ): ResourceFetchAttempt? {
        val pending = mutableListOf<PendingEvent>()
        val attempt =
            synchronized(lock) {
                if (graphs[graph.id] !== graph) return@synchronized null
                if (!graph.ensureDirectResource(legacyUri)) return@synchronized null
                associateLocked(legacyUri, graph.id)
                val created =
                    ResourceFetchAttempt(
                        graphId = graph.id,
                        legacyUri = legacyUri,
                        id = nextFetchAttemptId.getAndIncrement(),
                    )
                currentFetchAttempt[fetchKey(graph.id, legacyUri)] = created.id
                touchLocked(graph.id)
                graph.markFetched(
                    legacyUri,
                    upstreamUri,
                    contentBase,
                    ResourceState.FETCHING,
                    prefetched,
                )
                registryResource(graph, legacyUri)?.let {
                    pending += PendingEvent.ResourceUpdated(it)
                }
                created
            }
        pending.forEach { it.fire(hooks) }
        return attempt
    }

    fun markFetchState(
        graph: NavigationResourceGraph,
        attempt: ResourceFetchAttempt,
        legacyUri: URI,
        upstreamUri: URI,
        contentBase: URI?,
        state: ResourceState,
    ): Boolean {
        val pending = mutableListOf<PendingEvent>()
        val changed =
            synchronized(lock) {
                if (graphs[graph.id] !== graph) return@synchronized false
                if (attempt.graphId != graph.id) return@synchronized false
                val key = fetchKey(graph.id, legacyUri)
                if (LegacyHttpUrl.requestObservableKey(attempt.legacyUri) != key.legacyKey) {
                    return@synchronized false
                }
                if (currentFetchAttempt[key] != attempt.id) return@synchronized false

                touchLocked(graph.id)
                graph.markFetched(legacyUri, upstreamUri, contentBase, state)
                registryResource(graph, legacyUri)?.let {
                    pending += PendingEvent.ResourceUpdated(it)
                }
                if (state == ResourceState.READY || state == ResourceState.FAILED) {
                    currentFetchAttempt.remove(key, attempt.id)
                }
                true
            }
        pending.forEach { it.fire(hooks) }
        return changed
    }

    fun markFetched(
        graph: NavigationResourceGraph,
        legacyUri: URI,
        upstreamUri: URI,
        contentBase: URI?,
        state: ResourceState,
        prefetched: Boolean = false,
    ) {
        val pending = mutableListOf<PendingEvent>()
        synchronized(lock) {
            if (graphs[graph.id] !== graph) return
            if (!graph.ensureDirectResource(legacyUri)) return
            associateLocked(legacyUri, graph.id)
            touchLocked(graph.id)
            graph.markFetched(legacyUri, upstreamUri, contentBase, state, prefetched)
            registryResource(graph, legacyUri)?.let {
                pending += PendingEvent.ResourceUpdated(it)
            }
        }
        pending.forEach { it.fire(hooks) }
    }

    fun recordTransformations(
        legacyUri: URI,
        transformations: List<ResourceTransformationSummary>,
    ) {
        val pending = mutableListOf<PendingEvent>()
        synchronized(lock) {
            contextByLegacyUri[LegacyHttpUrl.requestObservableKey(legacyUri)]
                ?.mapNotNull(graphs::get)
                .orEmpty()
                .forEach { graph ->
                    if (graph.setTransformations(legacyUri, transformations)) {
                        registryResource(graph, legacyUri)?.let {
                            pending += PendingEvent.ResourceUpdated(it)
                        }
                    }
                }
        }
        pending.forEach { it.fire(hooks) }
    }

    fun updateResourceKind(
        graph: NavigationResourceGraph,
        legacyUri: URI,
        kind: ResourceKind,
    ) {
        val pending = mutableListOf<PendingEvent>()
        synchronized(lock) {
            if (graphs[graph.id] !== graph) return
            if (graph.updateKind(legacyUri, kind)) {
                registryResource(graph, legacyUri)?.let {
                    pending += PendingEvent.ResourceUpdated(it)
                }
            }
        }
        pending.forEach { it.fire(hooks) }
    }

    fun snapshots(): List<NavigationResourceGraphSnapshot> =
        synchronized(lock) { graphs.values.map { it.snapshot() } }

    private fun touchLocked(graphId: Long) {
        val graph = graphs.remove(graphId) ?: return
        graphs[graphId] = graph
    }

    private fun evictLeastRecentlyUsedLocked(pending: MutableList<PendingEvent>) {
        val oldestId = graphs.keys.firstOrNull() ?: return
        val removed = graphs.remove(oldestId) ?: return
        graphByRootLegacyUri.remove(LegacyHttpUrl.requestObservableKey(removed.rootLegacyUri))
        val snapshot = removed.snapshot()
        pending += PendingEvent.RootRemoved(ResourceRegistryRoot(snapshot.id, snapshot.rootLegacyUri))
        snapshot.nodes.forEach { resource ->
            pending +=
                PendingEvent.ResourceRemoved(
                    ResourceRegistryResource(
                        graphId = snapshot.id,
                        rootLegacyUri = snapshot.rootLegacyUri,
                        resourceLegacyUri = resource.legacyUri,
                        state = resource.state,
                        kind = resource.kind,
                        prefetched = resource.prefetched,
                        transformations = resource.transformations,
                    ),
                )
        }
        val iterator = contextByLegacyUri.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.remove(oldestId)
            if (entry.value.isEmpty()) iterator.remove()
        }
        currentFetchAttempt.keys.removeIf { it.graphId == oldestId }
    }

    private fun fetchKey(
        graphId: Long,
        legacyUri: URI,
    ): FetchResourceKey =
        FetchResourceKey(graphId, LegacyHttpUrl.requestObservableKey(legacyUri))

    private fun associateLocked(uri: URI, graphId: Long) {
        contextByLegacyUri
            .getOrPut(LegacyHttpUrl.requestObservableKey(uri)) { linkedSetOf() }
            .add(graphId)
    }

    private companion object {
        val nextId = AtomicLong(1)
        val nextFetchAttemptId = AtomicLong(1)
    }
}
