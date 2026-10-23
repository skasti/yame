package no.skasti.yame.config

import no.skasti.yame.config.ini.IniDocument

/** Feature-owned setting definitions and mappings, not the current values held by ConfigStore. */
abstract class Settings(val id: String, val file: String? = null) {
    init {
        require(id.isNotBlank())
        require(file == null || file.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))) {
            "Invalid logical configuration file name: $file"
        }
    }

    abstract val bindings: List<IniBinding<*>>

    open fun loadFromIni(context: IniReadContext): List<ConfigChange<*>> =
        bindings.map { it.load(context) }

    open fun saveToIni(context: IniWriteContext) {
        bindings.forEach { it.save(context) }
    }
}

/** Use a separate file named after this definition; the repository chooses its extension. */
abstract class SeparateSettings(id: String) : Settings(id = id, file = id)

/** Null is a valid default; a supplier differentiates it from an absent binding. */
class IniBinding<T>(
    val key: SettingKey<T>,
    val property: String,
    val default: () -> T,
    val decode: (String) -> T,
    /** Returning null removes an optional property instead of writing an empty value. */
    val encode: (T) -> String?,
    val section: String = key.group.iniSection,
    val persist: Boolean = true,
) {
    fun load(context: IniReadContext): ConfigChange<T> {
        val raw = context.read(section, property)
        try {
            return (key to (raw?.let(decode) ?: default())).also { it.validate() }
        } catch (error: Exception) {
            throw IllegalArgumentException("${context.filename} [$section] $property: ${error.message}", error)
        }
    }

    fun save(context: IniWriteContext) {
        if (!persist || key !in context.changedKeys) return
        val value = requireNotNull(context.snapshot[key]) { "Missing ${key.path}" }.value
        encode(value)?.let { context.write(section, property, it) } ?: context.remove(section, property)
    }
}

class IniReadContext internal constructor(val filename: String, val document: IniDocument, val existed: Boolean) {
    private val claimed = mutableSetOf<Pair<String, String>>()
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
) {
    fun write(section: String, property: String, value: String) = document.put(section, property, value)
    fun remove(section: String, property: String) = document.remove(section, property)
    fun removeSection(section: String) = document.removeSection(section)
    fun sections(): List<String> = document.sections()
    fun properties(section: String): Map<String, String> = document.properties(section)
}
