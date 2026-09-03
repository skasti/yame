package no.skasti.serialmodem.ppp.proxy.transform

import no.skasti.serialmodem.ppp.proxy.ReferenceRole
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal data class ResourceFetchKey(
    val scope: String,
    val legacyUri: URI,
    val upstreamUri: URI,
    val role: ReferenceRole,
    val usesTargetAsContentBase: Boolean,
    val requestFingerprint: String,
)

internal class InFlightResourceFetchWork<T> {
    private data class Pending<T>(
        val future: CompletableFuture<T> = CompletableFuture(),
        val waiters: AtomicInteger = AtomicInteger(),
    )

    private val work = ConcurrentHashMap<ResourceFetchKey, Pending<T>>()

    fun getOrStart(
        key: ResourceFetchKey,
        producer: () -> T,
    ): T {
        val created = Pending<T>()
        val existing = work.putIfAbsent(key, created)
        if (existing != null) {
            existing.waiters.incrementAndGet()
            try {
                return try {
                    existing.future.join()
                } catch (error: CompletionException) {
                    throw (error.cause ?: error)
                }
            } finally {
                existing.waiters.decrementAndGet()
            }
        }

        try {
            val result = producer()
            created.future.complete(result)
            return result
        } catch (error: Throwable) {
            created.future.completeExceptionally(error)
            throw error
        } finally {
            work.remove(key, created)
        }
    }

    fun clear() {
        work.values.forEach { it.future.cancel(true) }
        work.clear()
    }

    internal fun size(): Int = work.size

    internal fun waiterCount(key: ResourceFetchKey): Int =
        work[key]?.waiters?.get() ?: 0
}

internal fun resourceRequestFingerprint(
    method: String,
    headers: List<Pair<String, String>>,
    body: ByteArray,
    effectiveCookieHeaders: List<String>,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.updateLengthPrefixed(method.uppercase(Locale.ROOT).toByteArray(StandardCharsets.UTF_8))
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(headers.size).array())
    headers.forEach { (name, value) ->
        digest.updateLengthPrefixed(name.lowercase(Locale.ROOT).toByteArray(StandardCharsets.UTF_8))
        digest.updateLengthPrefixed(value.toByteArray(StandardCharsets.UTF_8))
    }
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(effectiveCookieHeaders.size).array())
    effectiveCookieHeaders.forEach {
        digest.updateLengthPrefixed(it.toByteArray(StandardCharsets.UTF_8))
    }
    digest.updateLengthPrefixed(body)
    return HexFormat.of().formatHex(digest.digest())
}

private fun MessageDigest.updateLengthPrefixed(bytes: ByteArray) {
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}
