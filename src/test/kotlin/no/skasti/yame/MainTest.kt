package no.skasti.yame

import no.skasti.yame.tone.configurationForToneTest
import no.skasti.yame.modem.HayesModemConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MainTest {
    @Test fun `startup failures release the installed store and CLI defaults use it`() {
        val path = java.nio.file.Files.createTempFile("yame-invalid-startup", ".ini")
        try {
            java.nio.file.Files.writeString(path, "[serial]\nbaud=invalid\n")
            assertFailsWith<IllegalArgumentException> { main(arrayOf("--config", path.toString())) }
            assertFailsWith<IllegalStateException> { no.skasti.yame.config.ConfigStore.current }
            val store = no.skasti.yame.config.ConfigStore()
            no.skasti.yame.config.ConfigStore.install(store).use {
                parseArgs(arrayOf("--baud", "9600"))
                assertEquals(9600, store[no.skasti.yame.serial.SerialSettings.Baud])
            }
            java.nio.file.Files.writeString(path, "[serial]\nbaud=57600\n")
            assertFailsWith<IllegalArgumentException> { main(arrayOf("--config", path.toString(), "--username", "alone")) }
            assertFailsWith<IllegalStateException> { no.skasti.yame.config.ConfigStore.current }
        } finally { java.nio.file.Files.deleteIfExists(path) }
    }

    @Test
    fun `keeps saved login when no CLI login overrides are provided`() {
        assertEquals(
            "saved-user" to "saved-password",
            resolveLoginCredentials(
                savedUsername = "saved-user",
                savedPassword = "saved-password",
                usernameOverride = null,
                passwordOverride = null,
            ),
        )
    }

    @Test
    fun `replaces saved login only when both CLI overrides are provided`() {
        assertEquals(
            "cli-user" to "cli-password",
            resolveLoginCredentials(
                savedUsername = "saved-user",
                savedPassword = "saved-password",
                usernameOverride = "cli-user",
                passwordOverride = "cli-password",
            ),
        )
    }

    @Test
    fun `rejects CLI login overrides provided one at a time`() {
        assertFailsWith<IllegalArgumentException> {
            resolveLoginCredentials(
                savedUsername = "saved-user",
                savedPassword = "saved-password",
                usernameOverride = "cli-user",
                passwordOverride = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            resolveLoginCredentials(
                savedUsername = "saved-user",
                savedPassword = "saved-password",
                usernameOverride = null,
                passwordOverride = "cli-password",
            )
        }
    }

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
