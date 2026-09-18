package no.skasti.yame.ppp.proxy.cookies

import java.net.CookieManager
import java.net.CookieStore
import java.net.HttpCookie
import java.net.URI
import java.util.Locale

internal data class CookieOverride(
    val name: String,
    val domain: String,
    val path: String,
    val secure: Boolean,
)

internal class BoundedCookieOverrides(
    private val maxEntries: Int = 256,
) {
    private data class Key(val name: String, val domain: String, val path: String)

    init {
        require(maxEntries > 0) { "Cookie override limit must be positive" }
    }

    private val entries = linkedMapOf<Key, CookieOverride>()

    @Synchronized
    fun put(override: CookieOverride) {
        val key = Key(override.name.lowercase(Locale.ROOT), override.domain.lowercase(Locale.ROOT), override.path)
        entries.remove(key)
        entries[key] = override
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun any(predicate: (CookieOverride) -> Boolean): Boolean = entries.values.any(predicate)
}

internal class BoundedCookieStore(
    private val maxEntries: Int = 256,
    private val delegate: CookieStore = CookieManager().cookieStore,
) : CookieStore {
    private data class Key(val name: String, val domain: String, val path: String)
    private data class Entry(val uri: URI?, val cookie: HttpCookie)

    init {
        require(maxEntries > 0) { "Cookie store limit must be positive" }
    }

    private val insertionOrder = linkedMapOf<Key, Entry>()

    @Synchronized
    override fun add(uri: URI?, cookie: HttpCookie) {
        purgeExpired()
        val key = key(uri, cookie)
        insertionOrder.remove(key)?.let { previous -> delegate.remove(previous.uri, previous.cookie) }
        delegate.add(uri, cookie)
        if (!cookie.hasExpired()) insertionOrder[key] = Entry(uri, cookie)
        trimToLimit()
    }

    @Synchronized
    override fun get(uri: URI): MutableList<HttpCookie> {
        purgeExpired()
        return delegate.get(uri).toMutableList()
    }

    @Synchronized
    override fun getCookies(): MutableList<HttpCookie> {
        purgeExpired()
        return delegate.getCookies().toMutableList()
    }

    @Synchronized
    override fun getURIs(): MutableList<URI> {
        purgeExpired()
        return delegate.getURIs().toMutableList()
    }

    @Synchronized
    override fun remove(uri: URI?, cookie: HttpCookie): Boolean {
        insertionOrder.remove(key(uri, cookie))
        return delegate.remove(uri, cookie)
    }

    @Synchronized
    override fun removeAll(): Boolean {
        insertionOrder.clear()
        return delegate.removeAll()
    }

    private fun key(uri: URI?, cookie: HttpCookie): Key =
        Key(
            name = cookie.name.lowercase(Locale.ROOT),
            domain = (cookie.domain ?: uri?.host.orEmpty()).lowercase(Locale.ROOT),
            path = cookie.path.orEmpty(),
        )

    private fun purgeExpired() {
        val iterator = insertionOrder.iterator()
        while (iterator.hasNext()) {
            val (_, entry) = iterator.next()
            if (entry.cookie.hasExpired()) {
                delegate.remove(entry.uri, entry.cookie)
                iterator.remove()
            }
        }
    }

    private fun trimToLimit() {
        while (insertionOrder.size > maxEntries) {
            val oldestKey = insertionOrder.keys.first()
            val oldest = insertionOrder.remove(oldestKey) ?: continue
            delegate.remove(oldest.uri, oldest.cookie)
        }
    }
}
