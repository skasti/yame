package no.skasti.yame.observer

/** Capacity counts pending events; the event currently being dispatched is excluded. */
data class EventBrokerConfig(
    val capacity: Int = 4096,
    val publishTimeout: java.time.Duration = java.time.Duration.ofMillis(10),
    val maxSubscribers: Int = 64,
) {
    init {
        require(capacity > 0 && maxSubscribers > 0 && !publishTimeout.isNegative)
    }
}
