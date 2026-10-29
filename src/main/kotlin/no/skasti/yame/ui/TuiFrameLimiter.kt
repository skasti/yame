package no.skasti.yame.ui

/**
 * Coalesces presentation redraws without coalescing or dropping any observations.
 * Called exclusively on the TUI worker; the scheduler posts [onDeadline] back to it.
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
        drawIfDue()
    }

    /** Invoked by the worker, not directly from the timer thread. */
    fun onDeadline() {
        scheduled = false
        drawIfDue()
    }

    /** Preserve the final accepted state when the presentation closes normally. */
    fun flush() {
        if (!dirty) return
        dirty = false
        lastDrawNanos = nowNanos()
        draw()
    }

    private fun drawIfDue() {
        if (!dirty) return
        val now = nowNanos()
        val remaining = lastDrawNanos?.let { (intervalNanos - (now - it)).coerceAtLeast(0) } ?: 0L
        if (remaining == 0L) {
            flush()
        } else if (!scheduled) {
            scheduled = true
            schedule(remaining)
        }
    }
}
