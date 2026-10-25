package no.skasti.yame.ppp.proxy

import no.skasti.yame.observer.YameEvent
import no.skasti.yame.observer.YameEvents
import no.skasti.yame.ppp.proxy.transform.ResourceTransformationSummary
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

enum class ResourceKind {
    DOCUMENT,
    STYLESHEET,
    IMAGE,
    SCRIPT,
    FRAME,
    FORM,
    OTHER,
}

enum class ResourceRelation(val role: ReferenceRole) {
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

enum class ResourceState {
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
    val fetchAttemptId: Long? = null,
    val fetchAttemptNumber: Long? = null,
)

data class ResourceEdgeSnapshot(
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

internal data class ResourceFetchAttempt(
    val graphId: Long,
    val legacyUri: URI,
    val id: Long,
    val attemptNumber: Long,
)

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
        var fetchAttemptId: Long?,
        var fetchAttemptNumber: Long?,
    )

    private val nodes = linkedMapOf<String, MutableNode>()
    private val edges = linkedSetOf<ResourceEdgeSnapshot>()
    private var usageCount = 0L
    private var lastUsedAt: Instant? = null

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
    fun beginFetch(
        legacyUri: URI,
        upstreamUri: URI,
        contentBase: URI?,
        attemptId: Long,
        attemptNumber: Long,
        prefetched: Boolean,
    ): Boolean {
        if (!canAdmit(legacyUri)) return false
        if (key(legacyUri) !in nodes) {
            upsertNode(
                legacyUri = legacyUri,
                upstreamUri = upstreamUri,
                role = ReferenceRole.SUBRESOURCE,
                kind = ResourceKind.OTHER,
                contentBase = contentBase,
                state = ResourceState.FETCHING,
                prefetched = prefetched,
            )
        }
        val node = requireNotNull(nodes[key(legacyUri)])
        node.upstreamUri = upstreamUri
        node.contentBase = contentBase
        node.state = ResourceState.FETCHING
        node.prefetched = node.prefetched || prefetched
        node.fetchAttemptId = attemptId
        node.fetchAttemptNumber = attemptNumber
        return true
    }

    @Synchronized
    fun markFetchState(
        legacyUri: URI,
        upstreamUri: URI,
        contentBase: URI?,
        state: ResourceState,
    ): Boolean {
        val node = nodes[key(legacyUri)] ?: return false
        if (!canTransition(node.state, state)) return false
        node.upstreamUri = upstreamUri
        node.contentBase = contentBase
        node.state = state
        return true
    }

    @Synchronized
    fun recordUse(occurredAt: Instant) {
        if (usageCount < Long.MAX_VALUE) usageCount++
        lastUsedAt = occurredAt
    }

    @Synchronized
    fun usageSnapshot(): Pair<Long, Instant?> = usageCount to lastUsedAt

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
                transformations = immutableSnapshotList(it.transformations),
                fetchAttemptId = it.fetchAttemptId,
                fetchAttemptNumber = it.fetchAttemptNumber,
            )
        }

    @Synchronized
    fun associationsFor(legacyUri: URI): List<ResourceEdgeSnapshot> {
        val resourceKey = key(legacyUri)
        return immutableSnapshotList(
            edges.filter { edge ->
                key(edge.parentLegacyUri) == resourceKey || key(edge.childLegacyUri) == resourceKey
            },
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
                    transformations = immutableSnapshotList(it.transformations),
                    fetchAttemptId = it.fetchAttemptId,
                    fetchAttemptNumber = it.fetchAttemptNumber,
                )
            },
            edges = immutableSnapshotList(edges),
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
                    immutableSnapshotList(transformations),
                    null,
                    null,
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
            if (transformations.isNotEmpty()) previous.transformations = immutableSnapshotList(transformations)
        }
    }

    @Synchronized
    fun setTransformations(
        legacyUri: URI,
        transformations: List<ResourceTransformationSummary>,
    ): Boolean {
        val node = nodes[key(legacyUri)] ?: return false
        if (node.transformations == transformations) return false
        node.transformations = immutableSnapshotList(transformations)
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

    private fun canTransition(from: ResourceState, to: ResourceState): Boolean =
        when (from) {
            ResourceState.FETCHING -> to in setOf(
                ResourceState.SOURCE_READY,
                ResourceState.TRANSFORMING,
                ResourceState.READY,
                ResourceState.FAILED,
            )
            ResourceState.SOURCE_READY -> to in setOf(
                ResourceState.TRANSFORMING,
                ResourceState.READY,
                ResourceState.FAILED,
            )
            ResourceState.TRANSFORMING -> to == ResourceState.READY || to == ResourceState.FAILED
            ResourceState.DISCOVERED, ResourceState.READY, ResourceState.FAILED -> false
        }

    private fun key(uri: URI): String = LegacyHttpUrl.requestObservableKey(uri)
}

