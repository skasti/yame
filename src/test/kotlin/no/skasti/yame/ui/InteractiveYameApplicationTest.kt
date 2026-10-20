package no.skasti.yame.ui

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.persistence.YameConfiguration
import no.skasti.yame.persistence.applicationConfigStore
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.ppp.proxy.ResourceKind
import no.skasti.yame.ppp.proxy.ResourceRelation
import no.skasti.yame.ppp.proxy.ResourceRegistryHooks
import no.skasti.yame.ppp.proxy.ResourceState
import no.skasti.yame.serial.SerialPortDescriptor
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InteractiveYameApplicationTest {
    @Test
    fun `new TUI projects resources already held by the injected registry`() {
        val directory = Files.createTempDirectory("yame-tui-resource-sync")
        try {
            val registry = NavigationResourceRegistry()
            val root = URI("http://tui-sync.test/page")
            val image = URI("http://tui-sync.test/image.gif")
            val upstream = URI("https://origin.test/image.gif")
            val graph = registry.startNavigation(root)
            registry.discover(
                graph,
                graph.rootLegacyUri,
                image,
                upstream,
                ResourceRelation.IMG_SRC,
                ResourceKind.IMAGE,
            )
            val attempt = requireNotNull(registry.beginFetch(graph, image, upstream, upstream))
            assertTrue(
                registry.markFetchState(
                    graph,
                    attempt,
                    image,
                    upstream,
                    upstream,
                    ResourceState.READY,
                ),
            )

            YameLogManager(YameLogManager.defaultLevels(), logDirectory = directory).use { logs ->
                InteractiveYameApplication(
                    configStore = applicationConfigStore(YameConfiguration(portName = "COM1")),
                    functions = FunctionStore(),
                    terminal = Terminal(interactive = false),
                    logManager = logs,
                    resourceGraphs = registry,
                    portProvider = { emptyList<SerialPortDescriptor>() },
                ).use { app ->
                    val resources = app.resourceGraphProjectionSnapshot()[graph.id]
                    assertNotNull(resources)
                    assertEquals(ResourceState.READY, resources[image]?.state)
                    assertEquals(ResourceKind.IMAGE, resources[image]?.kind)
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `captured registry callbacks after shutdown do not submit work to the closed TUI`() {
        val directory = Files.createTempDirectory("yame-tui-resource-shutdown")
        try {
            val callbacks = ConcurrentLinkedQueue<() -> Unit>()
            val registry = NavigationResourceRegistry(
                hooks = ResourceRegistryHooks { callback -> callbacks.add(callback) },
            )
            YameLogManager(YameLogManager.defaultLevels(), logDirectory = directory).use { logs ->
                val app = InteractiveYameApplication(
                    configStore = applicationConfigStore(YameConfiguration(portName = "COM1")),
                    functions = FunctionStore(),
                    terminal = Terminal(interactive = false),
                    logManager = logs,
                    resourceGraphs = registry,
                    portProvider = { emptyList<SerialPortDescriptor>() },
                )

                registry.startNavigation(URI("http://tui-shutdown.test/page"))
                assertFalse(callbacks.isEmpty(), "registry dispatch should have captured subscribed callbacks")

                app.close()
                while (true) {
                    val callback = callbacks.poll() ?: break
                    callback()
                }

                assertTrue(app.resourceGraphProjectionSnapshot().isEmpty())
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
