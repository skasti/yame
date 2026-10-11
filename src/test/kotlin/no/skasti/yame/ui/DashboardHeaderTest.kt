package no.skasti.yame.ui

import no.skasti.yame.BuildInfo
import no.skasti.yame.serial.SerialFlowControl
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DashboardHeaderTest {
    private val state = DashboardState(
        portName = "COM3", baud = 115200, flowControl = SerialFlowControl.HARDWARE,
        connected = false, dnsUpstream = "8.8.8.8", httpProxyEnabled = true,
        toneSimulationEnabled = false, logs = emptyList(), dnsLookups = emptyList(),
        transfers = emptyList(), httpHosts = emptyList(), selectedHttpHostIndex = 0,
        commandPalette = null,
    )

    @Test
    fun rotatesAtFiveSecondsAndWrapsWithoutLosingSettings() {
        val first = DashboardHeader.render(state, 100)
        assertEquals(first, DashboardHeader.render(state.copy(headerElapsedNanos = 4_999_999_999L), 100))
        val second = DashboardHeader.render(state.copy(headerElapsedNanos = 5_000_000_000L), 100)
        assertNotEquals(first, second)
        assertEquals(first, DashboardHeader.render(state.copy(headerElapsedNanos = 10_000_000_000L), 100))
        for (field in listOf(BuildInfo.display, "COM3 @ 115200", "NOT CONNECTED",
            "Hardware (RTS/CTS)", "DNS 8.8.8.8", "HTTP proxy ON", "tones OFF")) {
            assertContains(first + second, field)
        }
    }

    @Test
    fun headerIsStableWhenAllFieldsFitIncludingStackedLayout() {
        for (width in listOf(200, 300)) {
            val first = YameDashboardRenderer.render(state, width, 30).lines().first()
            val later = YameDashboardRenderer.render(state.copy(headerElapsedNanos = 5_000_000_000L), width, 30).lines().first()
            assertEquals(first, later)
            assertContains(first, "Hardware (RTS/CTS)")
            assertContains(first, "tones OFF")
            assertEquals(width, first.length)
        }
    }

    @Test
    fun everyLayoutUsesTimedPagesWithFixedDimensions() {
        for (width in listOf(40, 72, 100)) {
            val headers = (0L..10L).map { tick ->
                val frame = YameDashboardRenderer.render(state.copy(headerElapsedNanos = tick * 5_000_000_000L), width, 30)
                assertEquals(30, frame.lines().size)
                assertTrue(frame.lines().all { it.length == width })
                frame.lines().first()
            }
            assertTrue(headers.distinct().size > 1)
            assertContains(headers.joinToString("\n"), "Hardware (RTS/CTS)")
            assertContains(headers.joinToString("\n"), "tones OFF")
        }
    }

    @Test
    fun oversizedFieldIsSplitAcrossPagesInsteadOfClipped() {
        val longPort = "P".repeat(62) + "END"
        val headers = (0L..15L).map {
            DashboardHeader.render(state.copy(portName = longPort, headerElapsedNanos = it * 5_000_000_000L), 40)
        }
        assertTrue(headers.all { it.length == 40 })
        assertContains(headers.joinToString(""), "END @ 115200")
        assertContains(headers.joinToString(""), "tones OFF")
    }
}
