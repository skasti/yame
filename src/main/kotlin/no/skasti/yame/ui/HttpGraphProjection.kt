package no.skasti.yame.ui

import no.skasti.yame.observer.YameEvent
import no.skasti.yame.ppp.proxy.*
import java.net.URI
import java.time.Instant
import java.util.TreeMap

/** Consumer-owned state only. Called under the TUI monitor, on its existing observation path. */
internal class HttpGraphProjection(private val maxPending: Int = 256) {
    class Graph(val rootLegacyUri: URI) {
        val resources = linkedMapOf<String, ResourceLifecycleEvent>()
        val associations = linkedSetOf<ResourceEdgeSnapshot>()
        val removedResources = mutableSetOf<String>()
        var usageCount = 0L
        var lastUsedAt: Instant? = null

        fun use(count: Long, at: Instant?) {
            // A use count describes a domain transition, including when the wall clock goes backwards.
            if (count > usageCount) {
                usageCount = count
                lastUsedAt = at
            } else if (count == usageCount) {
                check(lastUsedAt == at) { "Conflicting proxy graph usage observations" }
            }
        }
    }

    private val graphs = linkedMapOf<Long, Graph>()
    private val retired = RetiredGraphIds()
    private val pending = linkedMapOf<Long, MutableMap<String, YameEvent>>()
    var pendingCount = 0
        private set
    var failure: Throwable? = null
        private set
    val status: String?
        get() = when {
            failure != null -> "Proxy view unavailable: observation failure"
            pendingCount > 0 -> "Proxy view incomplete: waiting for graph creation ($pendingCount)"
            else -> null
        }

    fun graphSnapshot(): Map<Long, Graph> = graphs.toMap()

    fun accept(event: YameEvent) {
        if (failure != null) return // No snapshot recovery: keep the failure visible until restart.
        try {
            when (event) {
                is ResourceGraphLifecycleEvent -> acceptGraph(event)
                is ResourceLifecycleEvent -> acceptResource(event)
            }
        } catch (error: Throwable) {
            failure = error
            graphs.clear()
            pending.clear()
            pendingCount = 0
            throw error
        }
    }

    private fun acceptGraph(event: ResourceGraphLifecycleEvent) {
        if (event.change == ResourceGraphChange.REMOVED) {
            retired.add(event.graphId)
            graphs.remove(event.graphId)
            pendingCount -= pending.remove(event.graphId)?.size ?: 0
            return
        }
        if (event.graphId in retired) return
        val graph = graphs[event.graphId]
        if (graph == null) {
            if (event.change != ResourceGraphChange.CREATED) return defer(event.graphId, event)
            val created = Graph(event.rootLegacyUri)
            graphs[event.graphId] = created
            created.use(event.usageCount, event.lastUsedAt)
            event.initialResources.forEach {
                check(it.graphId == event.graphId && it.rootLegacyUri == event.rootLegacyUri)
                acceptResource(it)
            }
            pending.remove(event.graphId)?.let { deferred ->
                pendingCount -= deferred.size
                deferred.values.forEach(::accept)
            }
        } else {
            check(graph.rootLegacyUri == event.rootLegacyUri) { "Conflicting proxy graph lifetime" }
            graph.use(event.usageCount, event.lastUsedAt)
            // Duplicate CREATED must not reset accumulated resources or their insertion order.
            if (event.change == ResourceGraphChange.CREATED) event.initialResources.forEach(::acceptResource)
        }
    }

    private fun acceptResource(event: ResourceLifecycleEvent) {
        if (event.graphId in retired) return
        val graph = graphs[event.graphId] ?: return defer(event.graphId, event)
        check(graph.rootLegacyUri == event.rootLegacyUri) { "Resource belongs to a different graph lifetime" }
        graph.use(event.graphUsageCount, event.graphLastUsedAt)
        val key = event.canonicalResourceKey
        if (event.change == ResourceLifecycleChange.REMOVED) {
            graph.removedResources += key
            graph.resources.remove(key)
            graph.associations.removeAll { edge ->
                LegacyHttpUrl.requestObservableKey(edge.parentLegacyUri) == key ||
                    LegacyHttpUrl.requestObservableKey(edge.childLegacyUri) == key
            }
            return
        }
        // Current domain semantics: a removed membership is terminal for this graph lifetime.
        if (key in graph.removedResources) return
        graph.associations += event.associations.filter { edge ->
            LegacyHttpUrl.requestObservableKey(edge.parentLegacyUri) !in graph.removedResources &&
                LegacyHttpUrl.requestObservableKey(edge.childLegacyUri) !in graph.removedResources
        }
        val current = graph.resources[key]
        if (current == null || compareResourceObservations(event, current) > 0) {
            graph.resources[key] = event
        }
    }

