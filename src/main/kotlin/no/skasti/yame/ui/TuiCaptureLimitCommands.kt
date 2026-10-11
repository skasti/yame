package no.skasti.yame.ui

import no.skasti.yame.logging.PacketCaptureLimitKind

internal fun parseCaptureLimitInput(input: String): Pair<PacketCaptureLimitKind, Int>? {
    val parts = input.trim().split(Regex("\\s+"))
    if (parts.size != 2) return null
    val kind = PacketCaptureLimitKind.entries.firstOrNull {
        parts[0].equals("/${it.commandName}", ignoreCase = true)
    } ?: return null
    return runCatching { kind to kind.parse(parts[1]) }.getOrNull()
}
