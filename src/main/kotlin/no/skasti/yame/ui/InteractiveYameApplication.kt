package no.skasti.yame.ui

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.config.*
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.logging.*
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig
import no.skasti.yame.ppp.proxy.ResourceRegistryHooks
import no.skasti.yame.ppp.proxy.ResourceRegistryResource
import no.skasti.yame.ppp.proxy.ResourceRegistryRoot
import no.skasti.yame.ppp.proxy.YameRegistries
import no.skasti.yame.runtime.YameRuntime
import no.skasti.yame.serial.*
import java.util.concurrent.Executors

/** Presentation composition root; runtime and stores are also usable without the TUI. */
class InteractiveYameApplication(
    val configStore: ConfigStore,
    val functions: FunctionStore,
    terminal: Terminal,
    logManager: YameLogManager,
    portProvider: () -> List<SerialPortDescriptor> = SerialConnection::availablePortDescriptors,
) : AutoCloseable {
    private val observer = TuiYameObserver(configStore, functions, terminal)
    private val resourceUiExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "yame-resource-ui").apply { isDaemon = true }
    }
    private val resourceGraphs: NavigationResourceRegistry =
        YameRegistries.navigationResources(PppHttpCompatibilityConfig.fromSnapshot(configStore.snapshot()))
    private val resourceRegistryHooks: ResourceRegistryHooks = resourceGraphs.hooks
    private val onRootAdded: (ResourceRegistryRoot) -> Unit =
        { root -> resourceUiExecutor.execute { observer.handleResourceRootAdded(root) } }
    private val onRootRemoved: (ResourceRegistryRoot) -> Unit =
        { root -> resourceUiExecutor.execute { observer.handleResourceRootRemoved(root) } }
    private val onRootUsed: (ResourceRegistryRoot) -> Unit =
        { root -> resourceUiExecutor.execute { observer.handleResourceRootUsed(root) } }
    private val onResourceAdded: (ResourceRegistryResource) -> Unit =
        { resource -> resourceUiExecutor.execute { observer.handleResourceAdded(resource) } }
    private val onResourceUpdated: (ResourceRegistryResource) -> Unit =
        { resource -> resourceUiExecutor.execute { observer.handleResourceUpdated(resource) } }
    private val onResourceRemoved: (ResourceRegistryResource) -> Unit =
        { resource -> resourceUiExecutor.execute { observer.handleResourceRemoved(resource) } }

    init {
        resourceRegistryHooks.onRootAdded += onRootAdded
        resourceRegistryHooks.onRootRemoved += onRootRemoved
        resourceRegistryHooks.onRootUsed += onRootUsed
        resourceRegistryHooks.onResourceAdded += onResourceAdded
        resourceRegistryHooks.onResourceUpdated += onResourceUpdated
        resourceRegistryHooks.onResourceRemoved += onResourceRemoved
    }

    private val runtime = YameRuntime(
        observer = observer,
        configStore = configStore,
        functions = functions,
        logManager = logManager,
        resourceGraphs = resourceGraphs,
        resourceRegistryHooks = resourceRegistryHooks,
        portProvider = portProvider,
    )

    fun run() { observer.start(); runtime.run() }
    override fun close() {
        runtime.close()
        resourceRegistryHooks.onRootAdded -= onRootAdded
        resourceRegistryHooks.onRootRemoved -= onRootRemoved
        resourceRegistryHooks.onRootUsed -= onRootUsed
        resourceRegistryHooks.onResourceAdded -= onResourceAdded
        resourceRegistryHooks.onResourceUpdated -= onResourceUpdated
        resourceRegistryHooks.onResourceRemoved -= onResourceRemoved
        observer.close()
        resourceUiExecutor.shutdownNow()
    }
}
