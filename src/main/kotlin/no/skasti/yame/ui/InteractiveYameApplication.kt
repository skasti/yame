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
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Presentation composition root; runtime and stores are also usable without the TUI. */
class InteractiveYameApplication internal constructor(
    val configStore: ConfigStore,
    val functions: FunctionStore,
    terminal: Terminal,
    logManager: YameLogManager,
    private val resourceGraphs: NavigationResourceRegistry,
    portProvider: () -> List<SerialPortDescriptor> = SerialConnection::availablePortDescriptors,
) : AutoCloseable {
    constructor(
        configStore: ConfigStore,
        functions: FunctionStore,
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
    private val resourceUiExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "yame-resource-ui").apply { isDaemon = true }
    }
    private val resourceRegistryHooks: ResourceRegistryHooks = resourceGraphs.hooks
    private val resourceUiLifecycle = ReentrantLock()
    private val resourceUiIdle = resourceUiLifecycle.newCondition()
    private var resourceUiClosed = false
    private var activeResourceUiTasks = 0
    private val onRootAdded: (ResourceRegistryRoot) -> Unit =
        { root -> refreshResourceGraph(root.graphId) }
    private val onRootRemoved: (ResourceRegistryRoot) -> Unit =
        { root -> refreshResourceGraph(root.graphId) }
    private val onRootUsed: (ResourceRegistryRoot) -> Unit =
        { root -> refreshResourceGraph(root.graphId, rootUsed = true) }
    private val onResourceAdded: (ResourceRegistryResource) -> Unit =
        { resource -> refreshResourceGraph(resource.graphId) }
    private val onResourceUpdated: (ResourceRegistryResource) -> Unit =
        { resource -> refreshResourceGraph(resource.graphId) }
    private val onResourceRemoved: (ResourceRegistryResource) -> Unit =
        { resource -> refreshResourceGraph(resource.graphId) }

    init {
        resourceRegistryHooks.onRootAdded += onRootAdded
        resourceRegistryHooks.onRootRemoved += onRootRemoved
        resourceRegistryHooks.onRootUsed += onRootUsed
        resourceRegistryHooks.onResourceAdded += onResourceAdded
        resourceRegistryHooks.onResourceUpdated += onResourceUpdated
        resourceRegistryHooks.onResourceRemoved += onResourceRemoved

        // Subscribe first, then snapshot while holding the observer monitor. Events queued
        // during startup reconcile from the live registry after this snapshot is applied.
        synchronized(observer) {
            observer.synchronizeResourceGraphs(resourceGraphs.snapshots())
        }
    }

    private val runtime = YameRuntime(
        observer = observer,
        configStore = configStore,
        functions = functions,
        logManager = logManager,
        resourceGraphs = resourceGraphs,
        portProvider = portProvider,
    )

    fun run() { observer.start(); runtime.run() }
    override fun close() {
        closeResourceUiCallbacks()
        runtime.close()
        observer.close()
    }

    internal fun resourceGraphProjectionSnapshot(): Map<Long, Map<java.net.URI, DashboardHttpResource>> =
        observer.resourceGraphProjectionSnapshot()

    private fun refreshResourceGraph(graphId: Long, rootUsed: Boolean = false) {
        submitResourceUi {
            synchronized(observer) {
                observer.synchronizeResourceGraph(
                    graphId = graphId,
                    snapshot = resourceGraphs.snapshot(graphId),
                    rootUsed = rootUsed,
                )
            }
        }
    }

    private fun submitResourceUi(action: () -> Unit) {
        try {
            resourceUiExecutor.execute {
                val accepted = resourceUiLifecycle.withLock {
                    if (resourceUiClosed) {
                        false
                    } else {
                        activeResourceUiTasks++
                        true
                    }
                }
                if (!accepted) return@execute

                try {
                    action()
                } finally {
                    resourceUiLifecycle.withLock {
                        activeResourceUiTasks--
                        if (activeResourceUiTasks == 0) resourceUiIdle.signalAll()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // A callback can have been captured before unsubscription and arrive after shutdown.
        }
    }

    private fun closeResourceUiCallbacks() {
        val closeCallbacks = resourceUiLifecycle.withLock {
            if (resourceUiClosed) {
                false
            } else {
                resourceUiClosed = true
                true
            }
        }
        if (closeCallbacks) {
            resourceRegistryHooks.onRootAdded -= onRootAdded
            resourceRegistryHooks.onRootRemoved -= onRootRemoved
            resourceRegistryHooks.onRootUsed -= onRootUsed
            resourceRegistryHooks.onResourceAdded -= onResourceAdded
            resourceRegistryHooks.onResourceUpdated -= onResourceUpdated
            resourceRegistryHooks.onResourceRemoved -= onResourceRemoved
            resourceUiExecutor.shutdownNow()
        }

        var interrupted = false
        resourceUiLifecycle.lock()
        try {
            while (activeResourceUiTasks > 0) {
                try {
                    resourceUiIdle.await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            resourceUiLifecycle.unlock()
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
