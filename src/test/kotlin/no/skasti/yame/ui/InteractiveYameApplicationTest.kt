package no.skasti.yame.ui

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.observer.*
import no.skasti.yame.config.ConfigStore
import no.skasti.yame.ppp.proxy.*
import no.skasti.yame.ppp.proxy.transform.ResourceTransformationSummary
import no.skasti.yame.recording.RecordingResourceView
import java.net.URI
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class InteractiveYameApplicationTest {
    private lateinit var broker: EventBroker
    private lateinit var installation: AutoCloseable
    private val timeout = Duration.ofSeconds(5)
    @BeforeTest fun install() {
        broker = EventBroker()
        installation = YameEvents.install(broker)
    }
    @AfterTest fun release() {
        broker.close()
        assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(timeout))
        installation.close()
    }
    private fun drain() {
        assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(timeout))
        assertEquals(0L, broker.status.subscriberFailures, "subscriber failure: ${broker.status.lastSubscriberFailure}")
        assertNull(broker.status.failure)
    }

    private fun withApp(test: (InteractiveYameApplication) -> Unit) =
        InteractiveYameApplication(
            configStore = ConfigStore(listOf(no.skasti.yame.serial.SerialSettings.Port to "COM1")),
            functions = FunctionStore(), terminal = Terminal(interactive = false),
        ).use(test)

    @Test fun `early independent consumer matches complete TUI graph and host views from published events`() {
        val clock = ControlledClock()
        val registry = NavigationResourceRegistry(maxContexts = 2, clock = clock)
        val recorder = RecordingResourceView()
        val published = mutableListOf<YameEvent>()
        broker.subscribe("independent-view") { event -> published += event; recorder.accept(event) }.use {
            withApp { app ->
                val root = URI("http://alpha.test/page")
                val image = URI("http://cdn.test/shared.gif")
                val upstream = URI("https://cdn.test/shared.gif")
                val first = registry.startNavigation(root)
                clock.at(1)
                registry.discover(first, root, image, upstream, ResourceRelation.IMG_SRC, ResourceKind.IMAGE)
                clock.at(2)
                registry.discover(first, root, image, upstream, ResourceRelation.IMG_SRC, ResourceKind.IMAGE)
                clock.at(3)
                registry.discover(first, URI("http://alpha.test/frame"), image, upstream,
                    ResourceRelation.CSS_URL, ResourceKind.IMAGE)
                clock.at(4)
                val attempt = requireNotNull(registry.beginFetch(first, image, upstream, upstream, prefetched = true))
                clock.at(5)
                registry.recordTransformations(image, listOf(ResourceTransformationSummary("first", 100, 25)))
                clock.at(6)
                assertTrue(registry.markFetchState(first, attempt, image, upstream, upstream, ResourceState.READY))
                drain()
                assertEquivalentViews(recorder, app)
                assertEquals(4L, recorder.graphs.getValue(first.id).usageCount)
                assertEquals(clock.time(3), recorder.graphs.getValue(first.id).lastUsedAt)
                assertEquals(setOf(
                    ResourceEdgeSnapshot(root, image, ResourceRelation.IMG_SRC),
                    ResourceEdgeSnapshot(URI("http://alpha.test/frame"), image, ResourceRelation.CSS_URL),
                ), recorder.graphs.getValue(first.id).associations)

                // A pure reuse emits USED without resource changes: ignoring USED must fail this checkpoint.
                val beforeReuse = published.size
                clock.at(8)
                assertEquals(first.id, registry.startNavigation(root).id)
                drain()
                assertEquals(listOf(ResourceGraphChange.USED), published.drop(beforeReuse)
                    .filterIsInstance<ResourceGraphLifecycleEvent>().map { it.change })
                assertEquals(5L, recorder.graphs.getValue(first.id).usageCount)
                assertEquals(clock.time(8), recorder.graphs.getValue(first.id).lastUsedAt)
                assertEquivalentViews(recorder, app)

                clock.at(9)
                val second = registry.startNavigation(URI("http://beta.test/page"))
                clock.at(10)
                registry.discover(second, second.rootLegacyUri, image, upstream, ResourceRelation.IMG_SRC, ResourceKind.IMAGE)
                clock.at(11)
                val extra = URI("http://cdn.test/extra.gif")
                registry.discover(second, second.rootLegacyUri, extra, URI("https://cdn.test/extra.gif"),
                    ResourceRelation.IMG_SRC, ResourceKind.IMAGE)
                drain()
                assertEquivalentViews(recorder, app)
                assertEquals(listOf("cdn.test", "beta.test", "alpha.test"), recorder.hosts().map { it.name })
                assertEquals(8L, recorder.hosts().first().usageCount) // Each graph once, not each CDN member.
                assertEquals(2, recorder.hosts().first().resources.size)

                // Equal recency exercises usage as the second sort key. Alpha has more uses than beta.
                assertEquals(first.id, registry.startNavigation(root).id)
                clock.at(12)
                val secondAttempt = requireNotNull(registry.beginFetch(second, image, upstream, upstream, prefetched = true))
                clock.at(13)
                registry.recordTransformations(image, listOf(ResourceTransformationSummary("shared", 100, 20)))
                clock.at(14)
                assertTrue(registry.markFetchState(second, secondAttempt, image, upstream, upstream, ResourceState.READY))
                drain()
                assertEquivalentViews(recorder, app)
                assertEquals(listOf("cdn.test", "alpha.test", "beta.test"), recorder.hosts().map { it.name })
                assertEquals(listOf(9L, 6L, 3L), recorder.hosts().map { it.usageCount })
                assertTrue(recorder.hosts().all { it.lastUsedAt == clock.time(11) })
                assertEquals(ResourceState.READY, recorder.hosts().first().resources.getValue(image.toString()).state)
                assertTrue(recorder.hosts().first().resources.getValue(image.toString()).prefetched)

                val expectedUsage = recorder.graphs.mapValues { (_, graph) -> graph.usageCount to graph.lastUsedAt }
                val expectedHosts = recorder.hosts()
                // Redeliver real observations, including old CREATED/USED/resource payloads.
                published.toList().forEach { YameEvents.publish(it) }
                drain()
                assertEquivalentViews(recorder, app)
                assertEquals(expectedUsage, recorder.graphs.mapValues { (_, graph) -> graph.usageCount to graph.lastUsedAt })
                assertEquals(expectedHosts, recorder.hosts())

                clock.at(15)
                val third = registry.startNavigation(URI("http://gamma.test/page"))
                drain()
                assertEquivalentViews(recorder, app)
                assertEquals(setOf(second.id, third.id), recorder.graphs.keys)
                assertEquals(listOf("gamma.test", "beta.test", "cdn.test"), recorder.hosts().map { it.name })
                assertEquals(3L, recorder.hosts().single { it.name == "cdn.test" }.usageCount)

                clock.at(16)
                val recreated = registry.startNavigation(root)
                drain()
                assertEquivalentViews(recorder, app)
                assertNotEquals(first.id, recreated.id)
                assertEquals(setOf(third.id, recreated.id), recorder.graphs.keys)
                assertEquals(URI("http://alpha.test/"), recorder.graphs.getValue(recreated.id).rootUri)
                assertEquals(1L, recorder.graphs.getValue(recreated.id).usageCount)
                assertEquals(clock.time(16), recorder.graphs.getValue(recreated.id).lastUsedAt)
                assertEquals(listOf("alpha.test", "gamma.test"), recorder.hosts().map { it.name })
                published.toList().forEach { YameEvents.publish(it) }
                drain()
                assertEquivalentViews(recorder, app)
                assertEquals(setOf(third.id, recreated.id), recorder.graphs.keys)
                assertEquals(listOf(1L, 1L), recorder.hosts().map { it.usageCount })
            }
        }
    }

    @Test fun `public resource removal cleans all incident associations without removing shared host survivors`() {
        val recorder = RecordingResourceView()
        broker.subscribe("independent-removal", handler = recorder::accept).use {
            withApp { app ->
                val clock = ControlledClock()
                val root = URI("http://removal.test/")
                val removed = URI("http://cdn.test/removed.gif")
                val kept = URI("http://cdn.test/kept.gif")
                val incoming = ResourceEdgeSnapshot(root, removed, ResourceRelation.IMG_SRC)
                val otherParent = ResourceEdgeSnapshot(URI("http://removal.test/frame"), removed, ResourceRelation.IMG_SRC)
                val outgoing = ResourceEdgeSnapshot(removed, kept, ResourceRelation.CSS_URL)
                val surviving = ResourceEdgeSnapshot(root, kept, ResourceRelation.IMG_SRC)
                fun resource(uri: URI, at: Long, edges: List<ResourceEdgeSnapshot>,
                             change: ResourceLifecycleChange = ResourceLifecycleChange.DISCOVERED) =
                    ResourceLifecycleEvent(change, 101, root, uri.toString(), uri, null,
                        ReferenceRole.SUBRESOURCE, ResourceKind.IMAGE, null, ResourceState.DISCOVERED,
                        false, null, null, edges, emptyList(), 4, clock.time(0), clock.time(at))
                // Individual node eviction has no registry API today: exercise its public removal contract explicitly.
                val created = ResourceGraphLifecycleEvent(ResourceGraphChange.CREATED, 101, root, clock.time(0), 1, clock.time(0))
                val rootResource = resource(root, 1, listOf(incoming, surviving))
                val removedResource = resource(removed, 2, listOf(incoming, otherParent, outgoing))
                val keptResource = resource(kept, 3, listOf(outgoing, surviving))
                listOf(created, rootResource, removedResource, keptResource).forEach { YameEvents.publish(it) }
                drain()
                assertEquivalentViews(recorder, app)
                assertEquals(setOf(incoming, otherParent, outgoing, surviving), recorder.graphs.getValue(101).associations)
                val deletion = resource(removed, 4, removedResource.associations, ResourceLifecycleChange.REMOVED)
                listOf(deletion, deletion, removedResource, keptResource).forEach { YameEvents.publish(it) }
                drain()
                assertEquivalentViews(recorder, app)
                assertEquals(setOf(root.toString(), kept.toString()), recorder.graphs.getValue(101).resources.keys)
                assertEquals(setOf(surviving), recorder.graphs.getValue(101).associations)
                assertEquals(4L, recorder.graphs.getValue(101).usageCount)
                assertEquals(listOf("cdn.test", "removal.test"), recorder.hosts().map { it.name }) // Name breaks equal usage/time.
                YameEvents.publish(ResourceGraphLifecycleEvent(ResourceGraphChange.REMOVED, 101, root,
                    clock.time(5), 4, clock.time(0)))
                YameEvents.publish(created)
                YameEvents.publish(keptResource)
                drain()
                assertEquivalentViews(recorder, app)
                assertTrue(recorder.graphs.isEmpty())
                assertTrue(recorder.hosts().isEmpty())
            }
        }
    }

    private fun assertEquivalentViews(recorder: RecordingResourceView, app: InteractiveYameApplication) {
        val resources = app.resourceGraphProjectionSnapshot()
        val metadata = app.resourceGraphMetadataSnapshot()
        assertEquals(recorder.graphs.keys, resources.keys)
        assertEquals(recorder.graphs.keys, metadata.keys)
        recorder.graphs.forEach { (id, graph) ->
            val actual = metadata.getValue(id)
            assertEquals(id, graph.id)
            assertEquals(graph.rootUri, actual.rootUri)
            assertEquals(graph.usageCount, actual.usageCount, "graph $id usage")
            assertEquals(graph.lastUsedAt, actual.lastUsedAt, "graph $id recency")
            assertEquals(graph.associations, actual.associations, "graph $id associations")
            assertEquals(graph.resources.values.map { it.resourceLegacyUri }.toSet(), resources.getValue(id).keys)
            graph.resources.forEach { (key, event) ->
                assertEquals(key, event.canonicalResourceKey)
                assertEquals(id, event.graphId)
                assertEquals(graph.rootUri, event.rootLegacyUri)
                assertResource(event, resources.getValue(id).getValue(event.resourceLegacyUri))
            }
        }
        val expectedHosts = recorder.hosts()
        val actualHosts = app.httpHostProjectionSnapshot()
        assertEquals(expectedHosts.map { it.name }, actualHosts.map { it.host }, "authoritative host ordering")
        expectedHosts.zip(actualHosts).forEach { (expected, actual) ->
            assertEquals(expected.usageCount, actual.useCount, "host ${expected.name} usage")
            assertEquals(expected.lastUsedAt, actual.lastUsedAt, "host ${expected.name} recency")
            assertEquals(expected.resources.values.map { it.resourceLegacyUri.toString() }.toSet(),
                actual.resources.map { it.url }.toSet(), "host ${expected.name} unique resources")
            assertEquals(actual.resources.size, actual.resources.map { it.url }.distinct().size)
            expected.resources.values.forEach { event ->
                assertResource(event, actual.resources.single { it.url == event.resourceLegacyUri.toString() })
            }
        }
    }

    private fun assertResource(expected: ResourceLifecycleEvent, actual: DashboardHttpResource) {
        assertEquals(expected.resourceLegacyUri.toString(), actual.url)
        assertEquals(expected.state, actual.state)
        assertEquals(expected.kind, actual.kind)
        assertEquals(expected.prefetched, actual.prefetched)
        assertEquals(expected.transformations, actual.transformations)
    }

    private class ControlledClock : Clock() {
        private var current = time(0)
        fun time(seconds: Long): Instant = Instant.parse("2026-10-26T06:00:00Z").plusSeconds(seconds)
        fun at(seconds: Long) { current = time(seconds) }
        override fun instant(): Instant = current
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    @Test fun `intentionally late TUI receives no snapshot bootstrap`() {
        val registry = NavigationResourceRegistry()
        registry.startNavigation(URI("http://already-held.test/page"))
        drain()
        withApp { app ->
            drain()
            assertTrue(app.resourceGraphProjectionSnapshot().isEmpty())
            // Newly created graph lifetimes still project normally.
            val newGraph = registry.startNavigation(URI("http://new.test/page"))
            drain()
            assertEquals(setOf(newGraph.id), app.resourceGraphProjectionSnapshot().keys)
        }
    }

    @Test fun `already captured observation after close cannot update the TUI`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        broker.subscribe("pause-before-tui") {
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
        }.use {
            try {
                val registry = NavigationResourceRegistry()
                withApp { app ->
                    registry.startNavigation(URI("http://tui-shutdown.test/page"))
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    app.close()
                    resume.countDown()
                    drain()
                    assertTrue(app.resourceGraphProjectionSnapshot().isEmpty())
                }
            } finally { resume.countDown() }
        }
    }
}
