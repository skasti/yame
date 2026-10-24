package no.skasti.yame.observer

import java.time.Duration

/** Process-wide entry point. The first access creates a broker unless one was installed first. */
object YameEvents {
    @Volatile private var applicationShutdown: (() -> Unit)? = null
    private val installation = BrokerInstallation(onDefaultCreated = { broker ->
        Runtime.getRuntime().addShutdownHook(Thread({
            val coordinated = applicationShutdown
            if (coordinated != null) coordinated() else {
                broker.close()
                broker.awaitClosed(Duration.ofSeconds(5))
            }
        }, "yame-events-shutdown"))
    })

    val current: EventBroker get() = installation.current
    fun install(broker: EventBroker): AutoCloseable = installation.install(broker)
    fun publish(event: YameEvent): Long = current.publish(event)
    fun subscribe(name: String, filter: (YameEvent) -> Boolean = { true }, handler: (YameEvent) -> Unit): AutoCloseable =
        current.subscribe(name, filter, handler)

    /** Let the fallback hook participate in the same application shutdown, rather
     * than racing producer quiescence by closing broker admission prematurely. */
    internal fun coordinateShutdown(action: () -> Unit): AutoCloseable {
        check(applicationShutdown == null)
        applicationShutdown = action
        return AutoCloseable { if (applicationShutdown === action) applicationShutdown = null }
    }

    /** Final application shutdown, after producer quiescence and broker drain. */
    fun shutdown() = installation.shutdown()
}

/** One lock decides first access, explicit installation, release and final shutdown. */
internal class BrokerInstallation(
    private val createDefault: () -> EventBroker = { EventBroker() },
    private val onDefaultCreated: (EventBroker) -> Unit = {},
) {
    private var selected: EventBroker? = null
    private var defaultOwned = false
    private var lazyAllowed = true
    private var stopped = false

    val current: EventBroker
        @Synchronized get() {
            if (stopped || (!lazyAllowed && selected == null)) throw BrokerClosedException()
            selected?.let { return it }
            val broker = createDefault()
            try {
                onDefaultCreated(broker)
            } catch (error: Throwable) {
                broker.close()
                throw error
            }
            selected = broker
            defaultOwned = true
            return broker
        }

    @Synchronized fun install(broker: EventBroker): AutoCloseable {
        if (stopped || selected != null || broker.status.closing) {
            if (broker !== selected) broker.close()
            throw IllegalStateException("YameEvents already has a broker or has shut down")
        }
        selected = broker
        defaultOwned = false
        lazyAllowed = false
        var released = false
        return AutoCloseable {
            synchronized(this) {
                if (!released) {
                    check(broker.status.terminated) { "Close and await the installed broker before releasing it" }
                    check(selected === broker) { "Broker installation changed unexpectedly" }
                    selected = null
                    released = true
                }
            }
        }
    }

    @Synchronized fun shutdown() {
        if (stopped) return
        stopped = true
        if (defaultOwned) selected?.close()
    }
}
