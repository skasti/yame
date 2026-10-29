package no.skasti.yame.ui

import no.skasti.yame.config.ConfigStore
import no.skasti.yame.config.InstalledConfigTest

import kotlin.test.*

class TuiFrameLimiterTest : InstalledConfigTest() {
    private fun installedStore(): ConfigStore = ConfigStore().also(::installConfig)

    @Test fun `bursty observations coalesce into at most one frame per 60 FPS interval`() {
        installedStore()
        var now = 100L
        var renders = 0
        val deadlines = mutableListOf<Pair<Long, Long>>()
        val limiter = TuiFrameLimiter(
            nowNanos = { now },
            schedule = { delay, generation -> deadlines += now + delay to generation },
            draw = { renders++ },
        )

        limiter.request()
        assertEquals(1, renders)
        repeat(500) { limiter.request() }
        assertEquals(1, renders, "all burst updates must be applied without additional frames")
        assertEquals(listOf(16_666_767L), deadlines.map { it.first })

        now = 16_666_766L // one nanosecond too early
        limiter.onDeadline(deadlines.last().second)
        assertEquals(1, renders)
        assertEquals(2, deadlines.size)
        assertEquals(16_666_767L, deadlines.last().first)

        now++
        limiter.onDeadline(deadlines.last().second)
        assertEquals(2, renders)
        assertEquals(2, deadlines.size)

        // Neither a stale timer nor an unchanged render request should redraw twice.
        limiter.onDeadline(deadlines.last().second)
        assertEquals(2, renders)
        now += 16_666_667L
        limiter.request()
        assertEquals(2, renders, "even an overdue frame must be scheduled, not block event processing")
        assertEquals(3, deadlines.size)
        limiter.onDeadline(deadlines.last().second)
        assertEquals(3, renders)
    }

    @Test fun `idle deferred frame and final shutdown flush preserve the latest state`() {
        installedStore()
        var now = 0L
        var state = 0
        val frames = mutableListOf<Int>()
        val scheduled = mutableListOf<Pair<Long, Long>>()
        val limiter = TuiFrameLimiter(
            nowNanos = { now },
            schedule = { delay, generation -> scheduled += delay to generation },
            draw = { frames += state },
        )

        limiter.request()
        state = 1
        limiter.request()
        state = 2
        limiter.request()
        assertEquals(listOf(0), frames)
        assertEquals(listOf(16_666_667L), scheduled.map { it.first })

        now = 16_666_667L
        limiter.onDeadline(scheduled.last().second)
        assertEquals(listOf(0, 2), frames)

        state = 3
        limiter.request()
        limiter.flush()
        assertEquals(listOf(0, 2, 3), frames)
        limiter.onDeadline(scheduled.last().second)
        assertEquals(listOf(0, 2, 3), frames)
    }

    @Test fun `supported frame rates use ceiling intervals and defer later renders`() {
        installedStore()
        for (fps in TuiSettings.SupportedFrameRates) {
            ConfigStore.set(TuiSettings.MaxFps to fps)
            var now = 0L
            var renders = 0
            val deadlines = mutableListOf<Pair<Long, Long>>()
            val limiter = TuiFrameLimiter(
                nowNanos = { now },
                schedule = { delay, generation -> deadlines += now + delay to generation },
                draw = { renders++ },
            )
            val interval = (1_000_000_000L + fps - 1) / fps
            limiter.request()
            limiter.request()
            assertEquals(listOf(interval), deadlines.map { it.first }, "FPS=$fps")
            now = interval - 1
            limiter.onDeadline(deadlines.last().second)
            assertEquals(1, renders, "FPS=$fps")
            now = interval
            limiter.onDeadline(deadlines.last().second)
            assertEquals(2, renders, "FPS=$fps")
        }
    }

    @Test fun `changing FPS reissues a timer and ignores callbacks from the old rate`() {
        val store = installedStore()
        var now = 0L
        var renders = 0
        val scheduled = mutableListOf<Pair<Long, Long>>()
        val limiter = TuiFrameLimiter(
            nowNanos = { now },
            schedule = { delay, generation -> scheduled += now + delay to generation },
            draw = { renders++ },
        )
        limiter.request()
        limiter.request()
        val old = scheduled.last()
        assertEquals(16_666_667L, old.first)

        store.set(TuiSettings.MaxFps to 120)
        val faster = scheduled.last()
        assertEquals(8_333_334L, faster.first)
        assertNotEquals(old.second, faster.second)
        now = faster.first
        limiter.onDeadline(old.second)
        assertEquals(1, renders)
        limiter.onDeadline(faster.second)
        assertEquals(2, renders)
        // Configuration changes while idle affect the next requested frame.
        store.set(TuiSettings.MaxFps to 15)
        limiter.request()
        val slower = scheduled.last()
        assertEquals(now + 66_666_667L, slower.first)
        limiter.onDeadline(faster.second)
        assertEquals(2, renders)
        now = slower.first
        limiter.onDeadline(slower.second)
        assertEquals(3, renders)
    }

    @Test fun `invalid rates are rejected without changing active interval`() {
        val store = installedStore()
        val configStore = ConfigStore()
        var now = 0L
        val deadlines = mutableListOf<Long>()
        val limiter = TuiFrameLimiter(
            nowNanos = { now },
            schedule = { delay, _ -> deadlines += delay },
            draw = {},
        )
        assertFailsWith<IllegalArgumentException> { configStore.set(TuiSettings.MaxFps to 0) }
        assertFailsWith<IllegalArgumentException> { configStore.set(TuiSettings.MaxFps to 144) }
        limiter.request()
        limiter.request()
        assertEquals(listOf(16_666_667L), deadlines)
    }

    @Test fun `configuration callbacks use worker dispatch and unsubscribe on close`() {
        val store = installedStore()
        val configStore = ConfigStore()
        val actions = ArrayDeque<() -> Unit>()
        val scheduled = mutableListOf<Long>()
        var now = 0L
        val limiter = TuiFrameLimiter(
            nowNanos = { now },
            schedule = { delay, _ -> scheduled += delay },
            draw = {},
            dispatch = { action -> actions.addLast(action) },
        )
        // Initial=true supplies the current ConfigStore rate through the same
        // dispatcher; configuration must not mutate timer state on its caller.
        assertEquals(1, actions.size)
        actions.removeFirst().invoke()
        limiter.request()
        limiter.request()
        assertEquals(listOf(16_666_667L), scheduled)
        store.set(TuiSettings.MaxFps to 120)
        assertEquals(1, actions.size)
        assertEquals(1, scheduled.size)
        actions.removeFirst().invoke()
        assertEquals(listOf(16_666_667L, 8_333_334L), scheduled)

        limiter.close()
        store.set(TuiSettings.MaxFps to 15)
        assertTrue(actions.isEmpty(), "closed limiter must release its subscription")
    }

    @Test fun `render failures propagate and are not silently dropped`() {
        installedStore()
        val problem = IllegalStateException("terminal failed")
        val limiter = TuiFrameLimiter(
            nowNanos = { 0L },
            schedule = { _, _ -> fail("unexpected schedule") },
            draw = { throw problem },
        )
        assertSame(problem, assertFailsWith<IllegalStateException> { limiter.request() })
    }
}
