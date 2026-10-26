package no.skasti.yame.recording

import no.skasti.yame.observer.YameEvent
import no.skasti.yame.ppp.proxy.ResourceEdgeSnapshot
import no.skasti.yame.ppp.proxy.ResourceGraphChange
import no.skasti.yame.ppp.proxy.ResourceGraphLifecycleEvent
import no.skasti.yame.ppp.proxy.ResourceLifecycleChange
import no.skasti.yame.ppp.proxy.ResourceLifecycleEvent
import java.net.URI
import java.time.Instant
import java.util.Locale

/** Small recording consumer for controlled test traces: public payloads only, no TUI or registry queries. */
class RecordingResourceView {
    class Graph(val id: Long, val rootUri: URI) {
        var usageCount = 0L
            private set
        var lastUsedAt: Instant? = null
            private set
        val resources = linkedMapOf<String, ResourceLifecycleEvent>()
        val associations = linkedSetOf<ResourceEdgeSnapshot>()
        internal val removedResources = mutableSetOf<String>()
        internal val removedAssociations = mutableSetOf<ResourceEdgeSnapshot>()

        internal fun recordUsage(count: Long, at: Instant?) {
            if (count > usageCount) {
                usageCount = count
                lastUsedAt = at
            }
        }
    }

    data class Host(
        val name: String,
        val resources: Map<String, ResourceLifecycleEvent>,
        val usageCount: Long,
        val lastUsedAt: Instant?,
    )

    val graphs = linkedMapOf<Long, Graph>()
    private val retired = mutableSetOf<Long>()

    fun accept(event: YameEvent) {
        when (event) {
            is ResourceGraphLifecycleEvent -> {
                if (event.change == ResourceGraphChange.REMOVED) {
                    retired += event.graphId
                    graphs.remove(event.graphId)
                } else if (event.graphId !in retired) {
                    val graph = graph(event.graphId, event.rootLegacyUri)
                    graph.recordUsage(event.usageCount, event.lastUsedAt)
                    if (event.change == ResourceGraphChange.CREATED) {
                        event.initialResources.forEach { resource ->
                            if (resource.canonicalResourceKey !in graph.resources) accept(resource)
                        }
                    }
                }
            }
            is ResourceLifecycleEvent -> if (event.graphId !in retired) {
                val graph = graph(event.graphId, event.rootLegacyUri)
                graph.recordUsage(event.graphUsageCount, event.graphLastUsedAt)
                if (event.change == ResourceLifecycleChange.REMOVED) {
                    graph.removedResources += event.canonicalResourceKey
                    graph.resources.remove(event.canonicalResourceKey)
                    // The public removal payload carries all incident edges, including outgoing ones.
                    graph.removedAssociations += event.associations
                    graph.associations.removeAll(event.associations.toSet())
                } else if (event.canonicalResourceKey !in graph.removedResources) {
                    val previous = graph.resources[event.canonicalResourceKey]
                    // Fixtures use controlled occurrence times; this recorder is not a general reorder engine.
                    if (previous == null || event.occurredAt > previous.occurredAt) {
                        graph.resources[event.canonicalResourceKey] = event
                    }
                    graph.associations.addAll(event.associations.filterNot { it in graph.removedAssociations })
                }
            }
        }
    }

    private fun graph(id: Long, root: URI): Graph = graphs.getOrPut(id) { Graph(id, root) }.also {
        check(it.rootUri == root) { "Conflicting root URI for graph $id" }
    }

    /** Aggregate memberships independently of the TUI's imperative host projection. */
    fun hosts(): List<Host> {
        val memberships = graphs.values.flatMap { graph ->
            graph.resources.values.map { resource -> graph to resource }
        }
        return memberships.groupBy { (_, resource) -> resource.resourceLegacyUri.host.lowercase(Locale.ROOT) }
            .map { (name, members) ->
                val contributingGraphs = members.map { it.first }.distinctBy { it.id }
                val resources = members.groupBy { it.second.canonicalResourceKey }.mapValues { (_, copies) ->
                    copies.maxWith(compareBy<Pair<Graph, ResourceLifecycleEvent>> { it.second.occurredAt }
                        .thenBy { it.first.id }).second
                }
                Host(name, resources, contributingGraphs.sumOf { it.usageCount },
                    contributingGraphs.mapNotNull { it.lastUsedAt }.maxOrNull())
            }
            .sortedWith(compareByDescending<Host> { it.lastUsedAt }
                .thenByDescending { it.usageCount }.thenBy { it.name })
    }
}
