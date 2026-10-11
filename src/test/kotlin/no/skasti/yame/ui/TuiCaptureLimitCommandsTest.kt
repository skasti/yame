package no.skasti.yame.ui

import no.skasti.yame.logging.PacketCaptureLimitKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TuiCaptureLimitCommandsTest {
    @Test
    fun `both commands accept positive integer counts`() {
        assertEquals(PacketCaptureLimitKind.PER_TYPE to 20, parseCaptureLimitInput("/capture-limit-per-type 20"))
        assertEquals(PacketCaptureLimitKind.TOTAL to 2000, parseCaptureLimitInput("/CAPTURE-LIMIT-TOTAL 2000"))
    }

    @Test
    fun `commands reject missing invalid and extra values`() {
        for (value in listOf("", "0", "-1", "1.5", "no", "2147483648", "10 20")) {
            assertNull(parseCaptureLimitInput("/capture-limit-total $value"))
        }
        assertNull(parseCaptureLimitInput("/other 10"))
    }
}
