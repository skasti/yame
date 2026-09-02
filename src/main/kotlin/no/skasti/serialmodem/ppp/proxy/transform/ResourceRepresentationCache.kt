package no.skasti.serialmodem.ppp.proxy.transform

import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

internal data class ResourceRepresentationKey(
    val legacyUri: URI,
    val upstreamUri: URI,
    val profile: String,
)

internal class ResourceRepresentationCache(
    private val maxEntries: Int,
    private val maxBytes: Long,
) {
    init {
        require(maxEntries > 0) { "Resource representation cache entry limit must be positive" }
        require(maxBytes > 0) { "Resource representation cache byte limit must be positive" }
    }

    private data class Entry(
        val representation: ResourceRepresentation,
        val bytes: Long,
    )

    private val lock = Any()
    private val entries = linkedMapOf<ResourceRepresentationKey, Entry>()
    private var totalBytes = 0L

    fun get(key: ResourceRepresentationKey): ResourceRepresentation? =
        synchronized(lock) {
            val entry = entries.remove(key) ?: return@synchronized null
            entries[key] = entry
            entry.representation.copy(body = entry.representation.body.copyOf())
        }

    fun put(
        key: ResourceRepresentationKey,
        representation: ResourceRepresentation,
    ): Boolean {
        val size = representation.body.size.toLong()
        if (size > maxBytes) return false
        synchronized(lock) {
            entries.remove(key)?.let { totalBytes -= it.bytes }
            while (entries.isNotEmpty() && (entries.size >= maxEntries || totalBytes + size > maxBytes)) {
                val oldest = entries.entries.first()
                entries.remove(oldest.key)
                totalBytes -= oldest.value.bytes
            }
            val stored = representation.copy(
                headers = representation.headers.mapValues { (_, values) -> values.toList() },
                body = representation.body.copyOf(),
            )
            entries[key] = Entry(stored, size)
            totalBytes += size
        }
        return true
    }

    fun clear() =
        synchronized(lock) {
            entries.clear()
            totalBytes = 0
        }

    internal fun snapshot(): Pair<Int, Long> =
        synchronized(lock) { entries.size to totalBytes }
}

internal class InFlightResourceWork {
    private data class Pending(
        val future: CompletableFuture<ResourceRepresentation> = CompletableFuture(),
        val waiters: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(),
    )

    private val work = ConcurrentHashMap<ResourceRepresentationKey, Pending>()

    fun getOrStart(
        key: ResourceRepresentationKey,
        producer: () -> ResourceRepresentation,
    ): ResourceRepresentation {
        val created = Pending()
        val existing = work.putIfAbsent(key, created)
        if (existing != null) {
            existing.waiters.incrementAndGet()
            try {
                return existing.future.join()
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

    internal fun waiterCount(key: ResourceRepresentationKey): Int =
        work[key]?.waiters?.get() ?: 0
}
