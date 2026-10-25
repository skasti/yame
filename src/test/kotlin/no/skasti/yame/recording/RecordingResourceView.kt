package no.skasti.yame.recording

import no.skasti.yame.observer.YameEvent
import no.skasti.yame.ppp.proxy.ResourceEdgeSnapshot
import no.skasti.yame.ppp.proxy.ResourceGraphChange
import no.skasti.yame.ppp.proxy.ResourceGraphLifecycleEvent
import no.skasti.yame.ppp.proxy.ResourceLifecycleChange
import no.skasti.yame.ppp.proxy.ResourceLifecycleEvent

/** Independent test consumer: public payloads only, no TUI types or registry queries. */
class RecordingResourceView {
    val resources = linkedMapOf<Long, MutableMap<String, ResourceLifecycleEvent>>()
    val associations = linkedMapOf<Long, MutableSet<ResourceEdgeSnapshot>>()
    private val retired = mutableSetOf<Long>()
    fun accept(event: YameEvent) {
        when (event) {
            is ResourceGraphLifecycleEvent -> when (event.change) {
                ResourceGraphChange.REMOVED -> {
                    retired += event.graphId
                    resources.remove(event.graphId)
                    associations.remove(event.graphId)
                }
                ResourceGraphChange.CREATED -> if (event.graphId !in retired) {
                    val graph = resources.getOrPut(event.graphId) { linkedMapOf() }
                    event.initialResources.forEach { graph.putIfAbsent(it.canonicalResourceKey, it) }
                    associations.getOrPut(event.graphId) { linkedSetOf() }
                        .addAll(event.initialResources.flatMap { it.associations })
                }
                ResourceGraphChange.USED -> Unit
            }
            is ResourceLifecycleEvent -> if (event.graphId !in retired) {
                val graph = resources.getOrPut(event.graphId) { linkedMapOf() }
                if (event.change == ResourceLifecycleChange.REMOVED) graph.remove(event.canonicalResourceKey)
                else {
                    graph[event.canonicalResourceKey] = event
                    associations.getOrPut(event.graphId) { linkedSetOf() }.addAll(event.associations)
                }
            }
        }
    }
}
