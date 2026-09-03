package no.skasti.serialmodem.ppp.proxy.transform

import no.skasti.serialmodem.ppp.proxy.ReferenceRole
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResourceFetchWorkTest {
    @Test
    fun `in flight fetch work is shared for the same request variant`() {
        val coordinator = InFlightResourceFetchWork<String>()
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val key = fetchKey("variant")

        try {
            val first = pool.submit<String> {
                coordinator.getOrStart(key) {
                    calls.incrementAndGet()
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    "shared"
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = pool.submit<String> {
                coordinator.getOrStart(key) {
                    calls.incrementAndGet()
                    "duplicate"
                }
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (coordinator.waiterCount(key) == 0 && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(1, coordinator.waiterCount(key))
            release.countDown()

            assertEquals("shared", first.get(2, TimeUnit.SECONDS))
            assertEquals("shared", second.get(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertEquals(0, coordinator.size())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `request fingerprint separates header body and cookie variants`() {
        val base = resourceRequestFingerprint(
            method = "GET",
            headers = listOf("Accept" to "image/gif"),
            body = ByteArray(0),
            effectiveCookieHeaders = listOf("session=a"),
        )

        assertEquals(
            base,
            resourceRequestFingerprint(
                method = "get",
                headers = listOf("accept" to "image/gif"),
                body = ByteArray(0),
                effectiveCookieHeaders = listOf("session=a"),
            ),
        )
        assertNotEquals(
            base,
            resourceRequestFingerprint(
                method = "GET",
                headers = listOf("Accept" to "image/png"),
                body = ByteArray(0),
                effectiveCookieHeaders = listOf("session=a"),
            ),
        )
        assertNotEquals(
            base,
            resourceRequestFingerprint(
                method = "GET",
                headers = listOf("Accept" to "image/gif"),
                body = byteArrayOf(1),
                effectiveCookieHeaders = listOf("session=a"),
            ),
        )
        assertNotEquals(
            base,
            resourceRequestFingerprint(
                method = "GET",
                headers = listOf("Accept" to "image/gif"),
                body = ByteArray(0),
                effectiveCookieHeaders = listOf("session=b"),
            ),
        )
    }

    private fun fetchKey(variant: String) = ResourceFetchKey(
        scope = "1:10.0.0.2",
        legacyUri = URI("http://legacy.test/resource"),
        upstreamUri = URI("https://modern.test/resource"),
        role = ReferenceRole.SUBRESOURCE,
        usesTargetAsContentBase = false,
        requestFingerprint = variant,
    )
}
