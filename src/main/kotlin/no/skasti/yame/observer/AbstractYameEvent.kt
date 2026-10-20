package no.skasti.yame.observer

import kotlin.reflect.KProperty1
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberProperties

/** Optional convenience for events whose public Kotlin properties form their payload. */
abstract class AbstractYameEvent : AbstractMap<String, Any?>(), YameEvent {
    private class Properties(type: Class<*>) {
        // Only the concrete class declares payload. Base-class map/metadata properties are excluded.
        private val accessors: Map<String, KProperty1<Any, *>> = type.kotlin.declaredMemberProperties
            .filter { it.visibility == KVisibility.PUBLIC && it.name !in setOf("typeName", "version") }
            .associate { property ->
                @Suppress("UNCHECKED_CAST")
                property.name to (property as KProperty1<Any, *>)
            }

        val names: Set<String> = accessors.keys
        fun get(event: AbstractYameEvent, name: String): Any? = accessors.getValue(name).get(event)
    }

    private class CacheCell {
        @Volatile var value: Properties? = null
            private set

        fun get(type: Class<*>): Properties = value ?: synchronized(this) {
            value ?: Properties(type).also { value = it }
        }
    }

    companion object {
        private val properties = object : ClassValue<CacheCell>() {
            override fun computeValue(type: Class<*>): CacheCell = CacheCell()
        }

        /** Test probe: observing the cache does not discover any properties. */
        internal fun cachedMetadataFor(type: Class<*>): Any? = properties.get(type).value
    }

    private val payload: Properties get() = properties.get(javaClass).get(javaClass)

    override val size: Int get() = payload.names.size
    override val keys: Set<String> get() = payload.names
    override fun containsKey(key: String): Boolean = key in payload.names
    override fun get(key: String): Any? = if (containsKey(key)) payload.get(this, key) else null
    override val entries: Set<Map.Entry<String, Any?>> get() = object : AbstractSet<Map.Entry<String, Any?>>() {
        override val size: Int get() = this@AbstractYameEvent.size
        override fun iterator(): Iterator<Map.Entry<String, Any?>> {
            val names = payload.names.iterator()
            return object : Iterator<Map.Entry<String, Any?>> {
                override fun hasNext(): Boolean = names.hasNext()
                override fun next(): Map.Entry<String, Any?> {
                    val name = names.next()
                    return java.util.AbstractMap.SimpleImmutableEntry(name, payload.get(this@AbstractYameEvent, name))
                }
            }
        }
    }
}
