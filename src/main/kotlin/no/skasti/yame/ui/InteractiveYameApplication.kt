package no.skasti.yame.ui

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.config.*
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.logging.*
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.ppp.proxy.ResourceRegistryHooks
import no.skasti.yame.runtime.YameRuntime
import no.skasti.yame.serial.*
import java.util.concurrent.Executors

/** Presentation composition root; runtime and stores are also usable without the TUI. */
class InteractiveYameApplication(
    initialPortName: String?,
    initialBaud: Int,
    initialFlowControl: SerialFlowControl,
    initialModemConfig: HayesModemConfig,
    terminal: Terminal,
    logManager: YameLogManager,
    initialLogLevels: Map<YameLogModule, YameLogLevel> = YameLogManager.defaultLevels(),
    onConfigurationChanged: (YameConfiguration) -> Unit = {},
    portProvider: () -> List<SerialPortDescriptor> = SerialConnection::availablePortDescriptors,
    initialSavedConfiguration: YameConfiguration = YameConfiguration(
        initialPortName, initialBaud, initialFlowControl, initialModemConfig, logManager.captureLimits, initialLogLevels,
    ),
) : AutoCloseable {
    val configStore = applicationConfigStore(
        initialSavedConfiguration,
        YameConfiguration(initialPortName, initialBaud, initialFlowControl, initialModemConfig, logManager.captureLimits, initialLogLevels),
        onConfigurationChanged,
        { error -> logManager.logger(YameLogModule.MODEM)("Configuration subscriber failed: ${error.javaClass.simpleName}") },
    )
    val functions = FunctionStore()
    private val observer = TuiYameObserver(
        initialPortName, initialBaud, initialFlowControl,
        initialModemConfig.pppDnsConfig.upstreamServer.toString(),
        initialModemConfig.pppHttpCompatibilityConfig.enabled, initialModemConfig.toneSimulationEnabled,
        initialCaptureLimits = logManager.captureLimits, initialLogLevels = initialLogLevels,
        terminal = terminal, configStore = configStore, functions = functions,
    )
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
        initialPortName, initialBaud, initialFlowControl, initialModemConfig,
        observer, configStore, functions, resourceRegistryHooks, logManager, initialLogLevels, portProvider,
    )

    fun run() { observer.start(); runtime.run() }
    override fun close() {
        runtime.close()
        observer.close()
        resourceUiExecutor.shutdownNow()
    }
}
