package no.skasti.yame.config

import no.skasti.yame.config.ini.IniDocument
import no.skasti.yame.config.ini.IniScalar
import kotlin.reflect.KVisibility
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.KClass

/** Feature-owned setting definitions and mappings, not the current values held by ConfigStore. */
abstract class Settings(val id: String, val file: String? = null) {
    init {
        require(id.isNotBlank())
        require(file == null || file.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))) {
            "Invalid logical configuration file name: $file"
        }
    }

    private data class Discovered(
        val all: List<SettingKey<*>>,
        val ordinary: List<SettingKey<*>>,
        val composite: List<SettingKey<*>>,
    )

    private val discovered by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val keys = this::class.declaredMemberProperties.asSequence()
            .filter { it.visibility == KVisibility.PUBLIC }
            .flatMap { property -> discover(property.getter.call(this)).asSequence() }
            .filter { it.group.configuration === this }
            .distinct()
            .toList()
        require(keys.map { it.path }.distinct().size == keys.size) { "Duplicate setting path in $id" }
        val (composite, ordinary) = keys.filterNot { it.structural }.partition {
            (it.type.classifier as? KClass<*>)?.companionObjectInstance is CompositeSetting<*>
        }
        Discovered(keys, ordinary, composite)
    }

    val settingKeys: List<SettingKey<*>> get() = discovered.ordinary
    val compositeSettingKeys: List<SettingKey<*>> get() = discovered.composite
    val allSettingKeys: List<SettingKey<*>> get() = discovered.all

    private fun discover(value: Any?): List<SettingKey<*>> = when (value) {
        is SettingKey<*> -> listOf(value)
        is Map<*, *> -> value.values.flatMap(::discover)
        is Iterable<*> -> value.flatMap(::discover)
        is Array<*> -> value.flatMap(::discover)
        else -> emptyList()
    }

    open fun loadFromIni(context: IniReadContext): List<ConfigChange<*>> =
        settingKeys.map { loadOrdinary(it, context) } +
            compositeSettingKeys.map { loadComposite(it, context) }

    @Suppress("UNCHECKED_CAST")
    private fun loadOrdinary(key: SettingKey<*>, context: IniReadContext): ConfigChange<*> {
        val typed = key as SettingKey<Any?>
        val scoped = context.forKey(key)
        val raw = scoped.read("")
        try {
            return (typed to (raw?.let { IniScalar.decode(it, key.type) } ?: typed.default())).also { it.validate() }
        } catch (error: Exception) {
            throw IllegalArgumentException("${context.filename} [${key.group.id}] ${key.name}: ${error.message}", error)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadComposite(key: SettingKey<*>, context: IniReadContext): ConfigChange<*> {
        val typed = key as SettingKey<Any?>
        val companion = (key.type.classifier as KClass<*>).companionObjectInstance as CompositeSetting<Any>
        try {
            return (typed to companion.loadFromIni(context.forKey(key))).also { it.validate() }
        } catch (error: Exception) {
            throw IllegalArgumentException("${context.filename} [${key.group.id}] ${key.name}: ${error.message}", error)
        }
    }

    open fun saveToIni(context: IniWriteContext) {
        settingKeys.filter { it in context.changedKeys }.forEach { key ->
            val value = requireNotNull(context.snapshot[key]) { "Missing ${key.path}" }.value
            context.forKey(key).write("", value?.let { IniScalar.encode(it, key.type) })
        }
        compositeSettingKeys.filter { it in context.changedKeys }.forEach { key ->
            @Suppress("UNCHECKED_CAST")
            val companion = (key.type.classifier as KClass<*>).companionObjectInstance as CompositeSetting<Any>
            val value = requireNotNull(context.snapshot[key]) { "Missing ${key.path}" }.value
            companion.saveToIni(requireNotNull(value), context.forKey(key))
        }
    }
}

/** Use a separate file named after this definition; the repository chooses its extension. */
abstract class SeparateSettings(id: String) : Settings(id = id, file = id)

interface CompositeSetting<T : Any> {
    fun loadFromIni(context: IniReadContext): T
    fun saveToIni(value: T, context: IniWriteContext)
}

class IniReadContext internal constructor(
    val filename: String,
    val document: IniDocument,
    val existed: Boolean,
    private val claimed: MutableSet<Pair<String, String>> = mutableSetOf(),
    private val key: SettingKey<*>? = null,
) {
    fun forKey(key: SettingKey<*>): IniReadContext = IniReadContext(filename, document, existed, claimed, key)

    fun read(child: String): String? {
        val owner = requireNotNull(key) { "A setting key is required for relative INI reads" }
        val property = owner.name + if (child.isEmpty()) "" else ".$child"
        val section = owner.group.id
        val flat = "$section.$property"
        val canonical = read(section, property)
        val legacy = read("", flat)
        require(canonical == null || legacy == null || canonical == legacy) {
            "$filename: conflicting [$section] $property and root $flat"
        }
        return canonical ?: legacy
    }

    fun read(section: String, property: String): String? {
        require(claimed.add(section to property)) { "Duplicate claim [$section] $property in $filename" }
        return document.get(section, property)
    }
    fun unconsumedSections(): List<String> = document.sections().filter { name ->
        name.isNotEmpty() && (document.properties(name).isEmpty() || document.properties(name).keys.any { name to it !in claimed })
    }
    fun properties(section: String): Map<String, String> = document.properties(section)
    fun claim(section: String, property: String): String? = read(section, property)
    internal fun claims(): Set<Pair<String, String>> = claimed.toSet()
}

class IniWriteContext internal constructor(
    val snapshot: ConfigChanges,
    val document: IniDocument,
    val changedKeys: Set<SettingKey<*>>,
    private val key: SettingKey<*>? = null,
) {
    fun forKey(key: SettingKey<*>): IniWriteContext = IniWriteContext(snapshot, document, changedKeys, key)
    fun write(child: String, value: String?) {
        val owner = requireNotNull(key) { "A setting key is required for relative INI writes" }
        val property = owner.name + if (child.isEmpty()) "" else ".$child"
        val section = owner.group.id
        document.remove("", "$section.$property")
        if (value == null) document.remove(section, property) else document.put(section, property, value)
    }
    fun write(section: String, property: String, value: String) = document.put(section, property, value)
    fun remove(section: String, property: String) = document.remove(section, property)
    fun removeSection(section: String) = document.removeSection(section)
    fun sections(): List<String> = document.sections()
    fun properties(section: String): Map<String, String> = document.properties(section)
}
