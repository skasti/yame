package no.skasti.yame.logging

import no.skasti.yame.observer.AbstractYameEvent
import no.skasti.yame.observer.VersionNumber
import no.skasti.yame.observer.YameEvents
import java.time.Instant

/** Presentation-only observation. Module files are written independently on the producer. */
data class DiagnosticMessage(val message: String, val occurredAt: Instant = Instant.now()) : AbstractYameEvent() {
    override val typeName = "yame.diagnostic.message"
    override val version = VersionNumber(1, 0, 0)
}

internal fun diagnosticLogger(fileLogger: (String) -> Unit): (String) -> Unit = { message ->
    val observation = DiagnosticMessage(message)
    try { fileLogger(message) } finally { YameEvents.publish(observation) }
}
