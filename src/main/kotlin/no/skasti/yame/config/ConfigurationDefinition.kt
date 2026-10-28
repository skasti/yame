package no.skasti.yame.config

import no.skasti.yame.config.ini.IniScalar
import no.skasti.yame.config.ini.IniDocument
import kotlin.reflect.KVisibility
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.KClass
import kotlin.reflect.jvm.javaField

/** Feature-owned setting definitions and mappings, not the current values held by ConfigStore. */
abstract class Settings(val id: String, val file: String? = null, val description: String? = null) {
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
            .flatMap { property ->
                // Object properties are backed by static fields on the JVM; a
                // reflected field getter can reject the apparent object receiver.
                val field = property.javaField
                val value = if (field != null) {
                    check(field.trySetAccessible()) { "Cannot inspect ${property.name} in $id" }
                    field.get(this)
                }
                    else if (property.getter.parameters.isEmpty()) property.getter.call()
                    else property.getter.call(this)
                discover(value).asSequence()
            }
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
abstract class SeparateSettings(id: String, description: String? = null) : Settings(id = id, file = id, description = description)

interface CompositeSetting<T : Any> {
    fun loadFromIni(context: IniReadContext): T
    fun saveToIni(value: T, context: IniWriteContext)
}

/** Attribute names alone never grant ownership; only a key-scoped get claims one. */
interface IniReadableAttributes {
    val keys: Set<String>
    operator fun get(name: String): String?
}

interface IniWritableAttributes {
    operator fun set(name: String, value: String)
    fun remove(name: String)
}

/** One logical location for both [section] property and root section.property. */
data class IniPropertyId(val file: String, val section: String, val property: String)

private fun checkClaim(
    owners: MutableMap<Pair<String, String>, SettingKey<*>>,
    section: String, property: String, key: SettingKey<*>, filename: String,
) {
    require(section.isNotEmpty() && property.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))) {
        "$filename: invalid INI section/property [$section] $property"
    }
    val location = section to property
    val previous = owners[location]
    require(previous == null || previous === key) {
        "$filename [$section] $property: ${key.path} conflicts with ${previous?.path}"
    }
    owners[location] = key
}

class IniReadContext internal constructor(
    val filename: String,
    private val document: IniDocument,
    val existed: Boolean,
    private val claimed: MutableMap<Pair<String, String>, SettingKey<*>> = linkedMapOf(),
    private val key: SettingKey<*>? = null,
    private val definition: Settings? = null,
) {
    private fun requireOwner(key: SettingKey<*>) {
        require(definition == null || (key.group.configuration === definition && key in definition.allSettingKeys)) {
            "$filename: ${key.path} does not belong to ${definition?.id}"
        }
    }
    fun forKey(key: SettingKey<*>): IniReadContext {
        requireOwner(key)
        return IniReadContext(filename, document, existed, claimed, key, definition)
    }

    private fun value(section: String, property: String): String? {
        val flat = "$section.$property"
        val canonical = document.get(section, property)
        val root = document.get("", flat)
        require(canonical == null || root == null || canonical == root) {
            "$filename: conflicting [$section] $property and root $flat"
        }
        return canonical ?: root
    }

    fun read(child: String): String? {
        val owner = requireNotNull(key) { "A setting key is required for relative INI reads" }
        val property = owner.name + if (child.isEmpty()) "" else ".$child"
        val result = value(owner.group.id, property)
        checkClaim(claimed, owner.group.id, property, owner, filename)
        return result
    }

    fun <T, R : Any> compose(
        key: SettingKey<T>, section: String, block: (IniReadableAttributes) -> R?,
    ): R? {
        requireOwner(key)
        val tentative = linkedMapOf<Pair<String, String>, SettingKey<*>>()
        val attrs = IniAttributes(filename, document, section, key, tentative)
        val result = try { block(attrs) }
        catch (error: Exception) {
            throw IllegalArgumentException("$filename [$section] ${key.path}: ${error.message}", error)
        }
        if (result != null) {
            // Check the entire block before installing any of its claims.
            tentative.forEach { (location, owner) ->
                val previous = claimed[location]
                require(previous == null || previous === owner) {
                    "$filename [${location.first}] ${location.second}: ${owner.path} conflicts with ${previous?.path}"
                }
            }
            claimed.putAll(tentative)
        }
        return result
    }

    fun unconsumedSections(): List<String> = document.sections().filter { name ->
        name.isNotEmpty() && (document.properties(name).isEmpty() || document.properties(name).keys.any { name to it !in claimed })
    }
    /** Root-form candidates are considered only if the feature requests these child names. */
    fun flatSectionsFor(vararg properties: String): List<String> = document.properties("").keys.mapNotNull { flat ->
        properties.firstNotNullOfOrNull { property ->
            if (flat.endsWith(".$property")) flat.removeSuffix(".$property") else null
        }
    }.distinct()
    fun rootProperties(): Set<String> = document.properties("").keys
    internal fun claims(): Map<Pair<String, String>, SettingKey<*>> = claimed.toMap()

    /** A single implementation supplies the narrow read and write views. */
    private class IniAttributes(
        private val filename: String,
        private val document: IniDocument,
        private val section: String,
        private val owner: SettingKey<*>,
        private val claims: MutableMap<Pair<String, String>, SettingKey<*>>,
    ) : IniReadableAttributes, IniWritableAttributes {
        override val keys: Set<String> get() = (document.properties(section).keys +
            document.properties("").keys.mapNotNull { it.removePrefix("$section.").takeIf { part ->
                part != it && part.isNotEmpty()
            } }).toSet()

        override fun get(name: String): String? {
            require(name.isNotEmpty()) { "$filename [$section]: empty attribute name" }
            val canonical = document.get(section, name)
            val root = document.get("", "$section.$name")
            require(canonical == null || root == null || canonical == root) {
                "$filename: conflicting [$section] $name and root $section.$name"
            }
            checkClaim(claims, section, name, owner, filename)
            return canonical ?: root
        }

        override fun set(name: String, value: String) {
            checkClaim(claims, section, name, owner, filename)
            document.remove("", "$section.$name")
            document.put(section, name, value)
        }

        override fun remove(name: String) {
            checkClaim(claims, section, name, owner, filename)
            document.remove("", "$section.$name")
            document.remove(section, name)
        }
    }

    internal companion object {
        fun writable(
            filename: String, document: IniDocument, section: String, owner: SettingKey<*>,
            claims: MutableMap<Pair<String, String>, SettingKey<*>>,
        ): IniWritableAttributes = IniAttributes(filename, document, section, owner, claims)
    }
}

