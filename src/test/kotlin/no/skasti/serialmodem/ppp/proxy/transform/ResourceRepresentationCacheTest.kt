package no.skasti.serialmodem.ppp.proxy.transform

import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceRepresentationCacheTest {
    @Test
    fun `cache separates transformation profiles and returns defensive body copies`() {
        val cache = ResourceRepresentationCache(maxEntries = 4, maxBytes = 1024)
        val first = key("profile-a")
        val second = key("profile-b")
        cache.put(first, representation(byteArrayOf(1, 2, 3)))
        cache.put(second, representation(byteArrayOf(4, 5)))

        val cached = requireNotNull(cache.get(first))
        cached.body[0] = 99

        assertContentEquals(byteArrayOf(1, 2, 3), requireNotNull(cache.get(first)).body)
        assertContentEquals(byteArrayOf(4, 5), requireNotNull(cache.get(second)).body)
    }

    @Test
    fun `cache evicts least recently used entries for entry and byte limits`() {
        val cache = ResourceRepresentationCache(maxEntries = 2, maxBytes = 5)
        val first = key("one")
        val second = key("two")
        val third = key("three")

        assertTrue(cache.put(first, representation(byteArrayOf(1, 1))))
        assertTrue(cache.put(second, representation(byteArrayOf(2, 2))))
        requireNotNull(cache.get(first))
        assertTrue(cache.put(third, representation(byteArrayOf(3, 3, 3))))

        assertNull(cache.get(second))
        assertNull(cache.get(first))
        assertContentEquals(byteArrayOf(3, 3, 3), requireNotNull(cache.get(third)).body)
        assertEquals(1 to 3L, cache.snapshot())
    }

    @Test
    fun `in flight work coalesces concurrent producers`() {
        val coordinator = InFlightResourceWork()
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val key = key("same")

        try {
            val first = pool.submit<ResourceRepresentation> {
                coordinator.getOrStart(key) {
                    calls.incrementAndGet()
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    representation(byteArrayOf(7))
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = pool.submit<ResourceRepresentation> {
                coordinator.getOrStart(key) {
                    calls.incrementAndGet()
                    representation(byteArrayOf(8))
                }
            }
            release.countDown()

            assertContentEquals(byteArrayOf(7), first.get(2, TimeUnit.SECONDS).body)
            assertContentEquals(byteArrayOf(7), second.get(2, TimeUnit.SECONDS).body)
            assertEquals(1, calls.get())
            assertEquals(0, coordinator.size())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    private fun key(profile: String) = ResourceRepresentationKey(
        legacyUri = URI("http://legacy.test/resource"),
        upstreamUri = URI("https://modern.test/resource"),
        profile = profile,
    )

    private fun representation(body: ByteArray) = ResourceRepresentation(
        statusCode = 200,
        headers = mapOf("Content-Type" to listOf("application/octet-stream")),
        body = body,
    )
}
