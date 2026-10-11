package no.skasti.yame.ui

import no.skasti.yame.BuildInfo

/** Packs complete status fields into pages; elapsed time is supplied by the observer. */
internal object DashboardHeader {
    private const val PAGE_NANOS = 5_000_000_000L
    private const val PREFIX = "[ YAME "
    private const val SUFFIX = " ]"
    private const val SEPARATOR = "  •  "

    fun render(state: DashboardState, width: Int): String {
        val fields = listOf(
            BuildInfo.display,
            "${state.portName ?: "no port"} @ ${state.baud}",
            if (state.connected) "CONNECTED" else "NOT CONNECTED",
            state.flowControl.displayName,
            "DNS ${state.dnsUpstream}",
            "HTTP proxy ${if (state.httpProxyEnabled) "ON" else "OFF"}",
            "tones ${if (state.toneSimulationEnabled) "ON" else "OFF"}",
        ).map { field -> field.map { if (it.isISOControl()) ' ' else it }.joinToString("") }
        // Very small terminals still cycle through every character rather than losing fields.
        val framed = width > PREFIX.length + SUFFIX.length
        val capacity = if (framed) width - PREFIX.length - SUFFIX.length else width.coerceAtLeast(1)
        val pages = mutableListOf<String>()
        var page = ""
        for (field in fields) {
            for (part in field.chunked(capacity)) {
                val candidate = if (page.isEmpty()) part else page + SEPARATOR + part
                if (candidate.length > capacity) {
                    pages += page
                    page = part
                } else {
                    page = candidate
                }
            }
        }
        if (page.isNotEmpty()) pages += page
        val index = ((state.headerElapsedNanos.coerceAtLeast(0) / PAGE_NANOS) % pages.size).toInt()
        val text = if (framed) PREFIX + pages[index] + SUFFIX else pages[index]
        return text.padEnd(width.coerceAtLeast(1))
    }
}
