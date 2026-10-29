package no.skasti.yame.ui

import no.skasti.yame.config.InstalledConfigTest
import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.observer.*
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import no.skasti.yame.config.ConfigStore
import no.skasti.yame.ppp.proxy.*
import no.skasti.yame.ppp.proxy.transform.ResourceTransformationSummary
import no.skasti.yame.ppp.session.PppSessionEnded
import no.skasti.yame.ppp.session.PppSessionIdentity
import no.skasti.yame.serial.ConnectionState
import no.skasti.yame.serial.ConnectionStateChanged
import java.net.URI
import java.time.Instant
import kotlin.test.*

class HttpGraphProjectionTest : InstalledConfigTest() {
    private val time = Instant.parse("2026-10-25T12:00:00Z")
    private val root = URI("http://site.test/")
    private val child = URI("http://cdn.test/image.gif")
    private val edge = ResourceEdgeSnapshot(root, child, ResourceRelation.IMG_SRC)
    private fun graph(id: Long = 1, change: ResourceGraphChange = ResourceGraphChange.CREATED,
                      count: Long = 1, at: Instant = time, uri: URI = root,
                      initial: List<ResourceLifecycleEvent> = emptyList()) =
        ResourceGraphLifecycleEvent(change, id, uri, at, count, at, initial)
    private fun resource(id: Long = 1, change: ResourceLifecycleChange = ResourceLifecycleChange.DISCOVERED,
                         state: ResourceState = ResourceState.DISCOVERED, attempt: Long? = null,
                         at: Instant = time, uri: URI = child, rootUri: URI = root,
                         count: Long = 1, usedAt: Instant = time,
                         kind: ResourceKind = ResourceKind.IMAGE, prefetch: Boolean = false,
                         edges: List<ResourceEdgeSnapshot> = listOf(edge),
                         transforms: List<ResourceTransformationSummary> = emptyList(),
                         revision: Long = 1L) =
        ResourceLifecycleEvent(change, id, rootUri, LegacyHttpUrl.requestObservableKey(uri), uri,
            URI("https://${uri.host}${uri.path}"), ReferenceRole.SUBRESOURCE, kind, null,
            state, prefetch, attempt, attempt, edges, transforms, count, usedAt, at, revision)
    private fun observer() = run { installConfig(ConfigStore()); TuiYameObserver(FunctionStore(), Terminal(interactive = false)) }

    @Test fun `resource and use before creation are deferred and duplicate creation is incremental`() {
        val projection = HttpGraphProjection()
        val initial = resource(uri = root, edges = emptyList(), kind = ResourceKind.DOCUMENT)
        val discovered = resource()
        projection.accept(discovered)
        projection.accept(graph(change = ResourceGraphChange.USED, count = 2, at = time.plusSeconds(2)))
        assertTrue(projection.graphSnapshot().isEmpty())
        assertEquals(2, projection.pendingCount)
        assertContains(projection.status!!, "incomplete")
        val created = graph(initial = listOf(initial))
        projection.accept(created)
        val retained = projection.graphSnapshot().getValue(1)
        projection.accept(created)
        projection.accept(discovered)
        assertSame(retained, projection.graphSnapshot().getValue(1))
        assertEquals(listOf(root.toString(), child.toString()), retained.resources.keys.toList())
        assertEquals(setOf(edge), retained.associations)
        assertEquals(2, retained.usageCount)
        assertEquals(time.plusSeconds(2), retained.lastUsedAt)
        assertEquals(0, projection.pendingCount)
        assertNull(projection.status)
    }

