package no.skasti.yame.ppp.proxy

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceGraphDirectRequestBoundsTest {
    @Test
    fun `direct requests cannot grow a host graph beyond its node limit`() {
        val registry =
            NavigationResourceRegistry(
                maxContexts = 4,
                maxNodesPerHost = 3,
                maxEdgesPerContext = 8,
            )

        val first = URI("http://example.test/one")
        val second = URI("http://example.test/two")
        val third = URI("http://example.test/three")
        val overflow = URI("http://example.test/four")

        registry.ensureResourceContext(first)
        registry.ensureResourceContext(second)
        registry.ensureResourceContext(third)
        registry.ensureResourceContext(overflow)

        val graph = registry.snapshots().single()
        assertEquals(3, graph.nodes.size)
        assertTrue(registry.contextsFor(first).isNotEmpty())
        assertTrue(registry.contextsFor(second).isNotEmpty())
        assertTrue(registry.contextsFor(overflow).isEmpty())
    }

    @Test
    fun `begin fetch cannot bypass a full host graph`() {
        val registry =
            NavigationResourceRegistry(
                maxContexts = 4,
                maxNodesPerHost = 2,
                maxEdgesPerContext = 8,
            )
        val admitted = URI("http://example.test/admitted")
        val overflow = URI("http://example.test/overflow")
        val graph = registry.ensureResourceContext(admitted)

        val attempt =
            registry.beginFetch(
                graph = graph,
                legacyUri = overflow,
                upstreamUri = URI("https://example.test/overflow"),
                contentBase = URI("https://example.test/overflow"),
            )

        assertEquals(null, attempt)
        assertEquals(2, graph.snapshot().nodes.size)
        assertTrue(registry.contextsFor(overflow).isEmpty())
    }
}
