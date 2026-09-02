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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceRepresentationCacheTest {
    @Test
    fun `cache returns defensive copies of source transformed body and headers`() {
        val cache = ResourceCache(maxEntries = 4, maxBytes = 1024)
        val key = cacheKey("profile-a")
        cache.put(key, cachedResource(byteArrayOf(1, 2, 3), byteArrayOf(4, 5)))

        val cached = requireNotNull(cache.get(key))
        cached.resource.source.body[0] = 99
        (cached.resource.source.headers as MutableMap<String, List<String>>)["Content-Type"] = listOf("broken")
        cached.resource.transformed!!.representation.body[0] = 88

        val reread = requireNotNull(cache.get(key))
        assertContentEquals(byteArrayOf(1, 2, 3), reread.resource.source.body)
        assertEquals(listOf("application/octet-stream"), reread.resource.source.headers["Content-Type"])
        assertContentEquals(byteArrayOf(4, 5), reread.resource.transformed!!.representation.body)
    }

    @Test
    fun `cache replaces resource for same identity and keeps profiles separate`() {
        val cache = ResourceCache(maxEntries = 4, maxBytes = 1024)
        val profileA = cacheKey("profile-a")
        val profileB = cacheKey("profile-b")

        cache.put(profileA, cachedResource(byteArrayOf(1), null))
        cache.put(profileA, cachedResource(byteArrayOf(2), null))
        cache.put(profileB, cachedResource(byteArrayOf(3), null))

        assertContentEquals(byteArrayOf(2), requireNotNull(cache.get(profileA)).resource.source.body)
        assertContentEquals(byteArrayOf(3), requireNotNull(cache.get(profileB)).resource.source.body)
        assertEquals(2, cache.snapshot().first)
    }

    @Test
    fun `source fingerprint covers status headers and body with fixed width hex`() {
        val base = representation(byteArrayOf(0x80.toByte(), 0xff.toByte()))
        val sameDifferentHeaderCase = base.copy(
            headers = mapOf("content-type" to listOf("application/octet-stream")),
        )
        val differentStatus = base.copy(statusCode = 201)
        val differentHeader = base.copy(headers = mapOf("Content-Type" to listOf("image/gif")))
        val differentBody = base.copy(body = byteArrayOf(0x80.toByte(), 0xfe.toByte()))

        val fingerprint = sourceFingerprint(base)

        assertEquals(64, fingerprint.length)
        assertEquals(fingerprint, sourceFingerprint(sameDifferentHeaderCase))
        assertNotEquals(fingerprint, sourceFingerprint(differentStatus))
        assertNotEquals(fingerprint, sourceFingerprint(differentHeader))
        assertNotEquals(fingerprint, sourceFingerprint(differentBody))
        assertTrue(sameSourceRepresentation(base, sameDifferentHeaderCase))
        assertFalse(sameSourceRepresentation(base, differentHeader))
    }

    @Test
    fun `cache accounts for source and transformed bytes when evicting`() {
        val cache = ResourceCache(maxEntries = 2, maxBytes = 6)
        val first = cacheKey("one")
        val second = cacheKey("two")
        val third = cacheKey("three")

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

        assertFalse(cache.put(cacheKey("profile"), cached))
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

        assertEquals(60L, policy.maxAgeSeconds)
        assertTrue(policy.mustRevalidate)
        assertTrue(policy.isFresh(storedAt, storedAt.plusSeconds(30)))
        assertFalse(policy.isFresh(storedAt, storedAt.plusSeconds(61)))
        assertEquals("\"abc\"", validators.etag)
        assertEquals("Wed, 02 Sep 2026 12:30:00 GMT", validators.lastModified)
    }

    @Test
    fun `in flight work coalesces only identical source representations`() {
        val coordinator = InFlightResourceWork()
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val source = representation(byteArrayOf(7))
        val key = workKey("same", source)

        try {
            val first = pool.submit<ResourceTransformationState> {
                coordinator.getOrStart(key) {
                    calls.incrementAndGet()
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    state(source)
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = pool.submit<ResourceTransformationState> {
                coordinator.getOrStart(key) {
                    calls.incrementAndGet()
                    state(representation(byteArrayOf(8)))
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

    private fun cacheKey(profile: String) = ResourceCacheKey(
        legacyUri = URI("http://legacy.test/resource"),
        upstreamUri = URI("https://modern.test/resource"),
        profile = profile,
    )

    private fun workKey(profile: String, source: ResourceRepresentation) = ResourceWorkKey(
        cacheKey = cacheKey(profile),
        sourceFingerprint = sourceFingerprint(source),
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

    private fun state(source: ResourceRepresentation) = ResourceTransformationState(
        resource = Resource(
            upstreamUri = URI("https://modern.test/resource"),
            source = source,
        ),
    )

    private fun representation(body: ByteArray) = ResourceRepresentation(
        statusCode = 200,
        headers = mapOf("Content-Type" to mutableListOf("application/octet-stream")),
        body = body,
    )
}
