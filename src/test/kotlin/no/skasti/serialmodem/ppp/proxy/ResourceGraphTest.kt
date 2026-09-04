package no.skasti.serialmodem.ppp.proxy
import no.skasti.serialmodem.ppp.proxy.NavigationResourceRegistry
import no.skasti.serialmodem.ppp.proxy.ReferenceRole
import no.skasti.serialmodem.ppp.proxy.ResourceKind
import no.skasti.serialmodem.ppp.proxy.ResourceRelation
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceGraphTest {
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
        assertEquals(2, snapshot.nodes.size)
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
        val first = registry.startNavigation(URI("http://legacy.test/one.html"))
        val second = registry.startNavigation(URI("http://legacy.test/two.html"))
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

        // Visiting unrelated roots must not evict accumulated resource knowledge.
        repeat(100) { index ->
            registry.startNavigation(URI("http://legacy.test/page-$index"))
        }

        val revisited = registry.startNavigation(root)
        assertEquals(first.id, revisited.id)
        assertTrue(revisited.contains(shared))
        assertEquals(101, registry.snapshots().size)
        assertEquals(listOf(first.id), registry.contextsFor(shared).map { it.id })
    }

    @Test
    fun `graph bounds nodes and edges and does not index dropped resources`() {
        val registry = NavigationResourceRegistry(
            maxNodesPerContext = 2,
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
        assertEquals(2, snapshot.nodes.size)
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

    @Test
    fun `registry hooks report root reuse without removal`() {
        val hooks = ResourceRegistryHooks()
        val addedRoots = mutableListOf<ResourceRegistryRoot>()
        val usedRoots = mutableListOf<ResourceRegistryRoot>()
        val removedRoots = mutableListOf<ResourceRegistryRoot>()
        hooks.onRootAdded += addedRoots::add
        hooks.onRootUsed += usedRoots::add
        hooks.onRootRemoved += removedRoots::add

        val registry = NavigationResourceRegistry(hooks = hooks)
        val root = URI("http://legacy.test/one")
        val first = registry.startNavigation(root)
        val revisited = registry.startNavigation(root)

        assertEquals(first.id, revisited.id)
        assertEquals(listOf(root), addedRoots.map { it.rootLegacyUri })
        assertEquals(2, usedRoots.count { it.graphId == first.id })
        assertTrue(removedRoots.isEmpty())
    }

    @Test
    fun `resource hook only fires when a resource is first added`() {
        val hooks = ResourceRegistryHooks()
        val addedResources = mutableListOf<ResourceRegistryResource>()
        hooks.onResourceAdded += addedResources::add
        val registry = NavigationResourceRegistry(hooks = hooks)
        val graph = registry.startNavigation(URI("http://legacy.test/root"))
        val child = URI("http://cdn.test/shared.gif")

        repeat(2) {
            registry.discover(
                graph,
                graph.rootLegacyUri,
                child,
                URI("https://cdn.test/shared.gif"),
                ResourceRelation.IMG_SRC,
                ResourceKind.IMAGE,
            )
        }

        assertEquals(listOf(child), addedResources.map { it.resourceLegacyUri })
    }


    @Test
    fun `registry hooks can be dispatched asynchronously while preserving order`() {
        val queued = ArrayDeque<() -> Unit>()
        val hooks = ResourceRegistryHooks { task -> queued.addLast(task) }
        val observed = mutableListOf<String>()
        hooks.onRootAdded += { observed += "added:${it.rootLegacyUri}" }
        hooks.onRootUsed += { observed += "used:${it.rootLegacyUri}" }

        val registry = NavigationResourceRegistry(hooks = hooks)
        val root = URI("http://legacy.test/root")
        registry.startNavigation(root)

        assertTrue(observed.isEmpty())
        while (queued.isNotEmpty()) {
            queued.removeFirst().invoke()
        }
        assertEquals(
            listOf(
                "added:$root",
                "used:$root",
            ),
            observed,
        )
    }


}
