package no.skasti.yame.ui

/**
 * Coalesces presentation redraws without coalescing or dropping observations.
 * All methods are invoked on the TUI worker; the scheduler posts [onDeadline]
 * back to it. Painting itself must not delay the event producer's queue admission.
 */
internal class TuiFrameLimiter(
    private val nowNanos: () -> Long,
    private val schedule: (delayNanos: Long) -> Unit,
    private val draw: () -> Unit,
    private val intervalNanos: Long = 16_666_667L, // ceil(1 second / 60 FPS)
) {
    init { require(intervalNanos > 0) }

    private var dirty = false
    private var scheduled = false
    private var lastDrawNanos: Long? = null

    fun request() {
        dirty = true
        // Bootstrap needs a visible screen immediately. Subsequent redraws are
        // deferred so a slow terminal never blocks the worker between every two
        // observations in a synchronous graph-discovery burst.
        if (lastDrawNanos == null) {
            flush()
        } else {
            scheduleIfNeeded()
        }
    }

    /** Invoked by the worker, not directly from the timer thread. */
    fun onDeadline() {
        scheduled = false
        if (!dirty) return
        val elapsed = nowNanos() - requireNotNull(lastDrawNanos)
        if (elapsed >= intervalNanos) flush() else scheduleIfNeeded()
    }

    /** Preserve the final accepted state when the presentation closes normally. */
    fun flush() {
        if (!dirty) return
        dirty = false
        draw()
        // The interval starts after the terminal operation finishes; otherwise
        // a paint slower than 16 ms would immediately permit the next one.
        lastDrawNanos = nowNanos()
    }

    private fun scheduleIfNeeded() {
        if (!dirty || scheduled) return
        val sinceLast = nowNanos() - requireNotNull(lastDrawNanos)
        scheduled = true
        schedule((intervalNanos - sinceLast).coerceAtLeast(0))
    }
}
