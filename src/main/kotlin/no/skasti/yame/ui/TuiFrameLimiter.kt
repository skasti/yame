package no.skasti.yame.ui

import no.skasti.yame.config.ConfigStore

/**
 * Coalesces presentation redraws without coalescing or dropping observations.
 * All methods are invoked on the TUI worker; the scheduler posts [onDeadline]
 * back to it. Painting itself must not delay the event producer's queue admission.
 */
internal class TuiFrameLimiter(
    private val nowNanos: () -> Long,
    private val schedule: (delayNanos: Long, generation: Long) -> Unit,
    private val draw: () -> Unit,
    private val dispatch: (() -> Unit) -> Unit = { action -> action() },
    initialFps: Int = ConfigStore[TuiSettings.MaxFps],
) : AutoCloseable {
    private var intervalNanos = frameIntervalNanos(initialFps)
    private var dirty = false
    private var scheduledGeneration: Long? = null
    private var nextGeneration = 0L
    private var lastDrawNanos: Long? = null
    @Volatile private var closed = false

    // Own both the initial rate and subsequent updates. The caller only provides
    // its execution context so timer state remains confined to the TUI worker.
    // Subscribing with initial=true closes the read/subscribe race.
    private val configurationSubscription =
        ConfigStore.onChange[TuiSettings.Group].subscribe(initial = true) { changes ->
            changes[TuiSettings.MaxFps]?.let { change ->
                dispatch { if (!closed) updateFrameRate(change.value) }
            }
        }

    /** Apply configuration changes without restarting the presentation. Worker-only. */
    private fun updateFrameRate(fps: Int) {
        val interval = frameIntervalNanos(fps)
        if (interval == intervalNanos) return
        intervalNanos = interval
        if (dirty && lastDrawNanos != null) {
            // A previously scheduled callback may still arrive. Its token is
            // obsolete, so it cannot redraw or disturb the replacement timer.
            scheduledGeneration = null
            scheduleIfNeeded()
        }
    }

    override fun close() {
        closed = true
        configurationSubscription.close()
    }

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
    fun onDeadline(generation: Long) {
        if (generation != scheduledGeneration) return
        scheduledGeneration = null
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
        // a slow paint would immediately permit the next one.
        lastDrawNanos = nowNanos()
    }

    private fun scheduleIfNeeded() {
        if (!dirty || scheduledGeneration != null) return
        val sinceLast = nowNanos() - requireNotNull(lastDrawNanos)
        check(nextGeneration < Long.MAX_VALUE) { "TUI frame timer generation exhausted" }
        val generation = ++nextGeneration
        scheduledGeneration = generation
        schedule((intervalNanos - sinceLast).coerceAtLeast(0), generation)
    }

    private companion object {
        fun frameIntervalNanos(fps: Int): Long {
            require(fps in TuiSettings.SupportedFrameRates)
            return (1_000_000_000L + fps - 1) / fps
        }
    }
}