class IniWriteContext internal constructor(
    val snapshot: ConfigChanges,
    private val document: IniDocument,
    val changedKeys: Set<SettingKey<*>>,
    private val filename: String = "configuration.ini",
    private val claims: MutableMap<Pair<String, String>, SettingKey<*>> = linkedMapOf(),
    private val key: SettingKey<*>? = null,
    private val definition: Settings? = null,
) {
    fun forKey(key: SettingKey<*>): IniWriteContext {
        requireOwner(key)
        return IniWriteContext(snapshot, document, changedKeys, filename, claims, key, definition)
    }
    private fun requireOwner(key: SettingKey<*>) {
        require(definition == null || (key.group.configuration === definition && key in definition.allSettingKeys)) {
            "$filename: ${key.path} does not belong to ${definition?.id}"
        }
    }
    fun write(child: String, value: String?) {
        val owner = requireNotNull(key) { "A setting key is required for relative INI writes" }
        writeAttributes(owner, owner.group.id) { attrs ->
            val property = owner.name + if (child.isEmpty()) "" else ".$child"
            if (value == null) attrs.remove(property) else attrs[property] = value
        }
    }
    fun <T> writeAttributes(key: SettingKey<T>, section: String, block: (IniWritableAttributes) -> Unit) {
        requireOwner(key)
        require(key in changedKeys) { "$filename: ${key.path} was not changed" }
        block(IniReadContext.writable(filename, document, section, key, claims))
    }
    fun <T> removeSection(key: SettingKey<T>, section: String) {
        requireOwner(key)
        require(key in changedKeys) { "$filename: ${key.path} was not changed" }
        require(section.isNotEmpty()) { "$filename: cannot remove root section" }
        document.properties(section).keys.forEach { property ->
            val previous = claims[section to property]
            require(previous === key) {
                "$filename [$section] $property: cannot remove attribute owned by ${previous?.path ?: "unknown key"}"
            }
        }
        // Root-flat properties at this logical location are subject to the same check.
        document.properties("").keys.filter { it.startsWith("$section.") }.forEach { flat ->
            val property = flat.removePrefix("$section.")
            val previous = claims[section to property]
            require(previous === key) {
                "$filename [$section] $property: cannot remove attribute owned by ${previous?.path ?: "unknown key"}"
            }
            document.remove("", flat)
        }
        document.removeSection(section)
        claims.keys.removeAll { it.first == section && claims[it] === key }
    }
    fun ownedSections(key: SettingKey<*>): List<String> = claims.entries.mapNotNull { (location, owner) ->
        location.first.takeIf { owner === key &&
            (document.properties(it).isNotEmpty() || document.properties("").keys.any { flat -> flat.startsWith("$it.") }) }
    }.distinct()
    /** Uses mapping claims, including composite child names and actual dynamic sections. */
    internal fun synchronizeDocumentation(definitions: Collection<Settings>) {
        val keys = definitions.flatMap { it.allSettingKeys }
        document.sections().forEach { section ->
            val groups = keys.map { it.group }.distinct().filter { it.id == section }.sortedBy { it.configuration?.id }
            val descriptions = groups.mapNotNull { it.description?.takeIf(String::isNotBlank) }.distinct().toMutableList()
            document.properties(section).keys.forEach { property ->
                val owner = claims[section to property]
                if (owner != null && !owner.structural) {
                    owner.description?.takeIf(String::isNotBlank)?.let { description ->
                        val parts = description.lines()
                        descriptions += "$property: ${parts.first()}" +
                            parts.drop(1).joinToString("") { "\n  $it" }
                    }
                }
            }
            // Only sections belonging to these definitions may have their blocks synchronized.
            if (groups.isNotEmpty() || claims.any { it.key.first == section && it.value in keys }) {
                document.synchronizeSectionDescription(section, descriptions.joinToString("\n"))
            }
        }
        definitions.sortedBy { it.id }.forEach { definition ->
            val owned = claims.filterValues { it.group.configuration === definition }.keys
            val description = (listOfNotNull(definition.description) + definition.allSettingKeys
                .filter { it.structural }.mapNotNull { it.description })
                .filter(String::isNotBlank).distinct().joinToString("\n")
            document.synchronizeSettingsDescription(definition.id, description,
                owned.map { it.first }.toSet(), owned.map { "${it.first}.${it.second}" }.toSet())
        }
    }
    internal fun claims(): Map<Pair<String, String>, SettingKey<*>> = claims.toMap()
}
