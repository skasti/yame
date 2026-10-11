package no.skasti.yame.logging

data class PacketCaptureLimits(
    val maxPerType: Int = 10,
    val maxTotal: Int = 1000,
) {
    init {
        require(maxPerType > 0) { "Capture limit per type must be a positive integer" }
        require(maxTotal > 0) { "Total capture limit must be a positive integer" }
    }
}

enum class PacketCaptureLimitKind(val commandName: String, val configKey: String) {
    PER_TYPE("capture-limit-per-type", "capture.max-per-type"),
    TOTAL("capture-limit-total", "capture.max-total");

    fun value(limits: PacketCaptureLimits): Int = when (this) {
        PER_TYPE -> limits.maxPerType
        TOTAL -> limits.maxTotal
    }

    fun apply(limits: PacketCaptureLimits, value: Int): PacketCaptureLimits = when (this) {
        PER_TYPE -> limits.copy(maxPerType = value)
        TOTAL -> limits.copy(maxTotal = value)
    }

    fun parse(value: String): Int = value.toIntOrNull()?.takeIf { it > 0 }
        ?: error("$commandName requires a positive integer")
}