    @Test fun `attempt and state semantics reject stale fetch observations even when clocks regress`() {
        val projection = HttpGraphProjection()
        projection.accept(graph())
        val ready = resource(state = ResourceState.READY, attempt = 1, at = time.plusSeconds(3), revision = 3)
        projection.accept(ready)
        projection.accept(resource(state = ResourceState.FETCHING, attempt = 1, at = time.plusSeconds(4), revision = 2))
        assertSame(ready, projection.graphSnapshot().getValue(1).resources.getValue(child.toString()))
        val retry = resource(change = ResourceLifecycleChange.FETCH_RETRIED,
            state = ResourceState.FETCHING, attempt = 2, at = time.minusSeconds(1), prefetch = true, revision = 4)
        projection.accept(retry)
        projection.accept(ready)
        assertSame(retry, projection.graphSnapshot().getValue(1).resources.getValue(child.toString()))
        val transformed = resource(state = ResourceState.READY, attempt = 2, at = time,
            prefetch = true, transforms = listOf(ResourceTransformationSummary("test", 100, 25)),
            revision = 5)
        projection.accept(transformed)
        assertEquals(75, projection.graphSnapshot().getValue(1).resources.getValue(child.toString())
            .transformations.single().savedBytes)
    }

    @Test fun `metadata updates do not regress state and older same-state details cannot overwrite newer ones`() {
        val projection = HttpGraphProjection()
        projection.accept(graph())
        projection.accept(resource(state = ResourceState.READY, attempt = 1, revision = 2))
        val latest = resource(change = ResourceLifecycleChange.KIND_UPDATED, state = ResourceState.READY,
            attempt = 1, kind = ResourceKind.DOCUMENT, at = time.plusSeconds(2), revision = 3,
            transforms = listOf(ResourceTransformationSummary("rewrite", 100, 50)))
        projection.accept(latest)
        projection.accept(resource(state = ResourceState.READY, attempt = 1, at = time.plusSeconds(1), revision = 2))
        projection.accept(resource(state = ResourceState.DISCOVERED))
        assertSame(latest, projection.graphSnapshot().getValue(1).resources.getValue(child.toString()))
    }

    @Test fun `higher resource revisions preserve newer associations across clock adjustments`() {
        val projection = HttpGraphProjection()
        projection.accept(graph())
        val first = resource(at = time.plusSeconds(1), revision = 2)
        val secondEdge = ResourceEdgeSnapshot(root, child, ResourceRelation.CSS_URL)
        val expanded = resource(at = time.minusSeconds(1), edges = listOf(edge, secondEdge), revision = 3)
        projection.accept(first)
        projection.accept(expanded)
        projection.accept(first)
        val graph = projection.graphSnapshot().getValue(1)
        assertSame(expanded, graph.resources.values.single())
        assertEquals(setOf(edge, secondEdge), graph.associations)
    }

    @Test fun `stale observations cannot introduce edges absent from a newer resource snapshot`() {
        val projection = HttpGraphProjection()
        projection.accept(graph())
        val staleEdge = ResourceEdgeSnapshot(root, child, ResourceRelation.CSS_URL)
        val newest = resource(state = ResourceState.READY, attempt = 1, revision = 4,
            edges = listOf(edge), count = 2, usedAt = time.plusSeconds(1))
        val stale = resource(state = ResourceState.DISCOVERED, revision = 3,
            at = time.plusSeconds(10), edges = listOf(edge, staleEdge),
            count = 3, usedAt = time.plusSeconds(2))

        projection.accept(newest)
        projection.accept(stale)
        val state = projection.graphSnapshot().getValue(1)
        assertSame(newest, state.resources.getValue(child.toString()))
        assertEquals(setOf(edge), state.associations)
        // Graph usage is authoritative and independent of the resource revision.
        assertEquals(3L, state.usageCount)
        assertEquals(time.plusSeconds(2), state.lastUsedAt)

        val accepted = resource(state = ResourceState.READY, attempt = 1,
            revision = 5, edges = listOf(edge, staleEdge))
        projection.accept(accepted)
        assertSame(accepted, state.resources.getValue(child.toString()))
        assertEquals(setOf(edge, staleEdge), state.associations)
        assertNull(projection.failure)
    }

