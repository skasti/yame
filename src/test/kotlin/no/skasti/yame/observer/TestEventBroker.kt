package no.skasti.yame.observer

import java.time.Duration

/** Standalone broker tests avoid installing process-global configuration. */
internal fun testEventBroker(
    capacity: Int = EventBrokerSettings.DefaultQueueCapacity,
    publishTimeout: Duration = Duration.ofMillis(EventBrokerSettings.DefaultPublishTimeoutMillis.toLong()),
    maxSubscribers: Int = EventBrokerSettings.DefaultMaxSubscribers,
): EventBroker = EventBroker(
    capacity = capacity,
    publishTimeout = publishTimeout,
    maxSubscribers = maxSubscribers,
)
