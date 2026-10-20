package no.skasti.yame.observer

/** Explicit process installation, with no hidden worker, replacement or test reset. */
object YameEvents {
    private val installation = BrokerInstallation()
    val current: EventBroker get() = installation.current
    fun install(broker: EventBroker): AutoCloseable = installation.install(broker)
    fun publish(event: YameEvent): Long = current.publish(event)
    fun subscribe(name: String, filter: (YameEvent) -> Boolean = { true }, handler: (YameEvent) -> Unit): AutoCloseable =
        current.subscribe(name, filter, handler)
}

/** Isolated instances test installation semantics without touching the process singleton. */
internal class BrokerInstallation {
    @Volatile private var installed: EventBroker? = null
    val current: EventBroker get() = checkNotNull(installed) { "YameEvents has not been installed" }

    @Synchronized fun install(broker: EventBroker): AutoCloseable {
        check(installed == null) { "YameEvents is already installed" }
        check(!broker.status.closing) { "Cannot install a closing broker" }
        installed = broker
        var active = true
        return AutoCloseable {
            synchronized(this) {
                if (active) {
                    installed = null
                    active = false
                }
            }
        }
    }
}
