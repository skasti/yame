package no.skasti.yame.logging

import no.skasti.yame.observer.AbstractYameEvent
import no.skasti.yame.observer.VersionNumber
import java.time.Instant

/** Log entry accepted by the module's configured severity filter. */
data class LogEntry(
    val module: YameLogModule,
    val level: YameLogLevel,
    val message: String,
    val occurredAt: Instant = Instant.now(),
) : AbstractYameEvent() {
    override val typeName = "yame.logging.entry"
    override val version = VersionNumber(1, 0, 0)
}
