package no.skasti.yame.lifecycle

import no.skasti.yame.logging.DiagnosticMessage
import no.skasti.yame.observer.EventBroker
import no.skasti.yame.presentation.Presentation
import no.skasti.yame.runtime.RuntimeSession
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class ApplicationBootstrapTest {
    private class StartupFailure : RuntimeException()

    @Test fun `subscriptions precede runtime construction and drain before presentation close`() {
        val broker = EventBroker()
        val order = CopyOnWriteArrayList<String>()
        val lifecycle = lifecycle(broker, order)
        ApplicationBootstrap(lifecycle).run(
            createPresentation = {
                order += "subscribe"
                val subscription = broker.subscribe("presentation") { order += (it as DiagnosticMessage).message }
                object : Presentation {
                    override fun start() { order += "start-presentation" }
                    override fun close() {
                        assertTrue(broker.status.terminated)
                        subscription.close()
                        order += "close-presentation"
                    }
                }
            },
            createRuntime = {
                order += "construct-runtime"
                broker.publish(DiagnosticMessage("construction-event"))
                object : RuntimeSession {
                    override fun run() {
                        assertTrue("start-presentation" in order)
                        order += "run"
                    }
                    override fun close() {
                        order += "stop-runtime"
                        broker.publish(DiagnosticMessage("final-event"))
                    }
                }
            },
        )
        lifecycle.close() // The outer shutdown hook/finally may call this again.
        assertEquals(1, order.count { it == "stop-runtime" })
        assertEquals(1, order.count { it == "close-presentation" })
        assertTrue(order.indexOf("subscribe") < order.indexOf("construct-runtime"))
        assertTrue(order.indexOf("construct-runtime") < order.indexOf("start-presentation"))
        assertTrue(order.indexOf("start-presentation") < order.indexOf("run"))
        assertTrue(order.indexOf("construction-event") < order.indexOf("final-event"))
        assertTrue(order.indexOf("stop-runtime") < order.indexOf("final-event"))
        assertEquals(listOf("close-presentation", "shared", "seal"), order.takeLast(3))
    }

    @Test fun `failure at each startup stage releases only constructed components once`() {
        for (stage in listOf("presentation", "runtime", "start", "run")) {
            val broker = EventBroker()
            val order = CopyOnWriteArrayList<String>()
            val lifecycle = lifecycle(broker, order)
            val failure = StartupFailure()
            assertSame(failure, assertFailsWith<StartupFailure> {
                ApplicationBootstrap(lifecycle).run(
                    createPresentation = {
                        if (stage == "presentation") throw failure
                        object : Presentation {
                            override fun start() { if (stage == "start") throw failure }
                            override fun close() { order += "presentation" }
                        }
                    },
                    createRuntime = {
                        if (stage == "runtime") throw failure
                        object : RuntimeSession {
                            override fun run() { if (stage == "run") throw failure }
                            override fun close() { order += "runtime" }
                        }
                    },
                )
            })
            lifecycle.close()
            val expected = when (stage) {
                "presentation" -> emptyList()
                "runtime" -> listOf("presentation")
                else -> listOf("runtime", "presentation")
            } + listOf("shared", "seal")
            assertEquals(expected, order.toList(), stage)
            assertTrue(broker.status.terminated)
        }
    }

    @Test fun `headless runtime follows the same lifecycle without creating a presentation`() {
        val broker = EventBroker()
        val order = CopyOnWriteArrayList<String>()
        ApplicationBootstrap(lifecycle(broker, order)).run(
            createPresentation = { null },
            createRuntime = {
                object : RuntimeSession {
                    override fun run() { order += "run" }
                    override fun close() { order += "stop" }
                }
            },
        )
        assertEquals(listOf("run", "stop", "shared", "seal"), order.toList())
    }

    @Test fun `shutdown racing a component factory closes the rejected component`() {
        for (stage in listOf("presentation", "runtime")) {
            val broker = EventBroker()
            val order = CopyOnWriteArrayList<String>()
            val lifecycle = lifecycle(broker, order)
            assertFailsWith<IllegalStateException> {
                ApplicationBootstrap(lifecycle).run(
                    createPresentation = {
                        if (stage == "presentation") lifecycle.close()
                        object : Presentation {
                            override fun start() { fail("must not start") }
                            override fun close() { order += "presentation" }
                        }
                    },
                    createRuntime = {
                        lifecycle.close()
                        object : RuntimeSession {
                            override fun run() { fail("must not run") }
                            override fun close() { order += "runtime" }
                        }
                    },
                )
            }
            assertEquals(1, order.count { it == stage })
            assertEquals(1, order.count { it == "shared" })
        }
    }

    private fun lifecycle(broker: EventBroker, order: MutableList<String>) =
        ApplicationEventLifecycle(broker, sealFacade = { order += "seal" },
            report = { fail(it) }, terminate = { fail("unexpected exit $it") })
            .also { it.consumer(AutoCloseable { order += "shared" }) }
}
