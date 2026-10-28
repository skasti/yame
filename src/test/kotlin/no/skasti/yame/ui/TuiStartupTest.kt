package no.skasti.yame.ui

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.config.ConfigStore
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.lifecycle.ApplicationFunctions
import no.skasti.yame.modem.ModemSettings
import no.skasti.yame.serial.ConnectionFunctions
import no.skasti.yame.serial.SerialPortDescriptor
import kotlin.test.*

class TuiStartupTest {
    @Test fun `empty function store does not stop input and later registrations work`() {
        val functions = FunctionStore()
        val config = ConfigStore()
        TuiYameObserver(config, functions, Terminal(interactive = false)).use { ui ->
            ui.start()
            assertTrue(functions.keys().isEmpty(), "starting UI must not create runtime functions")
            for (key in listOf("q", "Ctrl+C")) {
                ui.handleKeyAndAwait(key)
                assertFalse(ui.stopRequested, "unavailable quit must keep input alive")
                ui.handleKeyAndAwait("/")
                assertNotNull(ui.paletteSnapshot())
                ui.handleKeyAndAwait("Escape")
            }
            for (command in listOf("/quit", "/disconnect", "/reconnect", "/port")) {
                ui.command(command)
                assertFalse(ui.stopRequested)
            }
            ui.command("/tone off")
            assertFalse(config[ModemSettings.Tone], "settings input must survive unavailable operations")
            assertFailsWith<IllegalArgumentException> { functions.invoke(ApplicationFunctions.Quit) }
            assertFailsWith<IllegalStateException> { ConfigStore.current }

            var refreshes = 0
            var disconnects = 0
            var reconnects = 0
            var quits = 0
            val registrations = listOf(
                functions.register(ConnectionFunctions.RefreshPorts) {
                    refreshes++
                    listOf(SerialPortDescriptor("COM1", "one"))
                },
                functions.register(ConnectionFunctions.Disconnect) { disconnects++ },
                functions.register(ConnectionFunctions.Reconnect) { reconnects++ },
                functions.register(ApplicationFunctions.Quit) { quits++ },
            )
            try {
                ui.command("/port")
                ui.handleKeyAndAwait("Escape")
                ui.command("/disconnect")
                ui.command("/reconnect")
                assertEquals(1, refreshes)
                assertEquals(1, disconnects)
                assertEquals(1, reconnects)
                ui.command("/tone on")
                assertTrue(config[ModemSettings.Tone])
                ui.command("/quit")
                assertTrue(ui.stopRequested)
                assertEquals(1, quits, "early failed quit must not suppress later successful quit")
            } finally { registrations.forEach(AutoCloseable::close) }
        }
        assertTrue(functions.keys().isEmpty())
    }

    private fun TuiYameObserver.command(value: String) {
        value.forEach { handleKeyAndAwait(it.toString()) }
        handleKeyAndAwait("Enter")
    }
}
