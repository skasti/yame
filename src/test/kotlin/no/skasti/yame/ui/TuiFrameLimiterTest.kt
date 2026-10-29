package no.skasti.yame.ui

import kotlin.test.*

class TuiFrameLimiterTest {
    @Test fun `bursty observations coalesce into at most one frame per 60 FPS interval`() {
        var now = 100L
        var renders = 0
        val deadlines = mutableListOf<Long>()
        val limiter = TuiFrameLimiter(
            nowNanos = { now },
            schedule = { delay -> deadlines += now + delay },
            draw = { renders++ },
        )

        limiter.request()
        assertEquals(1, renders)
        repeat(500) { limiter.request() }
        assertEquals(1, renders, "all burst updates must be applied without additional frames")
        assertEquals(listOf(16_666_767L), deadlines)

        now = 16_666_766L // one nanosecond too early
        limiter.onDeadline()
        assertEquals(1, renders)
        assertEquals(2, deadlines.size)
        assertEquals(16_666_767L, deadlines.last())

        now++
        limiter.onDeadline()
        assertEquals(2, renders)
        assertEquals(2, deadlines.size)

        // Neither the timer nor an unchanged render request should redraw twice.
        limiter.onDeadline()
        assertEquals(2, renders)
        now += 16_666_667L
        limiter.request()
        assertEquals(3, renders)
    }

    @Test fun `idle deferred frame and final shutdown flush preserve the latest state`() {
        var now = 0L
        var state = 0
        val frames = mutableListOf<Int>()
        val scheduled = mutableListOf<Long>()
        val limiter = TuiFrameLimiter(
            nowNanos = { now },
            schedule = { scheduled += it },
            draw = { frames += state },
        )

        limiter.request()
        state = 1
        limiter.request()
        state = 2
        limiter.request()
        assertEquals(listOf(0), frames)
        assertEquals(listOf(16_666_667L), scheduled)

        // An eventual timer makes the latest state visible even with no new events.
        now = 16_666_667L
        limiter.onDeadline()
        assertEquals(listOf(0, 2), frames)

        state = 3
        limiter.request()
        limiter.flush() // Application shutdown before the delayed timer callback.
        assertEquals(listOf(0, 2, 3), frames)
        limiter.onDeadline()
        assertEquals(listOf(0, 2, 3), frames)
    }

    @Test fun `render failures propagate and are not silently dropped`() {
        val problem = IllegalStateException("terminal failed")
        val limiter = TuiFrameLimiter(
            nowNanos = { 0L },
            schedule = { fail("unexpected schedule") },
            draw = { throw problem },
        )
        assertSame(problem, assertFailsWith<IllegalStateException> { limiter.request() })
    }
}