    @Test fun `shared associations survive stale updates and removal of another resource`() {
        val projection = HttpGraphProjection()
        projection.accept(graph())
        val sibling = URI("http://cdn.test/second.gif")
        val siblingEdge = ResourceEdgeSnapshot(root, sibling, ResourceRelation.IMG_SRC)
        val staleEdge = ResourceEdgeSnapshot(root, child, ResourceRelation.CSS_URL)

        projection.accept(resource(uri = root, kind = ResourceKind.DOCUMENT,
            edges = listOf(edge, siblingEdge), revision = 4))
        projection.accept(resource(uri = child, edges = listOf(edge), revision = 2))
        projection.accept(resource(uri = sibling, edges = listOf(siblingEdge), revision = 2))
        // A delayed root snapshot must not add an obsolete association.
        projection.accept(resource(uri = root, kind = ResourceKind.DOCUMENT,
            edges = listOf(edge, siblingEdge, staleEdge), revision = 3))
        val state = projection.graphSnapshot().getValue(1)
        assertEquals(setOf(edge, siblingEdge), state.associations)

        projection.accept(resource(uri = child, change = ResourceLifecycleChange.REMOVED,
            edges = listOf(edge), revision = 5))
        assertEquals(setOf(siblingEdge), state.associations)
        // Newer root snapshots can still mention a removed endpoint, but the
        // tombstone prevents that edge from reappearing.
        projection.accept(resource(uri = root, kind = ResourceKind.DOCUMENT,
            edges = listOf(edge, siblingEdge), revision = 6))
        assertEquals(setOf(siblingEdge), state.associations)
        assertEquals(setOf(root.toString(), sibling.toString()), state.resources.keys)
        assertNull(projection.failure)
    }

    @Test fun `removal dominates delayed updates creates and use regardless of occurrence time`() {
        val projection = HttpGraphProjection()
        projection.accept(resource())
        projection.accept(graph(change = ResourceGraphChange.REMOVED))
        projection.accept(graph())
        projection.accept(resource(state = ResourceState.READY, attempt = 1, at = time.plusSeconds(10)))
        projection.accept(graph(change = ResourceGraphChange.USED, count = 5))
        assertTrue(projection.graphSnapshot().isEmpty())
        assertEquals(0, projection.pendingCount)
        // Same URL, different graph lifetime.
        projection.accept(graph(id = 2))
        projection.accept(resource(id = 2))
        assertEquals(setOf(2L), projection.graphSnapshot().keys)
        assertEquals(ResourceState.DISCOVERED, projection.graphSnapshot().getValue(2).resources.values.single().state)
    }

    @Test fun `individual membership removal preserves other graphs and cannot be undone by delayed discovery`() {
        val projection = HttpGraphProjection()
        projection.accept(graph())
        projection.accept(graph(id = 2))
        projection.accept(resource())
        projection.accept(resource(id = 2))
        projection.accept(resource(change = ResourceLifecycleChange.REMOVED))
        projection.accept(resource())
        assertTrue(projection.graphSnapshot().getValue(1).resources.isEmpty())
        assertTrue(projection.graphSnapshot().getValue(1).associations.isEmpty())
        assertEquals(1, projection.graphSnapshot().getValue(2).resources.size)
    }

    @Test fun `shared canonical URLs preserve associations and authoritative usage without event counting`() {
        observer().use { ui ->
            ui.onEvent(graph())
            ui.onEvent(resource(count = 5, usedAt = time.plusSeconds(5), at = time.plusSeconds(5)))
            repeat(10) { ui.onEvent(resource(count = 5, usedAt = time.plusSeconds(5), at = time.plusSeconds(5))) }
            val otherEdge = ResourceEdgeSnapshot(URI("http://site.test/frame"), child, ResourceRelation.CSS_URL)
            ui.onEvent(resource(change = ResourceLifecycleChange.ASSOCIATION_ADDED, count = 6,
                usedAt = time.plusSeconds(6), at = time.plusSeconds(6), edges = listOf(edge, otherEdge),
                revision = 2))
            ui.onEvent(graph(id = 2, uri = URI("http://other.test/"), count = 2, at = time.plusSeconds(4)))
            ui.onEvent(resource(id = 2, rootUri = URI("http://other.test/"), count = 2,
                usedAt = time.plusSeconds(4), at = time.plusSeconds(4), uri = URI("http://CDN.test:80/image.gif#x")))
            val host = ui.httpHostProjectionSnapshot().single()
            assertEquals(8, host.useCount)
            assertEquals(time.plusSeconds(6), host.lastUsedAt)
            assertEquals(listOf(child.toString()), host.resources.map { it.url })
            ui.onEvent(graph(change = ResourceGraphChange.USED, count = 7, at = time.minusSeconds(1)))
            // Higher use count wins even when last-use wall time moves backwards.
            assertEquals(9, ui.httpHostProjectionSnapshot().single().useCount)
        }
    }