    private fun defer(graphId: Long, event: YameEvent) {
        val key = when (event) {
            is ResourceLifecycleEvent -> "resource:${event.canonicalResourceKey}"
            is ResourceGraphLifecycleEvent -> "usage"
            else -> error("Unsupported proxy dependency")
        }
        val previous = pending[graphId]?.get(key)
        if (previous is ResourceLifecycleEvent && event is ResourceLifecycleEvent) {
            if (previous.change == ResourceLifecycleChange.REMOVED) return
            if (event.change != ResourceLifecycleChange.REMOVED &&
                compareResourceObservations(event, previous) <= 0) return
        }
        if (previous is ResourceGraphLifecycleEvent && event is ResourceGraphLifecycleEvent) {
            if (event.usageCount < previous.usageCount) return
            if (event.usageCount == previous.usageCount) {
                check(event.lastUsedAt == previous.lastUsedAt) { "Conflicting deferred graph usage" }
                return
            }
        }
        if (previous == null) {
            check(pendingCount < maxPending) { "TUI proxy graph dependency buffer exhausted" }
            pendingCount++
        }
        pending.getOrPut(graphId) { linkedMapOf() }[key] = event
    }
}

/**
 * Compare complete observations of the same membership. Domain progress wins over wall time.
 * Equal-time conflicting metadata without causal evidence is ambiguous, never last-delivery-wins.
 */
internal fun compareResourceObservations(new: ResourceLifecycleEvent, old: ResourceLifecycleEvent): Int {
    val attempt = (new.fetchAttemptNumber ?: 0).compareTo(old.fetchAttemptNumber ?: 0)
    if (attempt != 0) return attempt
    check(new.fetchAttemptId == old.fetchAttemptId) { "Conflicting proxy fetch identities" }
    fun rank(state: ResourceState) = when (state) {
        ResourceState.DISCOVERED -> 0
        ResourceState.FETCHING -> 1
        ResourceState.SOURCE_READY -> 2
        ResourceState.TRANSFORMING -> 3
        ResourceState.READY, ResourceState.FAILED -> 4
    }
    val state = rank(new.state).compareTo(rank(old.state))
    if (state != 0) return state
    check(new.state == old.state) { "Conflicting terminal states in one proxy fetch attempt" }
    // These values can only grow within a membership, even when the clock moves backwards.
    val newEdges = new.associations.toSet()
    val oldEdges = old.associations.toSet()
    fun extends(a: ResourceLifecycleEvent, b: ResourceLifecycleEvent,
                aEdges: Set<ResourceEdgeSnapshot>, bEdges: Set<ResourceEdgeSnapshot>) =
        aEdges.containsAll(bEdges) && (a.prefetched || !b.prefetched) &&
            (a.role == ReferenceRole.NAVIGATION || b.role != ReferenceRole.NAVIGATION)
    val newExtends = extends(new, old, newEdges, oldEdges)
    val oldExtends = extends(old, new, oldEdges, newEdges)
    if (newExtends != oldExtends) return if (newExtends) 1 else -1
    check(newExtends) { "Conflicting proxy membership progress" }
    val time = new.occurredAt.compareTo(old.occurredAt)
    if (time != 0) return time
    check(new.upstreamUri == old.upstreamUri && new.role == old.role && new.kind == old.kind &&
        new.contentBase == old.contentBase && new.prefetched == old.prefetched &&
        new.transformations == old.transformations && newEdges == oldEdges) {
        "Ambiguous equal-time proxy resource observations"
    }
    return 0
}

/** Exact lifetime tombstones, compacted only across IDs actually observed as removed. */
private class RetiredGraphIds {
    private val ranges = TreeMap<Long, Long>()
    operator fun contains(id: Long): Boolean = ranges.floorEntry(id)?.value?.let { id <= it } == true
    fun add(id: Long) {
        if (id in this) return
        var start = id
        var end = id
        ranges.floorEntry(id)?.let { before ->
            if (before.value != Long.MAX_VALUE && before.value + 1 == id) {
                start = before.key
                ranges.remove(before.key)
            }
        }
        ranges.ceilingEntry(id)?.let { after ->
            if (id != Long.MAX_VALUE && after.key == id + 1) {
                end = after.value
                ranges.remove(after.key)
            }
        }
        check(ranges.size < 1024) { "TUI proxy lifetime tombstone buffer exhausted" }
        ranges[start] = end
    }
}
