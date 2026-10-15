package no.skasti.yame.ui

/** Resolve either local commands or a typed path, without executing actions while filtering. */
internal fun registeredCommandPalette(input: String, initialPath: String = "/", registry: CommandPalette = CommandPalette.global): TuiCommandPalette {
    var path = initialPath
    var palette = registry.find(path) ?: requireNotNull(registry.find("/")).also { path = "/" }
    var query = input.removePrefix("/")
    while (true) {
        val separator = query.indexOfFirst { it == '/' || it == ' ' }
        if (separator < 1) break
        val child = palette.items().firstOrNull {
            it.command.removePrefix("/").equals(query.substring(0, separator), true)
        } as? Palette ?: break
        if (path.count { it == '/' } >= 16) break
        path = path.trimEnd('/') + "/" + paletteCommandKey(child.command)
        palette = child
        query = query.substring(separator + 1).trimStart()
    }
    return TuiCommandPalette(
        mode = TuiPaletteMode.COMMANDS,
        input = "/$query",
        title = palette.title,
        options = palette.items().filter {
            it.command.removePrefix("/").startsWith(query, true)
        }.map { TuiCommandOption(it.command, it.description, it.command) },
        selectedIndex = 0,
        palettePath = path,
    )
}
