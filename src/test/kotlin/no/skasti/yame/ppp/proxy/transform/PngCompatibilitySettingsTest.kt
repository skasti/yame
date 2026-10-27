package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.persistence.lazyApplicationConfigStore
import no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class PngCompatibilitySettingsTest {
    @Test
    fun `feature-owned png mode persists and reloads from yame ini`(@TempDir directory: Path) {
        val path = directory.resolve("yame.ini")
        val store = lazyApplicationConfigStore(path)

        assertEquals(PngCompatibilityMode.OPTIMIZE.configValue, store[TransformerSettings.PngCompatibility])
        store.set(TransformerSettings.PngCompatibility to PngCompatibilityMode.REQUIRE_LEGACY_FORMAT.configValue)

        val saved = Files.readString(path)
        assertTrue(saved.contains("[transformers]\npng-compatibility-mode=require-legacy-format"), saved)
        val reloaded = lazyApplicationConfigStore(path)
        assertEquals(
            PngCompatibilityMode.REQUIRE_LEGACY_FORMAT.configValue,
            reloaded[TransformerSettings.PngCompatibility],
        )
        assertEquals(
            PngCompatibilityMode.REQUIRE_LEGACY_FORMAT,
            PppHttpCompatibilityConfig.fromConfigStore(reloaded).pngCompatibilityMode,
        )
    }

    @Test
    fun `setting rejects unknown modes`(@TempDir directory: Path) {
        val store = lazyApplicationConfigStore(directory.resolve("yame.ini"))
        assertEquals(PngCompatibilityMode.OPTIMIZE.configValue, store[TransformerSettings.PngCompatibility])
        assertFailsWith<IllegalArgumentException> {
            store.set(TransformerSettings.PngCompatibility to "surprise")
        }
    }
}
