package no.skasti.yame.ui

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.config.ConfigStore
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.observer.YameEvents
import no.skasti.yame.lifecycle.FatalFailureSource
import no.skasti.yame.lifecycle.DeadlineCloseable
import no.skasti.yame.presentation.Presentation

/** TUI presentation only; application bootstrap owns runtime and shared stores. */
class InteractiveYameApplication(
    val configStore: ConfigStore = ConfigStore.current,
    val functions: FunctionStore = FunctionStore.current,
    terminal: Terminal,
) : Presentation, FatalFailureSource, DeadlineCloseable {
    private val observer = TuiYameObserver(configStore, functions, terminal)
    private val observations = try {
        YameEvents.subscribe("tui-runtime", handler = observer::onEvent)
    } catch (error: Throwable) {
        observer.close()
        throw error
    }

    override val fatalFailure: Throwable? get() = observer.fatalFailure

    override fun start() = observer.start()

    override fun close() {
        try { observations.close() } finally { observer.close() }
    }

    override fun closeBefore(deadlineNanos: Long) {
        try { observations.close() } finally { observer.closeBefore(deadlineNanos) }
    }

    internal fun resourceGraphProjectionSnapshot(): Map<Long, Map<java.net.URI, DashboardHttpResource>> =
        observer.resourceGraphProjectionSnapshot()

    internal fun resourceGraphMetadataSnapshot(): Map<Long, DashboardHttpGraphMetadata> =
        observer.resourceGraphMetadataSnapshot()

    internal fun httpHostProjectionSnapshot(): List<DashboardHttpHost> = observer.httpHostProjectionSnapshot()
}
