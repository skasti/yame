package no.skasti.yame.ppp.proxy
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.ppp.proxy.ReferenceRole
import no.skasti.yame.ppp.proxy.ResourceKind
import no.skasti.yame.ppp.proxy.ResourceRelation
import no.skasti.yame.ppp.proxy.transform.ResourceTransformationSummary
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceGraphTest {
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
        val hooks = ResourceRegistryHooks()
        val removed = mutableListOf<ResourceRegistryRoot>()
        hooks.onRootRemoved += removed::add
        val registry = NavigationResourceRegistry(maxContexts = 2, hooks = hooks)
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
        assertEquals(listOf(URI("http://legacy-two.test/")), removed.map { it.rootLegacyUri })
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
        registry.markFetched(
            first,
            first.rootLegacyUri,
            URI("https://legacy.test/one"),
            URI("https://legacy.test/one"),
            ResourceState.READY,
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
        assertEquals(listOf(URI("http://legacy.test/")), addedRoots.map { it.rootLegacyUri })
        assertEquals(2, usedRoots.count { it.graphId == first.id })
        assertTrue(removedRoots.isEmpty())
    }

    @Test
    fun `resource hook fires once for each newly admitted resource`() {
        val hooks = ResourceRegistryHooks()
        val addedResources = mutableListOf<ResourceRegistryResource>()
        hooks.onResourceAdded += addedResources::add
        val registry = NavigationResourceRegistry(hooks = hooks)
        val rootRequest = URI("http://legacy.test/root")
        val graph = registry.startNavigation(rootRequest)
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

        assertEquals(listOf(rootRequest, child), addedResources.map { it.resourceLegacyUri })
    }

    @Test
    fun `resource lifecycle updates are observable with sticky prefetch and transforms`() {
        val hooks = ResourceRegistryHooks()
        val updates = mutableListOf<ResourceRegistryResource>()
        hooks.onResourceUpdated += updates::add
        val registry = NavigationResourceRegistry(hooks = hooks)
        val graph = registry.startNavigation(URI("http://legacy.test/root"))
        val child = URI("http://cdn.test/hero.jpg")
        val upstream = URI("https://cdn.test/hero.jpg")

        registry.discover(
            graph,
            graph.rootLegacyUri,
            child,
            upstream,
            ResourceRelation.IMG_SRC,
            ResourceKind.IMAGE,
        )
        val attempt =
            requireNotNull(
                registry.beginFetch(
                    graph = graph,
                    legacyUri = child,
                    upstreamUri = upstream,
                    contentBase = upstream,
                    prefetched = true,
                ),
            )
        registry.markFetchState(
            graph,
            attempt,
            child,
            upstream,
            upstream,
            ResourceState.SOURCE_READY,
        )
        registry.markFetchState(
            graph,
            attempt,
            child,
            upstream,
            upstream,
            ResourceState.TRANSFORMING,
        )
        registry.recordTransformations(
            child,
            listOf(
                ResourceTransformationSummary(
                    transformerId = "legacy-image-optimization",
                    sourceBytes = 1000,
                    outputBytes = 250,
                ),
            ),
        )
        registry.markFetchState(
            graph,
            attempt,
            child,
            upstream,
            upstream,
            ResourceState.READY,
        )

        assertEquals(
            listOf(
                ResourceState.FETCHING,
                ResourceState.SOURCE_READY,
                ResourceState.TRANSFORMING,
                ResourceState.TRANSFORMING,
                ResourceState.READY,
            ),
            updates.map { it.state },
        )
        assertTrue(updates.all { it.prefetched })
        val node = graph.snapshot().nodes.single { it.legacyUri == child }
        assertEquals(ResourceState.READY, node.state)
        assertTrue(node.prefetched)
        assertEquals(750, node.transformations.single().savedBytes)
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
                "added:http://legacy.test/",
                "used:http://legacy.test/",
            ),
            observed,
        )
    }
}
