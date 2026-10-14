package no.skasti.yame.config

import no.skasti.yame.serial.SerialSettings
import no.skasti.yame.modem.ModemSettings
import no.skasti.yame.modem.LoginCredentials
import no.skasti.yame.ppp.proxy.transform.TransformerSettings
import no.skasti.yame.logging.LogSettings
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.logging.YameLogLevel
import java.nio.file.Files
import kotlin.test.*

class ApplicationConfigStoreTest {
    @Test fun `CLI overrides and runtime discovery do not leak into later persistent changes`() {
        val baseline = YameConfiguration()
        val effective = baseline.copy(baudRate = 115200, modemConfig = baseline.modemConfig.copy(username = "cli", password = "secret"))
        var saved = baseline
        var saves = 0
        val store = applicationConfigStore(baseline, effective, { saved = it; saves++ })
        store.setTransient(SerialSettings.Port to "COM2")
        store.set(TransformerSettings.Style to false, LogSettings.Levels.getValue(YameLogModule.MODEM) to YameLogLevel.DEBUG)
        assertEquals(1, saves)
        assertEquals(38400, saved.baudRate)
        assertNull(saved.portName)
        assertNull(saved.modemConfig.password)
        assertEquals(115200, store[SerialSettings.Baud])
        assertEquals("COM2", store[SerialSettings.Port])
        store.set(SerialSettings.Baud to 115200)
        assertEquals(2, saves)
        assertEquals(115200, saved.baudRate)
    }

    @Test fun `feature changes autosave one compatible file snapshot and invalid credentials reject entire batch`() {
        val directory = Files.createTempDirectory("yame-store-test")
        try {
            val path = directory.resolve("yame.ini")
            val file = YameConfigurationStore(path)
            val store = applicationConfigStore(YameConfiguration(), save = file::save)
            store.set(SerialSettings.Baud to 9600, ModemSettings.Login to LoginCredentials("user", "secret"), TransformerSettings.Script to false)
            val loaded = file.load()
            assertEquals(9600, loaded.baudRate)
            assertEquals("secret", loaded.modemConfig.password)
            assertFalse(loaded.modemConfig.pppHttpCompatibilityConfig.htmlTransformers.scriptRemover)
            val bytes = Files.readAllBytes(path)
            assertFailsWith<IllegalArgumentException> {
                store.set(SerialSettings.Baud to 19200, ModemSettings.Login to LoginCredentials("user", null))
            }
            assertContentEquals(bytes, Files.readAllBytes(path))
            assertEquals(9600, store[SerialSettings.Baud])
        } finally { directory.toFile().deleteRecursively() }
    }
}
