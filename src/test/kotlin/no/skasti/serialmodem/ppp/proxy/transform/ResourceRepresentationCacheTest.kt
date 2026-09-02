package no.skasti.serialmodem.ppp.proxy.transform

import java.net.URI
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceRepresentationCacheTest {
    @Test
    fun `cache returns defensive copies of source transformed body and headers`() {
        val cache = ResourceCache(maxEntries = 4, maxBytes = 1024)
        val key = key("profile-a", "v1")
        cache.put(key, cachedResource(byteArrayOf(1, 2, 3), byteArrayOf(4, 5)))

        val cached = requireNotNull(cache.get(key))
        cached.resource.source.body[0] = 99
        (cached.resource.source.headers["Content-Type"] as MutableList)[0] = "broken"
        cached.resource.transformed!!.representation.body[0] = 88

        val reread = requireNotNull(cache.get(key))
        assertContentEquals(byteArrayOf(1, 2, 3), reread.resource.source.body)
        assertEquals(listOf("application/octet-stream"), reread.resource.source.headers["Content-Type"])
        assertContentEquals(byteArrayOf(4, 5), reread.resource.transformed!!.representation.body)
    }

    @Test
    fun `cache separates source versions and profiles`() {
        val cache = ResourceCache(maxEntries = 4, maxBytes = 1024)
        val first = key("profile-a", "v1")
        val second = key("profile-a", "v2")
        val third = key("profile-b", "v1")
        cache.put(first, cachedResource(byteArrayOf(1), null))
        cache.put(second, cachedResource(byteArrayOf(2), null))
        cache.put(third, cachedResource(byteArrayOf(3), null))

        assertContentEquals(byteArrayOf(1), requireNotNull(cache.get(first)).resource.source.body)
        assertContentEquals(byteArrayOf(2), requireNotNull(cache.get(second)).resource.source.body)
        assertContentEquals(byteArrayOf(3), requireNotNull(cache.get(third)).resource.source.body)
    }

    @Test
    fun `cache accounts for source and transformed bytes when evicting`() {
        val cache = ResourceCache(maxEntries = 2, maxBytes = 7)
        val first = key("one", "v1")
        val second = key("two", "v1")
        val third = key("three", "v1")

        assertTrue(cache.put(first, cachedResource(byteArrayOf(1, 1), byteArrayOf(1))))
        assertTrue(cache.put(second, cachedResource(byteArrayOf(2, 2), null)))
        requireNotNull(cache.get(first))
        assertTrue(cache.put(third, cachedResource(byteArrayOf(3, 3), byteArrayOf(3, 3))))

        assertNull(cache.get(second))
        assertNull(cache.get(first))
        assertEquals(1 to 4L, cache.snapshot())
    }

    @Test
    fun `no-store resources are not cached`() {
        val cache = ResourceCache(maxEntries = 2, maxBytes = 1024)
        val cached = cachedResource(byteArrayOf(1), null).copy(
            cachePolicy = ResourceCachePolicy(noStore = true),
        )

        assertFalse(cache.put(key("profile", "v1"), cached))
        assertEquals(0 to 0L, cache.snapshot())
    }

    @Test
    fun `cache policy parses freshness and revalidation metadata`() {
        val headers = mapOf(
            "Cache-Control" to listOf("public, max-age=60, must-revalidate"),
            "ETag" to listOf("\"abc\""),
            "Last-Modified" to listOf("Wed, 02 Sep 2026 12:30:00 GMT"),
        )
        val policy = cachePolicyFrom(headers)
        val validators = validatorsFrom(headers)
        val storedAt = Instant.parse("2026-09-02T12:00:00Z")

        assertEquals(60, policy.maxAgeSeconds)
        assertTrue(policy.mustRevalidate)
        assertTrue(policy.isFresh(storedAt, storedAt.plusSeconds(30)))
        assertFalse(policy.isFresh(storedAt, storedAt.plusSeconds(61)))
        assertEquals("\"abc\"", validators.etag)
        assertEquals("Wed, 02 Sep 2026 12:30:00 GMT", validators.lastModified)
    }

    @Test
    fun `in flight work coalesces concurrent producers`() {
        val coordinator = InFlightResourceWork()
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val key = key("same", "v1")

        try {
            val first = pool.submit<ResourceTransformationState> {
                coordinator.getOrStart(key) {
                    calls.incrementAndGet()
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    state(byteArrayOf(7))
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = pool.submit<ResourceTransformationState> {
                coordinator.getOrStart(key) {
                    calls.incrementAndGet()
                    state(byteArrayOf(8))
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (coordinator.waiterCount(key) == 0 && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(1, coordinator.waiterCount(key))
            release.countDown()

            assertContentEquals(byteArrayOf(7), first.get(2, TimeUnit.SECONDS).resource.source.body)
            assertContentEquals(byteArrayOf(7), second.get(2, TimeUnit.SECONDS).resource.source.body)
            assertEquals(1, calls.get())
            assertEquals(0, coordinator.size())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    private fun key(profile: String, version: String) = ResourceCacheKey(
        legacyUri = URI("http://legacy.test/resource"),
        upstreamUri = URI("https://modern.test/resource"),
        sourceVersion = version,
        profile = profile,
    )

    private fun cachedResource(sourceBody: ByteArray, transformedBody: ByteArray?) = CachedResource(
        resource = Resource(
            upstreamUri = URI("https://modern.test/resource"),
            source = representation(sourceBody),
            transformed = transformedBody?.let {
                TransformedRepresentation("profile", representation(it))
            },
        ),
        storedAt = Instant.EPOCH,
        cachePolicy = ResourceCachePolicy(maxAgeSeconds = 60),
        validators = ResourceValidators(),
    )

    private fun state(body: ByteArray) = ResourceTransformationState(
        resource = Resource(
            upstreamUri = URI("https://modern.test/resource"),
            source = representation(body),
        ),
    )

    private fun representation(body: ByteArray) = ResourceRepresentation(
        statusCode = 200,
        headers = mapOf("Content-Type" to mutableListOf("application/octet-stream")),
        body = body,
    )
}
