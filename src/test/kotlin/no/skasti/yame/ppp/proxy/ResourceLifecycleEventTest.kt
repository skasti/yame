package no.skasti.yame.ppp.proxy

import no.skasti.yame.observer.YameEvent
import no.skasti.yame.observer.YameEvents
import no.skasti.yame.observer.EventBroker
import no.skasti.yame.observer.BrokerWaitResult
import no.skasti.yame.observer.VersionNumber
import no.skasti.yame.ppp.proxy.transform.ResourceTransformationSummary
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceLifecycleEventTest {
    private val events = Collections.synchronizedList(mutableListOf<YameEvent>())
    private lateinit var broker: EventBroker
    private lateinit var installation: AutoCloseable
    private lateinit var subscription: AutoCloseable
    private val timeout = Duration.ofSeconds(5)

    @BeforeTest fun setUp() {
        broker = EventBroker(initialCapacity = 4096, initialPublishTimeout = java.time.Duration.ofMillis(10), initialMaxSubscribers = 64)
        installation = YameEvents.install(broker)
        subscription = YameEvents.subscribe("resource-test") { events += it }
    }

    @AfterTest fun tearDown() {
        subscription.close()
        broker.close()
        assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(timeout))
        installation.close()
        events.clear()
    }

    private fun observedEvents(): List<YameEvent> {
        assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(timeout))
        return synchronized(events) { events.toList() }
    }

    @Test
    fun `events carry occurrence time complete resource state and graph usage`() {
        val clock = MutableClock(Instant.parse("2026-10-24T12:00:00Z"))
        val registry = NavigationResourceRegistry(clock = clock)
        val rootRequest = URI("http://Example.test:80/index.html")
        val firstOccurrence = clock.instant()

        val graph = registry.startNavigation(rootRequest)
        clock.advanceSeconds(1)
        val admitted = observedEvents().filterIsInstance<ResourceLifecycleEvent>().first()
        val created = observedEvents().filterIsInstance<ResourceGraphLifecycleEvent>()
            .first { it.change == ResourceGraphChange.CREATED }
        val firstUse = observedEvents().filterIsInstance<ResourceGraphLifecycleEvent>()
            .first { it.change == ResourceGraphChange.USED }

        assertEquals("yame.ppp.proxy.resource.lifecycle", admitted.typeName)
        assertEquals(VersionNumber(1, 1, 0), admitted.version)
        assertTrue(admitted.resourceRevision > 0L)
        assertEquals(admitted.resourceRevision, admitted["resourceRevision"])
        assertEquals(ResourceLifecycleChange.ADMITTED, admitted.change)
        assertEquals(graph.id, admitted.graphId)
        assertEquals(rootRequest, admitted.resourceLegacyUri)
        assertEquals("http://example.test/index.html", admitted.canonicalResourceKey)
        assertEquals(URI("http://example.test/"), admitted.rootLegacyUri)
        assertEquals(ResourceState.DISCOVERED, admitted.state)
        assertEquals(firstOccurrence, admitted.occurredAt)
        assertEquals(firstOccurrence, created.occurredAt)
        assertEquals(
            setOf("http://example.test/", "http://example.test/index.html"),
            created.initialResources.map { it.canonicalResourceKey }.toSet(),
        )
        assertTrue(created.initialResources.all { it.occurredAt == firstOccurrence })
        assertEquals(firstOccurrence, firstUse.lastUsedAt)
        assertEquals(1L, firstUse.usageCount)

        val child = URI("http://cdn.test/site.css")
        val associationTime = clock.instant()
        registry.discover(
            graph,
            rootRequest,
            child,
            URI("https://cdn.test/site.css"),
            ResourceRelation.LINK_STYLESHEET,
            ResourceKind.STYLESHEET,
        )
        val discovered = observedEvents().filterIsInstance<ResourceLifecycleEvent>()
            .last { it.change == ResourceLifecycleChange.DISCOVERED }
        val latestUse = observedEvents().filterIsInstance<ResourceGraphLifecycleEvent>()
            .last { it.change == ResourceGraphChange.USED }

        assertEquals(associationTime, discovered.occurredAt)
        assertEquals(graph.id, discovered.graphId)
        assertEquals(URI("http://example.test/"), discovered.rootLegacyUri)
        assertEquals("http://cdn.test/site.css", discovered.canonicalResourceKey)
        assertEquals(URI("https://cdn.test/site.css"), discovered.upstreamUri)
        assertEquals(ReferenceRole.SUBRESOURCE, discovered.role)
        assertEquals(ResourceKind.STYLESHEET, discovered.kind)
        assertEquals(
            listOf(ResourceEdgeSnapshot(rootRequest, child, ResourceRelation.LINK_STYLESHEET)),
            discovered.associations,
        )
        assertEquals(2L, latestUse.usageCount)
        assertEquals(associationTime, latestUse.lastUsedAt)

        val revisited = registry.startNavigation(rootRequest)
        assertEquals(graph.id, revisited.id)
        assertEquals(3L, observedEvents().filterIsInstance<ResourceGraphLifecycleEvent>()
            .last { it.change == ResourceGraphChange.USED }.usageCount)
    }

    @Test
    fun `repeated discovery reports one resource admission and cumulative graph use`() {
        val registry = NavigationResourceRegistry()
        val request = URI("http://legacy.test/page")
        val graph = registry.startNavigation(request)
        val child = URI("http://cdn.test/shared.gif")
        repeat(2) {
            registry.discover(graph, request, child, URI("https://cdn.test/shared.gif"),
                ResourceRelation.IMG_SRC, ResourceKind.IMAGE)
        }
        val observed = observedEvents()
        val resources = observed.filterIsInstance<ResourceLifecycleEvent>()
        assertEquals(listOf(request), resources.filter { it.change == ResourceLifecycleChange.ADMITTED }
            .map { it.resourceLegacyUri })
        assertEquals(listOf(child), resources.filter { it.change == ResourceLifecycleChange.DISCOVERED }
            .map { it.resourceLegacyUri })
        assertEquals(listOf(1L, 2L, 3L), observed.filterIsInstance<ResourceGraphLifecycleEvent>()
            .filter { it.change == ResourceGraphChange.USED }.map { it.usageCount })
    }

    @Test
    fun `fetch retry has a new identity and can return to fetching after ready`() {
        val registry = NavigationResourceRegistry()
        val graph = registry.startNavigation(URI("http://legacy.test/root"))
        val resource = URI("http://legacy.test/image.gif")
        val upstream = URI("https://legacy.test/image.gif")
        registry.discover(
            graph,
            graph.rootLegacyUri,
            resource,
            upstream,
            ResourceRelation.IMG_SRC,
            ResourceKind.IMAGE,
        )

        val first = requireNotNull(registry.beginFetch(graph, resource, upstream, upstream))
        assertTrue(registry.markFetchState(graph, first, resource, upstream, upstream, ResourceState.READY))
        val retry = requireNotNull(registry.beginFetch(graph, resource, upstream, upstream, prefetched = true))
        assertTrue(first.id != retry.id)
        assertEquals(1L, first.attemptNumber)
        assertEquals(2L, retry.attemptNumber)

        val retryStarted = observedEvents().filterIsInstance<ResourceLifecycleEvent>()
            .last { it.change == ResourceLifecycleChange.FETCH_RETRIED }
        assertEquals(ResourceState.FETCHING, retryStarted.state)
        assertEquals(retry.id, retryStarted.fetchAttemptId)
        assertEquals(2L, retryStarted.fetchAttemptNumber)
        assertTrue(retryStarted.prefetched)

        assertFalse(registry.markFetchState(graph, first, resource, upstream, upstream, ResourceState.FAILED))
        assertTrue(registry.markFetchState(graph, retry, resource, upstream, upstream, ResourceState.READY))
        val completion = observedEvents().filterIsInstance<ResourceLifecycleEvent>()
            .last { it.change == ResourceLifecycleChange.FETCH_COMPLETED }
        assertEquals(ResourceState.READY, completion.state)
        assertEquals(retry.id, completion.fetchAttemptId)
        assertEquals(2L, completion.fetchAttemptNumber)
        assertTrue(completion.prefetched)
        assertEquals(ResourceState.READY, graph.snapshot().nodes.single { it.legacyUri == resource }.state)
    }

    @Test
    fun `authoritative resource revisions distinguish changes when clock never advances`() {
        val clock = MutableClock(Instant.parse("2026-10-24T12:00:00Z"))
        val registry = NavigationResourceRegistry(clock = clock)
        val page = URI("http://site.test/index.html")
        val child = URI("http://cdn.test/image.gif")
        val upstream = URI("https://cdn.test/image.gif")
        val graph = registry.startNavigation(page)

        registry.discover(graph, page, child, upstream, ResourceRelation.IMG_SRC, ResourceKind.IMAGE)
        // Same known edge, no resource change (graph USE still increments).
        registry.discover(graph, page, child, upstream, ResourceRelation.IMG_SRC, ResourceKind.IMAGE)
        registry.discover(graph, page, child, upstream, ResourceRelation.CSS_URL, ResourceKind.IMAGE)
        registry.updateResourceKind(graph, child, ResourceKind.SCRIPT)
        registry.recordTransformations(child, listOf(ResourceTransformationSummary("rewrite", 100, 50)))
        val attempt = requireNotNull(registry.beginFetch(graph, child, upstream, upstream))
        assertTrue(registry.markFetchState(graph, attempt, child, upstream, upstream, ResourceState.READY))

        val observations = observedEvents().filterIsInstance<ResourceLifecycleEvent>()
            .filter { it.resourceLegacyUri == child }
        assertEquals(listOf(
            ResourceLifecycleChange.DISCOVERED,
            ResourceLifecycleChange.ASSOCIATION_ADDED,
            ResourceLifecycleChange.KIND_UPDATED,
            ResourceLifecycleChange.TRANSFORMATIONS_UPDATED,
            ResourceLifecycleChange.FETCH_STARTED,
            ResourceLifecycleChange.FETCH_COMPLETED,
        ), observations.map { it.change })
        assertEquals(1, observations.map { it.occurredAt }.distinct().size)
        assertEquals(observations.size, observations.map { it.resourceRevision }.distinct().size)
        assertEquals(observations.map { it.resourceRevision }.sorted(), observations.map { it.resourceRevision })

        // A new association changes both endpoints' observable association set.
        val pageResource = requireNotNull(registry.snapshot(graph.id))
            .nodes.single { it.legacyUri == page }
        assertTrue(pageResource.revision > 1L)
        assertEquals(observations.last().resourceRevision, registry.snapshot(graph.id)!!.nodes
            .single { it.legacyUri == child }.revision)
    }

    @Test
    fun `resource event payloads are immutable snapshots through typed and generic access`() {
        val registry = NavigationResourceRegistry()
        val graph = registry.startNavigation(URI("http://legacy.test/root"))
        val resource = URI("http://legacy.test/image.jpg")
        val upstream = URI("https://legacy.test/image.jpg")
        registry.discover(
            graph,
            graph.rootLegacyUri,
            resource,
            upstream,
            ResourceRelation.IMG_SRC,
            ResourceKind.IMAGE,
        )
        val attempt = requireNotNull(registry.beginFetch(graph, resource, upstream, upstream, prefetched = true))
        registry.markFetchState(graph, attempt, resource, upstream, upstream, ResourceState.SOURCE_READY)
        registry.markFetchState(graph, attempt, resource, upstream, upstream, ResourceState.TRANSFORMING)

        val original = ResourceTransformationSummary("jpeg-optimizer", 1_000, 250, "reduced")
        val callerOwned = mutableListOf(original)
        registry.recordTransformations(resource, callerOwned)
        val event = observedEvents().filterIsInstance<ResourceLifecycleEvent>()
            .last { it.change == ResourceLifecycleChange.TRANSFORMATIONS_UPDATED }

        val later = ResourceTransformationSummary("later", 250, 200)
        callerOwned += later
        registry.recordTransformations(resource, listOf(later))

        assertEquals(listOf(original), event.transformations)
        assertEquals(listOf(original), event["transformations"])
        assertEquals(ResourceState.TRANSFORMING, event.state)
        assertTrue(event.prefetched)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (event["transformations"] as MutableList<ResourceTransformationSummary>) +=
                ResourceTransformationSummary("mutation", 1, 0)
        }
        assertEquals(listOf(original), event.transformations)
        assertEquals(listOf(later),
            graph.snapshot().nodes.single { it.legacyUri == resource }.transformations)
    }

    @Test
    fun `shared resources remain associated with each graph and eviction starts a new graph lifetime`() {
        val registry = NavigationResourceRegistry(maxContexts = 2)
        val firstRoot = URI("http://first.test/page")
        val secondRoot = URI("http://second.test/page")
        val shared = URI("http://cdn.test/shared.css")
        val upstream = URI("https://cdn.test/shared.css")
        val first = registry.startNavigation(firstRoot)
        registry.discover(first, firstRoot, shared, upstream, ResourceRelation.LINK_STYLESHEET, ResourceKind.STYLESHEET)
        val second = registry.startNavigation(secondRoot)
        registry.discover(second, secondRoot, shared, upstream, ResourceRelation.LINK_STYLESHEET, ResourceKind.STYLESHEET)

        val sharedEvents = observedEvents().filterIsInstance<ResourceLifecycleEvent>()
            .filter { it.change == ResourceLifecycleChange.DISCOVERED && it.canonicalResourceKey == "http://cdn.test/shared.css" }
        assertEquals(setOf(first.id, second.id), sharedEvents.map { it.graphId }.toSet())
        assertEquals(
            setOf(URI("http://first.test/"), URI("http://second.test/")),
            sharedEvents.map { it.rootLegacyUri }.toSet(),
        )

        val third = registry.startNavigation(URI("http://third.test/page"))
        assertTrue(third.id != first.id && third.id != second.id)
        val removedGraph = observedEvents().filterIsInstance<ResourceGraphLifecycleEvent>()
            .first { it.change == ResourceGraphChange.REMOVED }
        assertEquals(first.id, removedGraph.graphId)
        assertEquals(URI("http://first.test/"), removedGraph.rootLegacyUri)
        val removedShared = observedEvents().filterIsInstance<ResourceLifecycleEvent>()
            .first { it.change == ResourceLifecycleChange.REMOVED && it.graphId == first.id && it.resourceLegacyUri == shared }
        assertEquals(listOf(ResourceEdgeSnapshot(firstRoot, shared, ResourceRelation.LINK_STYLESHEET)), removedShared.associations)

        val recreated = registry.startNavigation(firstRoot)
        assertTrue(recreated.id != first.id)
        assertNotNull(observedEvents().filterIsInstance<ResourceGraphLifecycleEvent>().first {
            it.change == ResourceGraphChange.CREATED && it.rootLegacyUri == URI("http://first.test/")
        })
    }

    @Test
    fun `consumer projection buffers resource before root and rejects updates after eviction`() {
        val registry = NavigationResourceRegistry(maxContexts = 1)
        val projection = ReorderedProjection()
        val root = URI("http://legacy.test/page")
        val graph = registry.startNavigation(root)
        val admitted = observedEvents().filterIsInstance<ResourceLifecycleEvent>().first()
        val created = observedEvents().filterIsInstance<ResourceGraphLifecycleEvent>()
            .first { it.change == ResourceGraphChange.CREATED }

        projection.accept(admitted)
        assertNull(projection.graph(graph.id))
        assertEquals(1, projection.pendingResourceCount)
        projection.accept(created)
        assertEquals(URI("http://legacy.test/"), projection.graph(graph.id)?.rootLegacyUri)
        assertTrue(projection.graph(graph.id)?.resources?.containsKey("http://legacy.test/page") == true)

        val resource = URI("http://legacy.test/image.gif")
        val upstream = URI("https://legacy.test/image.gif")
        registry.discover(graph, root, resource, upstream, ResourceRelation.IMG_SRC, ResourceKind.IMAGE)
        val attempt = requireNotNull(registry.beginFetch(graph, resource, upstream, upstream))
        registry.markFetchState(graph, attempt, resource, upstream, upstream, ResourceState.READY)
        val staleUpdate = observedEvents().filterIsInstance<ResourceLifecycleEvent>()
            .last { it.graphId == graph.id && it.change == ResourceLifecycleChange.FETCH_COMPLETED }
        observedEvents().filterIsInstance<ResourceLifecycleEvent>().filter { it.graphId == graph.id }
            .forEach(projection::accept)
        assertEquals(ResourceState.READY, projection.graph(graph.id)?.resources?.get(staleUpdate.canonicalResourceKey)?.state)

        registry.startNavigation(URI("http://next.test/page"))
        val removed = observedEvents().filterIsInstance<ResourceGraphLifecycleEvent>()
            .first { it.graphId == graph.id && it.change == ResourceGraphChange.REMOVED }
        projection.accept(removed)
        projection.accept(staleUpdate)
        observedEvents().filterIsInstance<ResourceLifecycleEvent>()
            .filter { it.graphId == graph.id && it.change == ResourceLifecycleChange.REMOVED }
            .forEach(projection::accept)

        assertNull(projection.graph(graph.id))
        assertTrue(projection.wasRemoved(graph.id))
    }

    private class MutableClock(initial: Instant) : Clock() {
        @Volatile private var current = initial

        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advanceSeconds(seconds: Long) { current = current.plusSeconds(seconds) }
    }

    private class ReorderedProjection {
        data class Graph(
            val rootLegacyUri: URI,
            var usageCount: Long,
            var lastUsedAt: Instant?,
            val resources: MutableMap<String, ResourceLifecycleEvent> = linkedMapOf(),
        )

        private val graphs = mutableMapOf<Long, Graph>()
        private val retired = mutableSetOf<Long>()
        private val pending = mutableMapOf<Long, MutableList<ResourceLifecycleEvent>>()
        val pendingResourceCount: Int get() = pending.values.sumOf { it.size }

        fun graph(graphId: Long): Graph? = graphs[graphId]
        fun wasRemoved(graphId: Long): Boolean = graphId in retired

        fun accept(event: YameEvent) {
            when (event) {
                is ResourceGraphLifecycleEvent -> acceptGraph(event)
                is ResourceLifecycleEvent -> acceptResource(event)
            }
        }

        private fun acceptGraph(event: ResourceGraphLifecycleEvent) {
            if (event.change == ResourceGraphChange.REMOVED) {
                retired += event.graphId
                graphs.remove(event.graphId)
                pending.remove(event.graphId)
                return
            }
            if (event.graphId in retired) return
            val graph = graphs.getOrPut(event.graphId) {
                Graph(event.rootLegacyUri, event.usageCount, event.lastUsedAt)
            }
            graph.usageCount = event.usageCount
            graph.lastUsedAt = event.lastUsedAt
            pending.remove(event.graphId).orEmpty().forEach { applyResource(graph, it) }
        }

        private fun acceptResource(event: ResourceLifecycleEvent) {
            if (event.graphId in retired) return
            val graph = graphs[event.graphId]
            if (graph == null) {
                if (pendingResourceCount >= 32) return
                val events = pending.getOrPut(event.graphId) { mutableListOf() }
                events += event
                return
            }
            applyResource(graph, event)
        }

        private fun applyResource(graph: Graph, event: ResourceLifecycleEvent) {
            val current = graph.resources[event.canonicalResourceKey]
            if (event.change == ResourceLifecycleChange.REMOVED) {
                graph.resources.remove(event.canonicalResourceKey)
                return
            }
            if (current != null) {
                val oldAttempt = current.fetchAttemptNumber
                val newAttempt = event.fetchAttemptNumber
                if (oldAttempt != null && newAttempt != null && newAttempt < oldAttempt) return
                if (oldAttempt == newAttempt && stateRank(event.state) < stateRank(current.state)) return
            }
            graph.resources[event.canonicalResourceKey] = event
        }

        private fun stateRank(state: ResourceState): Int =
            when (state) {
                ResourceState.DISCOVERED -> 0
                ResourceState.FETCHING -> 1
                ResourceState.SOURCE_READY -> 2
                ResourceState.TRANSFORMING -> 3
                ResourceState.READY, ResourceState.FAILED -> 4
            }
    }
}
