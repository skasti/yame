package no.skasti.yame.function

import no.skasti.yame.config.ConfigStore
import no.skasti.yame.serial.SerialSettings
import no.skasti.yame.modem.ModemSettings
import kotlin.test.*

class FunctionStoreTest {
    @Test fun `typed action commits multiple groups once and unregisters safely`() {
        val functions = FunctionStore()
        var saves = 0
        val config = ConfigStore(persist = { saves++ })
        val slow = FunctionKey<Int, String>("modem.slow-mode")
        val handle = functions.register(slow) { baud ->
            config.set(SerialSettings.Baud to baud, ModemSettings.Tone to false)
            "ok"
        }
        assertFailsWith<IllegalArgumentException> { functions.register(slow) { "duplicate" } }
        assertEquals("ok", functions.invoke(slow, 9600))
        assertEquals(1, saves)
        assertEquals(9600, config[SerialSettings.Baud])
        assertFalse(config[ModemSettings.Tone])
        assertFailsWith<IllegalArgumentException> { functions.invoke(FunctionKey<Int, String>(slow.path), 4800) }
        handle.close()
        val replacement = functions.register(slow) { "replacement" }
        handle.close()
        assertEquals("replacement", functions.invoke(slow, 9600))
        replacement.close()
        assertTrue(functions.keys().isEmpty())
        assertFailsWith<IllegalArgumentException> { functions.invoke(slow, 9600) }
    }

    @Test fun `function errors propagate without holding the registry lock`() {
        val functions = FunctionStore()
        val key = FunctionKey<Unit, Unit>("fail")
        functions.register(key) { throw IllegalStateException("operation failed") }
        assertFailsWith<IllegalStateException> { functions.invoke(key) }
    }
}
