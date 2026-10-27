package no.skasti.yame.ppp.proxy.transform

enum class PngCompatibilityMode(val configValue: String) {
    OPTIMIZE("optimize"),
    REQUIRE_LEGACY_FORMAT("require-legacy-format");

    companion object {
        fun parse(value: String): PngCompatibilityMode =
            entries.firstOrNull { it.configValue.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unsupported PNG compatibility mode '$value'")
    }
}