    @Test fun `selection expansion recency and resource identity survive incremental updates and reconnect`() {
        observer().use { ui ->
            ui.onEvent(graph())
            ui.onEvent(resource())
            ui.handleKeyAndAwait("Enter")
            ui.handleKeyAndAwait("ArrowDown")
            ui.onEvent(ConnectionStateChanged(1, ConnectionState.CONNECTED))
            ui.onEvent(PppSessionEnded(PppSessionIdentity(1, 1)))
            ui.onEvent(ConnectionStateChanged(1, ConnectionState.DISCONNECTED))
            ui.onEvent(ConnectionStateChanged(2, ConnectionState.CONNECTED))
            ui.onEvent(resource(state = ResourceState.READY, attempt = 1, prefetch = true,
                at = time.plusSeconds(1), revision = 2))
            val host = ui.httpHostProjectionSnapshot().single()
            assertTrue(host.expanded)
            assertEquals(child.toString(), ui.httpSelectionSnapshot().second)
            assertEquals(ResourceState.READY, host.resources.single().state)
            assertTrue(host.resources.single().prefetched)
            ui.onEvent(graph(id = 2, uri = URI("http://other.test/"), count = 1, at = time.plusSeconds(2)))
            ui.onEvent(resource(id = 2, rootUri = URI("http://other.test/"), uri = URI("http://new.test/page"),
                at = time.plusSeconds(2), usedAt = time.plusSeconds(2)))
            assertEquals(listOf("new.test", "cdn.test"), ui.httpHostProjectionSnapshot().map { it.host })
            assertEquals("cdn.test", ui.httpSelectionSnapshot().first)
        }
    }

    @Test fun `unresolved dependencies fail visibly at the bound and stop displaying an invalid proxy view`() {
        val ui = observer()
        repeat(256) { ui.onEvent(resource(id = it + 1L)) }
        assertContains(ui.proxyProjectionStatus()!!, "incomplete")
        ui.onEvent(resource(id = 257))
        assertFailsWith<IllegalStateException> { ui.close() }
        run {
            assertNotNull(ui.observationFailure)
            assertNotNull(ui.fatalFailure)
            assertContains(ui.proxyProjectionStatus()!!, "unavailable")
            ui.onEvent(graph())
            assertTrue(ui.resourceGraphProjectionSnapshot().isEmpty())
        }
    }

    @Test fun `conflicting equal-time values expose ambiguity instead of depending on delivery order`() {
        val projection = HttpGraphProjection()
        projection.accept(graph())
        projection.accept(resource())
        assertFailsWith<IllegalStateException> { projection.accept(resource(kind = ResourceKind.SCRIPT)) }
        assertNotNull(projection.failure)
        assertTrue(projection.graphSnapshot().isEmpty())
    }

