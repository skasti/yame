package no.skasti.yame.observer

/** Pending budgets exclude the single event currently being dispatched. */
data class EventBrokerConfig(
    val capacity: Int = 4096,
    val payloadCapacity: Long = 16L * 1024 * 1024,
    val maxEventBytes: Long = 128L * 1024,
    val maxSubscribers: Int = 64,
) {
    init {
        require(capacity > 0 && payloadCapacity > 0 && maxEventBytes > 0 && maxSubscribers > 0)
        require(maxEventBytes <= payloadCapacity)
    }
}
