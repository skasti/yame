package no.skasti.yame.config.ini

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer
import kotlin.reflect.KType
import kotlin.time.Duration

/** INI scalar values use standard serializers, with readable Kotlin durations. */
@OptIn(ExperimentalSerializationApi::class)
object IniScalar {
    private val json = Json

    fun decode(raw: String, type: KType): Any {
        // String values are literal. Other scalar types may ignore surrounding
        // whitespace, independently of the structural INI parser.
        val value = if (type.classifier == String::class) raw else raw.trim()
        if (type.classifier == Duration::class) return Duration.parse(value)
        val serializer = serializer(type)
        val primitive = when (serializer.descriptor.kind) {
            kotlinx.serialization.descriptors.PrimitiveKind.INT,
            kotlinx.serialization.descriptors.PrimitiveKind.LONG,
            kotlinx.serialization.descriptors.PrimitiveKind.SHORT,
            kotlinx.serialization.descriptors.PrimitiveKind.BYTE,
            kotlinx.serialization.descriptors.PrimitiveKind.FLOAT,
            kotlinx.serialization.descriptors.PrimitiveKind.DOUBLE,
            kotlinx.serialization.descriptors.PrimitiveKind.BOOLEAN -> json.parseToJsonElement(value)
            else -> JsonPrimitive(value)
        }
        return requireNotNull(json.decodeFromJsonElement(serializer, primitive))
    }

    fun encode(value: Any, type: KType): String {
        if (type.classifier == Duration::class) return (value as Duration).toString()
        val primitive = json.encodeToJsonElement(serializer(type), value) as? JsonPrimitive
            ?: error("${type}: INI settings must serialize to scalar values")
        return primitive.content
    }
}
