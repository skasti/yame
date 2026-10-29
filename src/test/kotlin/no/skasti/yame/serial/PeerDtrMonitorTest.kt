package no.skasti.yame.serial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeerDtrMonitorTest {
    @Test fun `a falling DSR or DCD reports a peer hangup only after a known active level`() {
        val monitor = PeerDtrMonitor()
        assertTrue(monitor.observe(false, false).isEmpty())
        assertTrue(monitor.observe(false, false).isEmpty())
        assertTrue(monitor.observe(true, true).isEmpty())
        assertEquals(listOf("DSR", "DCD"), monitor.observe(false, false))
        assertTrue(monitor.observe(false, false).isEmpty())
        assertTrue(monitor.observe(true, false).isEmpty())
        assertEquals(listOf("DSR"), monitor.observe(false, false))
        assertTrue(monitor.observe(false, false).isEmpty())
    }

    @Test fun `missing DSR still permits DCD to detect a hangup`() {
        val monitor = PeerDtrMonitor()
        assertTrue(monitor.observe(false, true).isEmpty())
        assertEquals(listOf("DCD"), monitor.observe(false, false))
    }
}
