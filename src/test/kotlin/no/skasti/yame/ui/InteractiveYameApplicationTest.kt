package no.skasti.yame.ui

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.observer.*
import no.skasti.yame.persistence.YameConfiguration
import no.skasti.yame.persistence.applicationConfigStore
import no.skasti.yame.ppp.proxy.*
import no.skasti.yame.ppp.proxy.transform.ResourceTransformationSummary
import no.skasti.yame.recording.RecordingResourceView
import java.net.URI
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class InteractiveYameApplicationTest {
    private lateinit var broker: EventBroker
    private lateinit var installation: AutoCloseable
    private val timeout = Duration.ofSeconds(5)
    @BeforeTest fun install() {
        broker = EventBroker()
        installation = YameEvents.install(broker)
    }
    @AfterTest fun release() {
        broker.close()
        assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(timeout))
        installation.close()
    }
    private fun drain() = assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(timeout))

    private fun withApp(registry: NavigationResourceRegistry, test: (InteractiveYameApplication) -> Unit) {
        val directory = Files.createTempDirectory("yame-tui-events")
        try {
            YameLogManager(YameLogManager.defaultLevels(), logDirectory = directory).use { logs ->
                InteractiveYameApplication(
                    configStore = applicationConfigStore(YameConfiguration(portName = "COM1")),
                    functions = FunctionStore(), terminal = Terminal(interactive = false),
                    logManager = logs, resourceGraphs = registry, portProvider = { emptyList() },
                ).use(test)
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `early TUI and independent public-payload consumer reconstruct the injected registry observations`() {
        val registry = NavigationResourceRegistry(maxContexts = 2)
        val recorder = RecordingResourceView()
        broker.subscribe("independent-view", handler = recorder::accept).use {
            withApp(registry) { app ->
                val root = URI("http://tui-events.test/page")
                val image = URI("http://cdn.test/image.gif")
                val upstream = URI("https://cdn.test/image.gif")
                val graph = registry.startNavigation(root)
                registry.discover(graph, root, image, upstream, ResourceRelation.IMG_SRC, ResourceKind.IMAGE)
                registry.discover(graph, URI("http://tui-events.test/frame"), image, upstream,
                    ResourceRelation.CSS_URL, ResourceKind.IMAGE)
                val attempt = requireNotNull(registry.beginFetch(graph, image, upstream, upstream, prefetched = true))
                registry.recordTransformations(image, listOf(ResourceTransformationSummary("test", 100, 25)))
                assertTrue(registry.markFetchState(graph, attempt, image, upstream, upstream, ResourceState.READY))
                assertEquals(graph.id, registry.startNavigation(root).id)
                val second = registry.startNavigation(URI("http://second.test/page"))
                registry.discover(second, second.rootLegacyUri, image, upstream, ResourceRelation.IMG_SRC, ResourceKind.IMAGE)
                registry.recordTransformations(image, listOf(ResourceTransformationSummary("test", 100, 25)))
                drain()
                fun verify() {
                    val projected = app.resourceGraphProjectionSnapshot()
                    assertEquals(recorder.resources.keys, projected.keys)
                    recorder.resources.forEach { (id, resources) ->
                        val ui = projected.getValue(id)
                        assertEquals(resources.values.map { it.resourceLegacyUri }.toSet(), ui.keys)
                        resources.values.forEach { event ->
                            val resource = ui.getValue(event.resourceLegacyUri)
                            assertEquals(event.state, resource.state)
                            assertEquals(event.kind, resource.kind)
                            assertEquals(event.prefetched, resource.prefetched)
                            assertEquals(event.transformations, resource.transformations)
                        }
                    }
                }
                verify()
                assertEquals(2, recorder.associations.getValue(graph.id).size)
                assertEquals(ResourceState.READY, recorder.resources.getValue(graph.id).getValue(image.toString()).state)
                registry.startNavigation(URI("http://third.test/page")) // evicts graph, preserves second
                drain()
                verify()
                assertFalse(recorder.resources.containsKey(graph.id))
                assertTrue(recorder.resources.containsKey(second.id))
                val recreated = registry.startNavigation(root)
                drain()
                verify()
                assertNotEquals(graph.id, recreated.id)
            }
        }
    }

    @Test fun `intentionally late TUI receives no snapshot bootstrap`() {
        val registry = NavigationResourceRegistry()
        registry.startNavigation(URI("http://already-held.test/page"))
        drain()
        withApp(registry) { app ->
            drain()
            assertTrue(app.resourceGraphProjectionSnapshot().isEmpty())
            // Newly created graph lifetimes still project normally.
            val newGraph = registry.startNavigation(URI("http://new.test/page"))
            drain()
            assertEquals(setOf(newGraph.id), app.resourceGraphProjectionSnapshot().keys)
        }
    }

    @Test fun `already captured observation after close cannot update the TUI`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        broker.subscribe("pause-before-tui") {
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
        }.use {
            try {
                val registry = NavigationResourceRegistry()
                withApp(registry) { app ->
                    registry.startNavigation(URI("http://tui-shutdown.test/page"))
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    app.close()
                    resume.countDown()
                    drain()
                    assertTrue(app.resourceGraphProjectionSnapshot().isEmpty())
                }
            } finally { resume.countDown() }
        }
    }
}
