package no.skasti.yame

import no.skasti.yame.modem.HayesModemConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun `tone test rejects blank number`() {
        assertFailsWith<IllegalArgumentException> {
            main(arrayOf("--test-tone", ""))
        }
    }

    @Test
    fun `tone test rejects number that normalizes to empty`() {
        assertFailsWith<IllegalArgumentException> {
            main(arrayOf("--test-tone", "---"))
        }
    }

    @Test
    fun `tone test enables playback even when saved configuration disables it`() {
        val savedConfiguration = HayesModemConfig(toneSimulationEnabled = false)

        val testConfiguration = configurationForToneTest(savedConfiguration)

        assertTrue(testConfiguration.toneSimulationEnabled)
        assertEquals(savedConfiguration.copy(toneSimulationEnabled = true), testConfiguration)
    }
}