    @Test fun `resource revision orders rapid metadata changes regardless of tied or regressing clocks`() {
        val projection = HttpGraphProjection()
        projection.accept(graph())
        val original = resource(revision = 1)
        val updated = resource(change = ResourceLifecycleChange.KIND_UPDATED,
            kind = ResourceKind.SCRIPT, revision = 2)
        val transformed = resource(change = ResourceLifecycleChange.TRANSFORMATIONS_UPDATED,
            kind = ResourceKind.SCRIPT, revision = 3, at = time.minusSeconds(3),
            transforms = listOf(ResourceTransformationSummary("rewrite", 40, 20)))

        // A newer revision may arrive first, and its occurrence time may be older.
        projection.accept(transformed)
        projection.accept(original)
        projection.accept(updated)
        val actual = projection.graphSnapshot().getValue(1).resources.getValue(child.toString())
        assertSame(transformed, actual)
        assertNull(projection.failure)
    }

    @Test fun `equal resource revision permits duplicate snapshots with different timestamps and graph usage`() {
        val projection = HttpGraphProjection()
        projection.accept(graph())
        val first = resource(revision = 5, at = time, count = 1)
        val duplicate = resource(revision = 5, at = time.plusSeconds(1), count = 2,
            usedAt = time.plusSeconds(1))
        projection.accept(first)
        projection.accept(duplicate)
        assertEquals(5, projection.graphSnapshot().getValue(1).resources.getValue(child.toString()).resourceRevision)
        assertEquals(2, projection.graphSnapshot().getValue(1).usageCount)
        assertNull(projection.failure)
    }

    @Test fun `observer monitor ownership cannot block broker admission or presentation processing`() {
        val broker = testEventBroker(capacity = 1, publishTimeout = Duration.ZERO)
        val locked = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val callbackThread = AtomicReference<Thread>()
        observer().use { ui ->
            val holder = Thread {
                synchronized(ui) { locked.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
            }.apply { start() }
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS))
                broker.subscribe("proxy-tui") {
                    callbackThread.set(Thread.currentThread())
                    entered.countDown()
                    ui.onEvent(it)
                }
                broker.publish(graph())
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertNotSame(Thread.currentThread(), callbackThread.get())
                assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(Duration.ofSeconds(2)))
                broker.publish(resource())
                assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(Duration.ofSeconds(2)))
                assertNull(broker.status.failure)
                assertEquals(1, ui.resourceGraphProjectionSnapshot().size)
            } finally {
                resume.countDown()
                holder.join(5_000)
                broker.close()
                assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(Duration.ofSeconds(5)))
            }
        }
    }

    @Test fun `incomplete and unavailable proxy status are visible in both dashboard layouts`() {
        val base = DashboardState(portName = null, baud = 115200, connected = false,
            dnsUpstream = "8.8.8.8", httpProxyEnabled = true, logs = emptyList(),
            dnsLookups = emptyList(), transfers = emptyList(), httpHosts = emptyList(),
            selectedHttpHostIndex = 0, commandPalette = null)
        for (status in listOf("Proxy view incomplete: waiting", "Proxy view unavailable: observation failure")) {
            for (width in listOf(100, 160)) {
                assertContains(YameDashboardRenderer.render(base.copy(proxyStatus = status), width, 30), status)
            }
        }
    }

    @Test fun `duplicate unresolved observations coalesce without exhausting deferral`() {
        val projection = HttpGraphProjection(maxPending = 1)
        repeat(1000) { projection.accept(resource()) }
        projection.accept(resource(state = ResourceState.READY, attempt = 1, revision = 3))
        projection.accept(resource(state = ResourceState.FETCHING, attempt = 1, revision = 2))
        assertEquals(1, projection.pendingCount)
        projection.accept(graph())
        assertEquals(ResourceState.READY, projection.graphSnapshot().getValue(1).resources.values.single().state)
        assertNull(projection.status)
    }

    @Test fun `contiguous lifetime tombstones compact without forgetting old evictions`() {
        val projection = HttpGraphProjection()
        repeat(10_000) { projection.accept(graph(id = it + 1L, change = ResourceGraphChange.REMOVED)) }
        projection.accept(graph())
        projection.accept(resource())
        assertTrue(projection.graphSnapshot().isEmpty())
        assertNull(projection.failure)
    }
}
