package no.skasti.yame.ui

import java.util.Locale

sealed interface PaletteItem {
    val command: String
    val description: String
}

class PaletteChoice(
    override val command: String,
    private val describe: () -> String = { "" },
    val select: () -> Unit,
) : PaletteItem {
    override val description: String get() = describe()
}

class Palette(
    override val command: String,
    val title: String,
    override val description: String = "",
) : PaletteItem {
    private val entries = linkedMapOf<String, PaletteItem>()

    @Synchronized
    fun add(item: PaletteItem): AutoCloseable {
        val key = paletteCommandKey(item.command)
        require(item !== this) { "A palette cannot contain itself" }
        require(key !in entries) { "Duplicate command '${item.command}' in palette '$command'" }
        entries[key] = item
        return AutoCloseable {
            synchronized(this) {
                if (entries[key] === item) entries.remove(key)
            }
        }
    }

    @Synchronized
    fun items(): List<PaletteItem> = entries.values.toList()

    @Synchronized
    fun item(command: String): PaletteItem? = entries[paletteCommandKey(command)]

    @Synchronized
    internal fun remove(item: PaletteItem) {
        val key = paletteCommandKey(item.command)
        if (entries[key] === item) entries.remove(key)
    }
}

/** Process registry; registration handles release callbacks when their owning feature closes. */
object CommandPalette {
    private val root = Palette("/", "Commands")
    private val automaticPalettes = mutableSetOf<Palette>()

    fun add(item: PaletteItem): AutoCloseable = root.add(item)

    fun add(path: String, describe: () -> String = { "" }, action: () -> Unit): AutoCloseable = synchronized(root) {
        require(path.startsWith('/') && !path.endsWith('/') && !path.contains("//")) {
            "Command paths must be absolute and end with a command: $path"
        }
        val parts = path.drop(1).split('/').map(::paletteCommandKey)
        val ancestors = mutableListOf<Pair<Palette, Palette>>()
        var parent = root
        for (part in parts.dropLast(1)) {
            val existing = parent.item(part)
            require(existing == null || existing is Palette) { "Command '/$part' is a choice, not a palette" }
            val child = existing ?: Palette("/$part", part.replaceFirstChar { it.uppercase() }).also {
                parent.add(it)
                automaticPalettes += it
            }
            ancestors += parent to child
            parent = child
        }
        val leaf = parent.add(PaletteChoice(parts.last(), describe, action))
        AutoCloseable {
            synchronized(root) {
                leaf.close()
                for ((owner, child) in ancestors.asReversed()) {
                    if (child !in automaticPalettes || child.items().isNotEmpty()) break
                    owner.remove(child)
                    automaticPalettes.remove(child)
                }
            }
        }
    }

    fun find(path: String): Palette? = synchronized(root) {
        require(path.startsWith('/')) { "Palette paths must be absolute: $path" }
        var palette = root
        for (part in path.split('/').filter(String::isNotEmpty)) {
            palette = palette.item(part) as? Palette ?: return@synchronized null
        }
        palette
    }
}

internal fun paletteCommandKey(command: String): String {
    val key = command.removePrefix("/").lowercase(Locale.ROOT)
    require(Regex("[a-z][a-z0-9-]*").matches(key)) { "Invalid palette command '$command'" }
    return key
}
