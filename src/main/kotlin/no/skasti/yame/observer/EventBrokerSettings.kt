package no.skasti.yame.observer

import no.skasti.yame.config.SettingGroup
import no.skasti.yame.config.SettingKey
import no.skasti.yame.config.Settings

/** Event delivery settings, persisted in the primary yame.ini file. */
object EventBrokerSettings : Settings("events") {
    const val DefaultQueueCapacity = 4096
    val Group = SettingGroup(this)
    const val DefaultPublishTimeoutMillis = 10
    const val DefaultMaxSubscribers = 64
    val PublishTimeoutMillis = SettingKey<Int>(
        "publish-timeout-ms",
        Group,
        { require(it in 0..60_000) { "Event broker publish timeout must be between 0 and 60000 milliseconds" } },
        description = "Maximum wait for a free broker queue slot before a fatal overload error.",
        default = { DefaultPublishTimeoutMillis },
    )
    val MaxSubscribers = SettingKey<Int>(
        "max-subscribers",
        Group,
        { require(it in 1..4096) { "Event broker max subscribers must be between 1 and 4096" } },
        description = "Maximum number of registered event subscribers.",
        default = { DefaultMaxSubscribers },
    )
    val QueueCapacity = SettingKey<Int>(
        "queue-capacity",
        Group,
        { require(it in 1..65_536) { "Event broker queue capacity must be between 1 and 65536 events" } },
        description = "Maximum pending YAME events before publishers wait. Changes take effect immediately without dropping accepted events.",
        default = { DefaultQueueCapacity },
    )
}
