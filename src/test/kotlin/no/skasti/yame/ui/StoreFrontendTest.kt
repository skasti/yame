package no.skasti.yame.ui

import no.skasti.yame.persistence.*
import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.config.*
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.serial.*
import no.skasti.yame.modem.ModemSettings
import no.skasti.yame.ppp.proxy.transform.TransformerSettings
import java.io.IOException
import kotlin.test.*

class StoreFrontendTest {
    private fun observer(store: ConfigStore, functions: FunctionStore = FunctionStore()) = TuiYameObserver(
        terminal = Terminal(interactive = false), configStore = store, functions = functions,
    )
    private fun TuiYameObserver.command(value: String) {
        value.forEach { handleKey(it.toString()) }
        handleKey("Enter")
    }

    @Test fun `multiple frontends share configuration while owning independent palettes`() {
        var saves = 0
        val store = applicationConfigStore(YameConfiguration(), save = { saves++ })
        observer(store).use { first ->
            observer(store).use { second ->
                first.command("/tone off")
                assertFalse(store[ModemSettings.Tone])
                second.command("/transformers/style-remover/off")
                assertFalse(store[TransformerSettings.Style])
                assertEquals(2, saves)
            }
            first.command("/transformers/style-remover/on")
            assertTrue(store[TransformerSettings.Style])
        }
    }

    @Test fun `frontend invokes registered operations and survives rejected persistence`() {
        val store = applicationConfigStore(YameConfiguration(), save = { throw IOException("disk full") })
        val functions = FunctionStore()
        var disconnects = 0
        functions.register(ConnectionFunctions.Disconnect) { disconnects++ }
        observer(store, functions).use {
            it.command("/tone off")
            assertTrue(store[ModemSettings.Tone])
            it.command("/disconnect")
            assertEquals(1, disconnects)
        }
    }
}
