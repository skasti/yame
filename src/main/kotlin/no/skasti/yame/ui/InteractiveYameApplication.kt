package no.skasti.yame.ui

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.config.*
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.logging.*
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig
import no.skasti.yame.ppp.proxy.YameRegistries
import no.skasti.yame.runtime.YameRuntime
import no.skasti.yame.observer.YameEvents
import no.skasti.yame.serial.*

/** Presentation composition root; runtime and stores are also usable without the TUI. */
class InteractiveYameApplication internal constructor(
    val configStore: ConfigStore,
    val functions: FunctionStore = FunctionStore.current,
    terminal: Terminal,
    logManager: YameLogManager,
    resourceGraphs: NavigationResourceRegistry,
    portProvider: () -> List<SerialPortDescriptor> = SerialConnection::availablePortDescriptors,
) : AutoCloseable {
    constructor(
        configStore: ConfigStore,
        functions: FunctionStore = FunctionStore.current,
        terminal: Terminal,
        logManager: YameLogManager,
        portProvider: () -> List<SerialPortDescriptor> = SerialConnection::availablePortDescriptors,
    ) : this(
        configStore = configStore,
        functions = functions,
        terminal = terminal,
        logManager = logManager,
        resourceGraphs = YameRegistries.navigationResources(
            PppHttpCompatibilityConfig.fromConfigStore(configStore),
        ),
        portProvider = portProvider,
    )

    private val observer = TuiYameObserver(configStore, functions, terminal)
    // Register the existing observation consumer before runtime construction can start producers.
    private val observations = YameEvents.subscribe("tui-runtime", handler = observer::onEvent)
    private val runtime = YameRuntime(
        configStore = configStore,
        functions = functions,
        logManager = logManager,
        resourceGraphs = resourceGraphs,
        portProvider = portProvider,
    )

    fun run() { observer.start(); runtime.run() }
    internal fun stop() = runtime.close()

    override fun close() {
        runtime.close()
        observations.close()
        observer.close()
    }

    internal fun resourceGraphProjectionSnapshot(): Map<Long, Map<java.net.URI, DashboardHttpResource>> =
        observer.resourceGraphProjectionSnapshot()

    internal fun resourceGraphMetadataSnapshot(): Map<Long, DashboardHttpGraphMetadata> =
        observer.resourceGraphMetadataSnapshot()

    internal fun httpHostProjectionSnapshot(): List<DashboardHttpHost> = observer.httpHostProjectionSnapshot()
}
