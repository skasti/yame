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

internal class NavigationResourceGraph(
    val id: Long,
    val rootLegacyUri: URI,
) {
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
    ) {
        upsertNode(
            legacyUri = childLegacyUri,
            upstreamUri = upstreamUri,
            role = relation.role,
            kind = kind,
            contentBase = null,
            state = ResourceState.DISCOVERED,
        )
        edges += ResourceEdgeSnapshot(parentLegacyUri, childLegacyUri, relation)
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
            if (role == ReferenceRole.NAVIGATION) previous.role = ReferenceRole.NAVIGATION
            if (previous.kind == ResourceKind.OTHER) previous.kind = kind
            if (upstreamUri != null) previous.upstreamUri = upstreamUri
            if (contentBase != null) previous.contentBase = contentBase
            if (state.ordinal > previous.state.ordinal) previous.state = state
        }
    }

    private fun key(uri: URI): String = LegacyHttpUrl.requestObservableKey(uri)
}

internal class NavigationResourceRegistry {
    private val nextId = AtomicLong(1)
    private val graphs = linkedMapOf<Long, NavigationResourceGraph>()
    private val contextByLegacyUri = mutableMapOf<String, LinkedHashSet<Long>>()

    @Synchronized
    fun startNavigation(rootLegacyUri: URI): NavigationResourceGraph {
        val graph = NavigationResourceGraph(nextId.getAndIncrement(), rootLegacyUri)
        graphs[graph.id] = graph
        associate(rootLegacyUri, graph.id)
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
        graph.discover(parentLegacyUri, childLegacyUri, upstreamUri, relation, kind)
        associate(childLegacyUri, graph.id)
    }

    @Synchronized
    fun snapshots(): List<NavigationResourceGraphSnapshot> = graphs.values.map { it.snapshot() }

    private fun associate(uri: URI, graphId: Long) {
        contextByLegacyUri
            .getOrPut(LegacyHttpUrl.requestObservableKey(uri)) { linkedSetOf() }
            .add(graphId)
    }
}
