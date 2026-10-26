package no.skasti.yame.ppp.proxy
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.ppp.proxy.ReferenceRole
import no.skasti.yame.ppp.proxy.ResourceKind
import no.skasti.yame.ppp.proxy.ResourceRelation
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceGraphTest {
    @Test
    fun `resource registry instances remain independent`() {
        val first = NavigationResourceRegistry()
        val second = NavigationResourceRegistry()

        first.startNavigation(URI("http://independent-registry.test/page"))

        assertTrue(first !== second)
        assertEquals(1, first.snapshots().size)
        assertTrue(second.snapshots().isEmpty())
    }

    @Test
    fun `resource contexts use the resource host and port as the graph root`() {
        val registry = NavigationResourceRegistry()
        val first = URI("http://legacy.test:8080/first.gif")
        val second = URI("http://legacy.test:8080/second.gif")
        val differentPort = URI("http://legacy.test:8081/other.gif")

        val firstGraph = registry.ensureResourceContext(first)
        val secondGraph = registry.ensureResourceContext(second)
        val differentPortGraph = registry.ensureResourceContext(differentPort)

        assertEquals(firstGraph.id, secondGraph.id)
        assertEquals(URI("http://legacy.test:8080/"), firstGraph.rootLegacyUri)
        assertTrue(firstGraph.id != differentPortGraph.id)
        assertEquals(2, registry.snapshots().size)
        assertEquals(
            setOf(firstGraph.id),
            registry.contextsFor(first).map { it.id }.toSet(),
        )
    }

    @Test
    fun `resource graph deduplicates shared child nodes but keeps both edges`() {
        val registry = NavigationResourceRegistry()
        val graph = registry.startNavigation(URI("http://legacy.test/index.html"))
        val sharedLegacy = URI("http://cdn.test/shared.gif")
        val sharedUpstream = URI("https://cdn.test/shared.gif")

        registry.discover(
            graph,
            URI("http://legacy.test/index.html"),
            sharedLegacy,
            sharedUpstream,
            ResourceRelation.IMG_SRC,
            ResourceKind.IMAGE,
        )
        registry.discover(
            graph,
            URI("http://legacy.test/sidebar.html"),
            sharedLegacy,
            sharedUpstream,
            ResourceRelation.CSS_URL,
            ResourceKind.IMAGE,
        )

        val snapshot = graph.snapshot()
        assertEquals(3, snapshot.nodes.size)
        assertEquals(2, snapshot.edges.size)
        assertEquals(1, snapshot.nodes.count { it.legacyUri == sharedLegacy })
    }

    @Test
    fun `navigation relation wins when the same URL is also used as a subresource`() {
        val registry = NavigationResourceRegistry()
        val graph = registry.startNavigation(URI("http://legacy.test/index.html"))
        val targetLegacy = URI("http://target.test/page")
        val targetUpstream = URI("https://target.test/page")

        registry.discover(
            graph,
            graph.rootLegacyUri,
            targetLegacy,
            targetUpstream,
            ResourceRelation.FRAME_SRC,
            ResourceKind.FRAME,
        )
        registry.discover(
            graph,
            graph.rootLegacyUri,
            targetLegacy,
            targetUpstream,
            ResourceRelation.A_HREF,
            ResourceKind.DOCUMENT,
        )

        val node = graph.snapshot().nodes.single { it.legacyUri == targetLegacy }
        assertEquals(ReferenceRole.NAVIGATION, node.role)
        assertTrue(graph.snapshot().edges.any { it.relation == ResourceRelation.FRAME_SRC })
        assertTrue(graph.snapshot().edges.any { it.relation == ResourceRelation.A_HREF })
    }

    @Test
    fun `legacy URL association finds every navigation context that references a shared resource`() {
        val registry = NavigationResourceRegistry()
        val first = registry.startNavigation(URI("http://legacy-one.test/one.html"))
        val second = registry.startNavigation(URI("http://legacy-two.test/two.html"))
        val sharedLegacy = URI("http://cdn.test/site.css")
        val upstream = URI("https://cdn.test/site.css")

        registry.discover(first, first.rootLegacyUri, sharedLegacy, upstream, ResourceRelation.LINK_STYLESHEET, ResourceKind.STYLESHEET)
        registry.discover(second, second.rootLegacyUri, sharedLegacy, upstream, ResourceRelation.LINK_STYLESHEET, ResourceKind.STYLESHEET)

        assertEquals(setOf(first.id, second.id), registry.contextsFor(sharedLegacy).map { it.id }.toSet())
    }

    @Test
    fun `navigation roots are process persistent and reused on later visits`() {
        val registry = NavigationResourceRegistry()
        val root = URI("http://legacy.test/one")
        val shared = URI("http://cdn.test/old.gif")
        val first = registry.startNavigation(root)
        registry.discover(
            first,
            first.rootLegacyUri,
            shared,
            URI("https://cdn.test/old.gif"),
            ResourceRelation.IMG_SRC,
            ResourceKind.IMAGE,
        )

        // Visiting more resources on the same host must not create new graphs.
        repeat(100) { index ->
            registry.startNavigation(URI("http://legacy.test/page-$index"))
        }

        val revisited = registry.startNavigation(root)
        assertEquals(first.id, revisited.id)
        assertTrue(revisited.contains(shared))
        assertEquals(1, registry.snapshots().size)
        assertEquals(listOf(first.id), registry.contextsFor(shared).map { it.id })
    }

    @Test
    fun `registry evicts least recently used graph when global limit is reached`() {
        val registry = NavigationResourceRegistry(maxContexts = 2)
        val firstRoot = URI("http://legacy-one.test/one")
        val secondRoot = URI("http://legacy-two.test/two")
        val thirdRoot = URI("http://legacy-three.test/three")
        val first = registry.startNavigation(firstRoot)
        registry.startNavigation(secondRoot)

        // Reusing the first root makes the second graph the LRU candidate.
        assertEquals(first.id, registry.startNavigation(firstRoot).id)
        registry.startNavigation(thirdRoot)

        assertEquals(
            setOf(
                URI("http://legacy-one.test/"),
                URI("http://legacy-three.test/"),
            ),
            registry.snapshots().map { it.rootLegacyUri }.toSet(),
        )
    }

    @Test
    fun `evicted graph handle cannot be reindexed or mutated`() {
        val registry = NavigationResourceRegistry(maxContexts = 1)
        val first = registry.startNavigation(URI("http://legacy-one.test/one"))
        val staleChild = URI("http://legacy.test/stale.gif")
        registry.startNavigation(URI("http://legacy-two.test/two"))

        registry.discover(
            first,
            first.rootLegacyUri,
            staleChild,
            URI("https://legacy.test/stale.gif"),
            ResourceRelation.IMG_SRC,
            ResourceKind.IMAGE,
        )
        assertNull(
            registry.beginFetch(
                first,
                first.rootLegacyUri,
                URI("https://legacy.test/one"),
                URI("https://legacy.test/one"),
            ),
        )

        assertTrue(registry.contextsFor(staleChild).isEmpty())
        assertEquals(1, registry.snapshots().size)
        assertTrue(registry.snapshots().none { it.id == first.id })
    }

    @Test
    fun `stale fetch attempt cannot overwrite newer ready state`() {
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

        val older = requireNotNull(registry.beginFetch(graph, resource, upstream, upstream))
        val newer = requireNotNull(registry.beginFetch(graph, resource, upstream, upstream))

        assertTrue(
            registry.markFetchState(
                graph,
                newer,
                resource,
                upstream,
                upstream,
                ResourceState.READY,
            ),
        )
        assertTrue(
            !registry.markFetchState(
                graph,
                older,
                resource,
                upstream,
                upstream,
                ResourceState.FAILED,
            ),
        )

        val node = graph.snapshot().nodes.single { it.legacyUri == resource }
        assertEquals(ResourceState.READY, node.state)
    }

    @Test
    fun `host graph bounds nodes and edges and does not index dropped resources`() {
        val registry = NavigationResourceRegistry(
            maxNodesPerHost = 3,
            maxEdgesPerContext = 1,
        )
        val graph = registry.startNavigation(URI("http://legacy.test/root"))
        val kept = URI("http://legacy.test/kept.gif")
        val dropped = URI("http://legacy.test/dropped.gif")

        registry.discover(
            graph,
            graph.rootLegacyUri,
            kept,
            URI("https://legacy.test/kept.gif"),
            ResourceRelation.IMG_SRC,
            ResourceKind.IMAGE,
        )
        registry.discover(
            graph,
            graph.rootLegacyUri,
            dropped,
            URI("https://legacy.test/dropped.gif"),
            ResourceRelation.IMG_SRC,
            ResourceKind.IMAGE,
        )
        registry.discover(
            graph,
            URI("http://legacy.test/other-parent"),
            kept,
            URI("https://legacy.test/kept.gif"),
            ResourceRelation.CSS_URL,
            ResourceKind.IMAGE,
        )

        val snapshot = graph.snapshot()
        assertEquals(3, snapshot.nodes.size)
        assertEquals(1, snapshot.edges.size)
        assertTrue(registry.contextsFor(kept).isNotEmpty())
        assertTrue(registry.contextsFor(dropped).isEmpty())
    }

    @Test
    fun `navigation kind promotion is deterministic regardless of discovery order`() {
        fun discover(frameFirst: Boolean): ResourceNodeSnapshot {
            val registry = NavigationResourceRegistry()
            val graph = registry.startNavigation(URI("http://legacy.test/root"))
            val target = URI("http://legacy.test/mixed")
            val upstream = URI("https://legacy.test/mixed")
            if (frameFirst) {
                registry.discover(graph, graph.rootLegacyUri, target, upstream, ResourceRelation.FRAME_SRC, ResourceKind.FRAME)
                registry.discover(graph, graph.rootLegacyUri, target, upstream, ResourceRelation.A_HREF, ResourceKind.DOCUMENT)
            } else {
                registry.discover(graph, graph.rootLegacyUri, target, upstream, ResourceRelation.A_HREF, ResourceKind.DOCUMENT)
                registry.discover(graph, graph.rootLegacyUri, target, upstream, ResourceRelation.FRAME_SRC, ResourceKind.FRAME)
            }
            return graph.snapshot().nodes.single { it.legacyUri == target }
        }

        assertEquals(ResourceKind.DOCUMENT, discover(frameFirst = true).kind)
        assertEquals(ResourceKind.DOCUMENT, discover(frameFirst = false).kind)
        assertEquals(ReferenceRole.NAVIGATION, discover(frameFirst = true).role)
        assertEquals(ReferenceRole.NAVIGATION, discover(frameFirst = false).role)
    }

}