internal class NavigationResourceRegistry(
    private val maxContexts: Int = 512,
    private val maxNodesPerHost: Int = 4_096,
    private val maxEdgesPerContext: Int = 2_048,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(maxContexts > 0) { "Navigation context limit must be positive" }
        require(maxNodesPerHost > 0) { "Resource graph node limit per host must be positive" }
        require(maxEdgesPerContext > 0) { "Resource graph edge limit must be positive" }
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
        val pending = mutableListOf<YameEvent>()
        val graph =
            synchronized(lock) {
                val rootKey = LegacyHttpUrl.requestObservableKey(resourceRoot)
                val existing = graphByRootLegacyUri[rootKey]?.let(graphs::get)
                if (existing != null) {
                    val occurredAt = clock.instant()
                    touchLocked(existing.id)
                    existing.recordUse(occurredAt)
                    admitAssociatedResourceLocked(existing, associatedResource, occurredAt, pending)
                    pending += graphLifecycleEvent(existing, ResourceGraphChange.USED, occurredAt)
                    existing
                } else {
                    while (graphs.size >= maxContexts) evictLeastRecentlyUsedLocked(pending)
                    val occurredAt = clock.instant()
                    NavigationResourceGraph(
                        id = nextId.getAndIncrement(),
                        rootLegacyUri = resourceRoot,
                        maxNodesPerHost = maxNodesPerHost,
                        maxEdges = maxEdgesPerContext,
                    ).also { created ->
                        graphs[created.id] = created
                        graphByRootLegacyUri[rootKey] = created.id
                        associateLocked(resourceRoot, created.id)
                        created.recordUse(occurredAt)
                        admitAssociatedResourceLocked(created, associatedResource, occurredAt, pending)
                        pending += graphLifecycleEvent(
                            created,
                            ResourceGraphChange.CREATED,
                            occurredAt,
                            includeInitialResources = true,
                        )
                        pending += graphLifecycleEvent(created, ResourceGraphChange.USED, occurredAt)
                    }
                }
            }
        deliver(pending)
        return graph
    }

    private fun admitAssociatedResourceLocked(
        graph: NavigationResourceGraph,
        resource: URI,
        occurredAt: Instant,
        pending: MutableList<YameEvent>,
    ) {
        val wasKnown = graph.contains(resource)
        if (!graph.ensureDirectResource(resource)) return
        associateLocked(resource, graph.id)
        if (!wasKnown && LegacyHttpUrl.requestObservableKey(resource) !=
            LegacyHttpUrl.requestObservableKey(graph.rootLegacyUri)
        ) {
            resourceLifecycleEvent(graph, resource, ResourceLifecycleChange.ADMITTED, occurredAt)
                ?.let(pending::add)
        }
    }

    private fun graphLifecycleEvent(
        graph: NavigationResourceGraph,
        change: ResourceGraphChange,
        occurredAt: Instant,
        includeInitialResources: Boolean = false,
    ): ResourceGraphLifecycleEvent {
        val (usageCount, lastUsedAt) = graph.usageSnapshot()
        val initialResources =
            if (includeInitialResources) {
                graph.snapshot().nodes.mapNotNull { node ->
                    resourceLifecycleEvent(graph, node.legacyUri, ResourceLifecycleChange.ADMITTED, occurredAt)
                }
            } else {
                emptyList()
            }
        return ResourceGraphLifecycleEvent(
            change = change,
            graphId = graph.id,
            rootLegacyUri = graph.rootLegacyUri,
            occurredAt = occurredAt,
            usageCount = usageCount,
            lastUsedAt = lastUsedAt,
            initialResources = initialResources,
        )
    }

    private fun resourceLifecycleEvent(
        graph: NavigationResourceGraph,
        legacyUri: URI,
        change: ResourceLifecycleChange,
        occurredAt: Instant,
    ): ResourceLifecycleEvent? = graph.nodeSnapshot(legacyUri)?.let { resource ->
        val (usageCount, lastUsedAt) = graph.usageSnapshot()
        ResourceLifecycleEvent(
            change = change,
            graphId = graph.id,
            rootLegacyUri = graph.rootLegacyUri,
            canonicalResourceKey = LegacyHttpUrl.requestObservableKey(resource.legacyUri),
            resourceLegacyUri = resource.legacyUri,
            upstreamUri = resource.upstreamUri,
            role = resource.role,
            kind = resource.kind,
            contentBase = resource.contentBase,
            state = resource.state,
            prefetched = resource.prefetched,
            fetchAttemptId = resource.fetchAttemptId,
            fetchAttemptNumber = resource.fetchAttemptNumber,
            associations = graph.associationsFor(legacyUri),
            transformations = resource.transformations,
            graphUsageCount = usageCount,
            graphLastUsedAt = lastUsedAt,
            occurredAt = occurredAt,
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
        val pending = mutableListOf<YameEvent>()
        synchronized(lock) {
            if (graphs[graph.id] !== graph) return
            val previous = graph.nodeSnapshot(childLegacyUri)
            val previousAssociations = graph.associationsFor(childLegacyUri)
            val occurredAt = clock.instant()
            if (graph.discover(parentLegacyUri, childLegacyUri, upstreamUri, relation, kind)) {
                touchLocked(graph.id)
                graph.recordUse(occurredAt)
                associateLocked(childLegacyUri, graph.id)
                pending += graphLifecycleEvent(graph, ResourceGraphChange.USED, occurredAt)
                val resource = graph.nodeSnapshot(childLegacyUri)
                val associations = graph.associationsFor(childLegacyUri)
                if (previous == null) {
                    resourceLifecycleEvent(graph, childLegacyUri, ResourceLifecycleChange.DISCOVERED, occurredAt)
                        ?.let(pending::add)
                } else if (resource != null && associations != previousAssociations) {
                    resourceLifecycleEvent(
                        graph,
                        childLegacyUri,
                        ResourceLifecycleChange.ASSOCIATION_ADDED,
                        occurredAt,
                    )?.let(pending::add)
                } else if (resource != previous) {
                    resourceLifecycleEvent(graph, childLegacyUri, ResourceLifecycleChange.UPDATED, occurredAt)
                        ?.let(pending::add)
                }
            }
        }
        deliver(pending)
    }

    fun beginFetch(
        graph: NavigationResourceGraph,
        legacyUri: URI,
        upstreamUri: URI,
        contentBase: URI?,
        prefetched: Boolean = false,
    ): ResourceFetchAttempt? {
        val pending = mutableListOf<YameEvent>()
        val attempt =
            synchronized(lock) {
                if (graphs[graph.id] !== graph) return@synchronized null
                val occurredAt = clock.instant()
                val previous = graph.nodeSnapshot(legacyUri)
                if (!graph.ensureDirectResource(legacyUri)) return@synchronized null
                associateLocked(legacyUri, graph.id)
                val attemptNumber = (previous?.fetchAttemptNumber ?: 0L).let {
                    if (it == Long.MAX_VALUE) it else it + 1
                }
                val created =
                    ResourceFetchAttempt(
                        graphId = graph.id,
                        legacyUri = legacyUri,
                        id = nextFetchAttemptId.getAndIncrement(),
                        attemptNumber = attemptNumber,
                    )
                currentFetchAttempt[fetchKey(graph.id, legacyUri)] = created.id
                touchLocked(graph.id)
                graph.beginFetch(
                    legacyUri,
                    upstreamUri,
                    contentBase,
                    created.id,
                    created.attemptNumber,
                    prefetched,
                )
                if (previous == null) {
                    resourceLifecycleEvent(graph, legacyUri, ResourceLifecycleChange.ADMITTED, occurredAt)
                        ?.let(pending::add)
                }
                resourceLifecycleEvent(
                    graph,
                    legacyUri,
                    if (created.attemptNumber == 1L) {
                        ResourceLifecycleChange.FETCH_STARTED
                    } else {
                        ResourceLifecycleChange.FETCH_RETRIED
                    },
                    occurredAt,
                )?.let(pending::add)
                created
            }
        deliver(pending)
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
        val pending = mutableListOf<YameEvent>()
        val changed =
            synchronized(lock) {
                if (graphs[graph.id] !== graph) return@synchronized false
                if (attempt.graphId != graph.id) return@synchronized false
                val key = fetchKey(graph.id, legacyUri)
                if (LegacyHttpUrl.requestObservableKey(attempt.legacyUri) != key.legacyKey) {
                    return@synchronized false
                }
                if (currentFetchAttempt[key] != attempt.id) return@synchronized false
                val currentResource = graph.nodeSnapshot(legacyUri) ?: return@synchronized false
                if (currentResource.fetchAttemptId != attempt.id ||
                    currentResource.fetchAttemptNumber != attempt.attemptNumber
                ) {
                    return@synchronized false
                }

                val occurredAt = clock.instant()
                if (!graph.markFetchState(legacyUri, upstreamUri, contentBase, state)) {
                    return@synchronized false
                }
                touchLocked(graph.id)
                resourceLifecycleEvent(
                    graph,
                    legacyUri,
                    if (state == ResourceState.READY || state == ResourceState.FAILED) {
                        ResourceLifecycleChange.FETCH_COMPLETED
                    } else {
                        ResourceLifecycleChange.FETCH_STATE_CHANGED
                    },
                    occurredAt,
                )?.let(pending::add)
                if (state == ResourceState.READY || state == ResourceState.FAILED) {
                    currentFetchAttempt.remove(key, attempt.id)
                }
                true
            }
        deliver(pending)
        return changed
    }

    fun recordTransformations(
        legacyUri: URI,
        transformations: List<ResourceTransformationSummary>,
    ) {
        val pending = mutableListOf<YameEvent>()
        val snapshot = immutableSnapshotList(transformations)
        synchronized(lock) {
            contextByLegacyUri[LegacyHttpUrl.requestObservableKey(legacyUri)]
                ?.mapNotNull(graphs::get)
                .orEmpty()
                .forEach { graph ->
                    val occurredAt = clock.instant()
                    if (graph.setTransformations(legacyUri, snapshot)) {
                        resourceLifecycleEvent(
                            graph,
                            legacyUri,
                            ResourceLifecycleChange.TRANSFORMATIONS_UPDATED,
                            occurredAt,
                        )?.let(pending::add)
                    }
                }
        }
        deliver(pending)
    }

    fun updateResourceKind(
        graph: NavigationResourceGraph,
        legacyUri: URI,
        kind: ResourceKind,
    ) {
        val pending = mutableListOf<YameEvent>()
        synchronized(lock) {
            if (graphs[graph.id] !== graph) return
            val occurredAt = clock.instant()
            if (graph.updateKind(legacyUri, kind)) {
                resourceLifecycleEvent(
                    graph,
                    legacyUri,
                    ResourceLifecycleChange.KIND_UPDATED,
                    occurredAt,
                )?.let(pending::add)
            }
        }
        deliver(pending)
    }

    fun snapshots(): List<NavigationResourceGraphSnapshot> =
        synchronized(lock) { graphs.values.map { it.snapshot() } }

    fun snapshot(graphId: Long): NavigationResourceGraphSnapshot? =
        synchronized(lock) { graphs[graphId]?.snapshot() }

    private fun touchLocked(graphId: Long) {
        val graph = graphs.remove(graphId) ?: return
        graphs[graphId] = graph
    }

    /** Stage immutable observations under the lock; broker admission/fatal failures propagate outside it. */
    private fun deliver(pending: List<YameEvent>) {
        pending.forEach { YameEvents.publish(it) }
    }

    private fun evictLeastRecentlyUsedLocked(pending: MutableList<YameEvent>) {
        if (graphs.isEmpty()) return
        val occurredAt = clock.instant()
        val oldestId = graphs.keys.first()
        val removed = graphs.remove(oldestId) ?: return
        graphByRootLegacyUri.remove(LegacyHttpUrl.requestObservableKey(removed.rootLegacyUri))
        val snapshot = removed.snapshot()
        pending += graphLifecycleEvent(removed, ResourceGraphChange.REMOVED, occurredAt)
        snapshot.nodes.forEach { resource ->
            resourceLifecycleEvent(removed, resource.legacyUri, ResourceLifecycleChange.REMOVED, occurredAt)
                ?.let(pending::add)
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
