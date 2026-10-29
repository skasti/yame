package no.skasti.yame.presentation

import no.skasti.yame.logging.LogEntry
import no.skasti.yame.observer.YameEvents

/** Optional line-oriented diagnostics; also used by the standalone tone test. */
internal class PlainPresentation(output: (String) -> Unit = ::println) : Presentation {
    private val observations = YameEvents.subscribe("plain-diagnostics", { it is LogEntry }) {
        output((it as LogEntry).message)
    }

    override fun start() = Unit
    override fun close() = observations.close()
}
