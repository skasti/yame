package no.skasti.serialmodem.ppp.proxy.transform

import java.net.URI
import java.time.Instant
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

internal data class ResourceCacheKey(
    val legacyUri: URI,
    val upstreamUri: URI,
    val profile: String,
)

internal data class ResourceWorkKey(
    val cacheKey: ResourceCacheKey,
    val sourceFingerprint: String,
)

internal data class ResourceValidators(
    val etag: String? = null,
    val lastModified: String? = null,
)

internal data class ResourceCachePolicy(
    val maxAgeSeconds: Long? = null,
    val expiresAt: Instant? = null,
    val noCache: Boolean = false,
    val noStore: Boolean = false,
    val mustRevalidate: Boolean = false,
) {
    fun isFresh(storedAt: Instant, now: Instant = Instant.now()): Boolean {
        if (noCache) return false
        val maxAgeFreshUntil = maxAgeSeconds?.let(storedAt::plusSeconds)
        val freshUntil = listOfNotNull(maxAgeFreshUntil, expiresAt).minOrNull() ?: return false
        return now.isBefore(freshUntil)
    }
}

internal data class CachedResource(
    val resource: Resource,
    val storedAt: Instant,
    val cachePolicy: ResourceCachePolicy,
    val validators: ResourceValidators,
)

internal fun cachePolicyFrom(headers: Map<String, List<String>>): ResourceCachePolicy {
    val directives =
        headers.entries
            .filter { (name, _) -> name.equals("cache-control", ignoreCase = true) }
            .flatMap { it.value }
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    var maxAgeSeconds: Long? = null
    var noCache = false
    var noStore = false
    var mustRevalidate = false
    directives.forEach { directive ->
        val separator = directive.indexOf('=')
        val name = (if (separator >= 0) directive.substring(0, separator) else directive)
            .trim()
            .lowercase(Locale.ROOT)
        val value = if (separator >= 0) directive.substring(separator + 1).trim().trim('"') else null
        when (name) {
            "max-age" -> maxAgeSeconds = value?.toLongOrNull()?.takeIf { it >= 0 }
            "no-cache" -> noCache = true
            "no-store" -> noStore = true
            "must-revalidate" -> mustRevalidate = true
        }
    }

    val expiresAt =
        firstHeader(headers, "expires")
            ?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }

    return ResourceCachePolicy(
        maxAgeSeconds = maxAgeSeconds,
        expiresAt = expiresAt,
        noCache = noCache,
        noStore = noStore,
        mustRevalidate = mustRevalidate,
    )
}

internal fun validatorsFrom(headers: Map<String, List<String>>): ResourceValidators =
    ResourceValidators(
        etag = firstHeader(headers, "etag"),
        lastModified = firstHeader(headers, "last-modified"),
    )

internal class ResourceCache(
    private val maxEntries: Int,
    private val maxBytes: Long,
) {
    init {
        require(maxEntries > 0) { "Resource cache entry limit must be positive" }
        require(maxBytes > 0) { "Resource cache byte limit must be positive" }
    }

    private data class Entry(
        val cached: CachedResource,
        val bytes: Long,
    )

    private val lock = Any()
    private val entries = linkedMapOf<ResourceCacheKey, Entry>()
    private var totalBytes = 0L

    fun get(key: ResourceCacheKey): CachedResource? =
        synchronized(lock) {
            val entry = entries.remove(key) ?: return@synchronized null
            entries[key] = entry
            entry.cached.deepCopy()
        }

    fun put(
        key: ResourceWorkKey,
        cached: CachedResource,
    ): Boolean {
        if (cached.cachePolicy.noStore) return false
        val size = cached.resource.totalBodyBytes()
        if (size > maxBytes) return false

        synchronized(lock) {
            entries.remove(key)?.let { totalBytes -= it.bytes }
            while (entries.isNotEmpty() && (entries.size >= maxEntries || totalBytes + size > maxBytes)) {
                val oldest = entries.entries.first()
                entries.remove(oldest.key)
                totalBytes -= oldest.value.bytes
            }
            entries[key] = Entry(cached.deepCopy(), size)
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
        val future: CompletableFuture<ResourceTransformationState> = CompletableFuture(),
        val waiters: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(),
    )

    private val work = ConcurrentHashMap<ResourceWorkKey, Pending>()

    fun getOrStart(
        key: ResourceWorkKey,
        producer: () -> ResourceTransformationState,
    ): ResourceTransformationState {
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

    internal fun waiterCount(key: ResourceCacheKey): Int =
        work[key]?.waiters?.get() ?: 0
}

private fun firstHeader(headers: Map<String, List<String>>, name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

private fun Resource.totalBodyBytes(): Long =
    source.body.size.toLong() + (transformed?.representation?.body?.size?.toLong() ?: 0L)

private fun CachedResource.deepCopy(): CachedResource =
    copy(resource = resource.deepCopy())

private fun Resource.deepCopy(): Resource =
    copy(
        source = source.deepCopy(),
        transformed = transformed?.copy(representation = transformed.representation.deepCopy()),
    )

private fun ResourceRepresentation.deepCopy(): ResourceRepresentation =
    copy(
        headers = headers.mapValues { (_, values) -> values.toList() },
        body = body.copyOf(),
    )


internal fun sourceFingerprint(representation: ResourceRepresentation): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(representation.statusCode).array())

    val normalizedHeaders =
        representation.headers.entries
            .groupBy(
                keySelector = { (name, _) -> name.lowercase(Locale.ROOT) },
                valueTransform = { it.value },
            )
            .mapValues { (_, valueLists) -> valueLists.flatten() }
            .toSortedMap()

    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(normalizedHeaders.size).array())
    normalizedHeaders.forEach { (name, values) ->
        digest.updateLengthPrefixed(name.toByteArray(StandardCharsets.UTF_8))
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(values.size).array())
        values.forEach { value ->
            digest.updateLengthPrefixed(value.toByteArray(StandardCharsets.ISO_8859_1))
        }
    }
    digest.updateLengthPrefixed(representation.body)
    return HexFormat.of().formatHex(digest.digest())
}

internal fun sameSourceRepresentation(
    first: ResourceRepresentation,
    second: ResourceRepresentation,
): Boolean =
    sourceFingerprint(first) == sourceFingerprint(second)

private fun MessageDigest.updateLengthPrefixed(bytes: ByteArray) {
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}
