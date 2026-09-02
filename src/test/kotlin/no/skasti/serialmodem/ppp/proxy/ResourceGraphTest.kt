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
    fun `registry evicts the oldest navigation context and URI associations`() {
        val registry = NavigationResourceRegistry(maxContexts = 2)
        val shared = URI("http://cdn.test/old.gif")
        val first = registry.startNavigation(URI("http://legacy.test/one"))
        registry.discover(
            first,
            first.rootLegacyUri,
            shared,
            URI("https://cdn.test/old.gif"),
            ResourceRelation.IMG_SRC,
            ResourceKind.IMAGE,
        )
        val second = registry.startNavigation(URI("http://legacy.test/two"))
        val third = registry.startNavigation(URI("http://legacy.test/three"))

        assertEquals(listOf(second.id, third.id), registry.snapshots().map { it.id })
        assertTrue(registry.contextsFor(shared).isEmpty())
    }

    @Test
    fun `graph bounds nodes and edges and does not index dropped resources`() {
        val registry = NavigationResourceRegistry(
            maxContexts = 1,
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
    fun `registry hooks report add use and eviction removal`() {
        val hooks = ResourceRegistryHooks()
        val addedRoots = mutableListOf<ResourceRegistryRoot>()
        val usedRoots = mutableListOf<ResourceRegistryRoot>()
        val removedRoots = mutableListOf<ResourceRegistryRoot>()
        val addedResources = mutableListOf<ResourceRegistryResource>()
        val removedResources = mutableListOf<ResourceRegistryResource>()
        hooks.onRootAdded += addedRoots::add
        hooks.onRootUsed += usedRoots::add
        hooks.onRootRemoved += removedRoots::add
        hooks.onResourceAdded += addedResources::add
        hooks.onResourceRemoved += removedResources::add

        val registry = NavigationResourceRegistry(maxContexts = 1, hooks = hooks)
        val firstRoot = URI("http://legacy.test/one")
        val first = registry.startNavigation(firstRoot)
        val child = URI("http://cdn.test/site.css")
        registry.discover(
            first,
            first.rootLegacyUri,
            child,
            URI("https://cdn.test/site.css"),
            ResourceRelation.LINK_STYLESHEET,
            ResourceKind.STYLESHEET,
        )

        assertEquals(listOf(firstRoot), addedRoots.map { it.rootLegacyUri })
        assertEquals(2, usedRoots.count { it.graphId == first.id })
        assertEquals(listOf(child), addedResources.map { it.resourceLegacyUri })

        val evictionOrder = mutableListOf<String>()
        hooks.onRootRemoved += { evictionOrder += "root:${it.graphId}" }
        hooks.onResourceRemoved += { evictionOrder += "resource:${it.graphId}:${it.resourceLegacyUri}" }

        val second = registry.startNavigation(URI("http://legacy.test/two"))

        assertEquals(listOf(first.id), removedRoots.map { it.graphId })
        assertTrue(removedResources.any { it.graphId == first.id && it.resourceLegacyUri == child })
        assertTrue(evictionOrder.firstOrNull() == "root:${first.id}")
        assertTrue(second.id != first.id)
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


}
