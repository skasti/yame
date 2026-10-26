package no.skasti.yame.lifecycle

import no.skasti.yame.presentation.Presentation
import no.skasti.yame.runtime.RuntimeSession

/** Owns the same startup/shutdown ordering for every optional presentation. */
internal class ApplicationBootstrap(private val events: ApplicationEventLifecycle) {
    fun run(
        createPresentation: () -> Presentation?,
        createRuntime: () -> RuntimeSession,
    ) {
        try {
            // Subscribe before even constructing a runtime: constructor-time
            // observations are non-replayed too. Null is a headless runtime.
            val presentation = createPresentation()?.also(events::consumer)
            val runtime = createRuntime().also(events::producer)
            // Core functions now exist before presentation input is enabled.
            presentation?.start()
            runtime.run()
        } finally {
            // Quiesce producers, drain the broker, then release presentation
            // subscriptions/workers and shared infrastructure, even on failure.
            events.close()
        }
    }
}
