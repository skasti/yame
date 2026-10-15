package no.skasti.yame.ui

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.config.*
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.logging.*
import no.skasti.yame.ppp.proxy.ResourceRegistryHooks
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
    private val resourceRegistryHooks = ResourceRegistryHooks { task -> resourceUiExecutor.execute(task) }.apply {
        onRootAdded += observer::handleResourceRootAdded
        onRootRemoved += observer::handleResourceRootRemoved
        onRootUsed += observer::handleResourceRootUsed
        onResourceAdded += observer::handleResourceAdded
        onResourceUpdated += observer::handleResourceUpdated
        onResourceRemoved += observer::handleResourceRemoved
    }
    private val runtime = YameRuntime(
        observer, configStore, functions, resourceRegistryHooks, logManager, portProvider,
    )

    fun run() { observer.start(); runtime.run() }
    override fun close() {
        runtime.close()
        observer.close()
        resourceUiExecutor.shutdownNow()
    }
}
