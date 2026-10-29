package no.skasti.yame.ui

import no.skasti.yame.ppp.PppSettings
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.serial.ConnectionFunctions
import no.skasti.yame.ppp.RetroPppHandler
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.modem.HayesModem
import no.skasti.yame.ppp.PppEncoder
import no.skasti.yame.ppp.PppFrame
import no.skasti.yame.ppp.session.PppSession
import no.skasti.yame.ppp.lcp.LcpPacket
import java.io.ByteArrayOutputStream
import no.skasti.yame.config.InstalledConfigTest
import no.skasti.yame.config.ConfigStore
import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.ppp.proxy.transform.HtmlTransformer
import no.skasti.yame.ppp.proxy.transform.HtmlTransformerSettings
import no.skasti.yame.ppp.proxy.transform.defaultResourceTransformationPipeline
import no.skasti.yame.serial.SerialFlowControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandPaletteTest : InstalledConfigTest() {
    @Test
    fun `paths create palettes callbacks only execute on selection and cleanup waits for last child`() {
        var enabled = false
        val enable = CommandPalette.add("/registry-test/style-remover/enable", { enabled = true })
        val disable = CommandPalette.add("/registry-test/style-remover/disable") { enabled = false }
        try {
            val palette = assertNotNull(CommandPalette.find("/registry-test/style-remover"))
            assertEquals(listOf("enable", "disable"), palette.items().map { it.command })
            val view = registeredCommandPalette("/registry-test/style-remover/enable")
            assertEquals("/registry-test/style-remover", view.palettePath)
            assertFalse(enabled)
            (palette.item("enable") as PaletteChoice).select()
            assertTrue(enabled)
            enable.close()
            assertNotNull(CommandPalette.find("/registry-test/style-remover"))
        } finally {
            enable.close()
            disable.close()
        }
        assertNull(CommandPalette.find("/registry-test"))
    }

    @Test
    fun `commands are unique ignoring slash and case within each level`() {
        val first = CommandPalette.add("/registry-test/style-remover/on") {}
        val other = CommandPalette.add("/registry-test/script-remover/on") {}
        val numeric = CommandPalette.add("/registry-test/baud/9600") {}
        try {
            assertFailsWith<IllegalArgumentException> { CommandPalette.add("/REGISTRY-TEST/STYLE-REMOVER/ON") {} }
            assertFailsWith<IllegalArgumentException> { CommandPalette.add("/registry-test/style-remover/on/child") {} }
            assertFailsWith<IllegalArgumentException> { CommandPalette.add("/bad//path") {} }
            assertEquals("9600", CommandPalette.find("/registry-test/baud")?.item("9600")?.command)
            val palette = Palette("/child", "Child")
            palette.add(PaletteChoice("/on") {})
            assertFailsWith<IllegalArgumentException> { palette.add(PaletteChoice("ON") {}) }
        } finally { first.close(); other.close(); numeric.close() }
    }

    @Test
    fun `presentation registers transformer switches independently of pipeline construction`() {
        run {
            val store = ConfigStore()
            repeat(2) { defaultResourceTransformationPipeline(1024) }
            installConfig(store)
            val registrations = registerTransformerCommands()
            try {
            val parent = assertNotNull(CommandPalette.find("/transformers"))
            assertEquals(HtmlTransformer.entries.map { "/${it.command}" }, parent.items().map { it.command })
            val options = assertNotNull(CommandPalette.find("/transformers/style-remover"))
            (options.item("off") as PaletteChoice).select()
            assertFalse(store[no.skasti.yame.ppp.proxy.transform.TransformerSettings.Style])
            assertTrue(store[no.skasti.yame.ppp.proxy.transform.TransformerSettings.Script])
            assertEquals("current", options.item("off")?.description)
            } finally { registrations.forEach(AutoCloseable::close) }
        }
        assertNull(CommandPalette.find("/transformers"))
    }

    @Test
    fun `keyboard browses nested palettes goes back and executes local on off arguments`() {
        run {
            val store = ConfigStore()
            defaultResourceTransformationPipeline(1024)
            observer(configStore = store).use { observer ->
                observer.type("/transformers")
                assertEquals(listOf("/transformers"), observer.paletteSnapshot()?.options?.map { it.value })
                observer.handleKeyAndAwait("Enter")
                assertEquals("/transformers", observer.paletteSnapshot()?.palettePath)
                observer.type("style-remover")
                observer.handleKeyAndAwait("Enter")
                assertEquals("/transformers/style-remover", observer.paletteSnapshot()?.palettePath)
                observer.handleKeyAndAwait("Escape")
                assertEquals("/transformers", observer.paletteSnapshot()?.palettePath)
                observer.type("/style-remover off")
                observer.handleKeyAndAwait("Enter")
                assertFalse(store[no.skasti.yame.ppp.proxy.transform.TransformerSettings.Style])
                assertNull(observer.paletteSnapshot())
                observer.type("/transformers/style-remover/on")
                observer.handleKeyAndAwait("Enter")
                assertTrue(store[no.skasti.yame.ppp.proxy.transform.TransformerSettings.Style])
                observer.type("/transformers")
                observer.handleKeyAndAwait("Enter")
                observer.handleKeyAndAwait("Backspace")
                assertEquals("/", observer.paletteSnapshot()?.palettePath)
                observer.handleKeyAndAwait("Escape")
                assertNull(observer.paletteSnapshot())
            }
        }
    }

    @Test
    fun `existing tone baud and login commands survive registry migration and observer recreation`() {
        repeat(2) {
            var tone: Boolean? = null
            var login: Pair<String, String>? = null
            observer(onTone = { tone = it }, onLogin = { user, password -> login = user to password }).use { observer ->
                observer.type("/tone off")
                observer.handleKeyAndAwait("Enter")
                assertEquals(false, tone)
                observer.type("/baud")
                observer.handleKeyAndAwait("Enter")
                assertEquals(TuiPaletteMode.BAUD, observer.paletteSnapshot()?.mode)
                observer.handleKeyAndAwait("Escape")
                observer.type("/login user:secret")
                assertEquals(TuiPaletteMode.LOGIN, observer.paletteSnapshot()?.mode)
                observer.handleKeyAndAwait("Enter")
                assertEquals("user" to "secret", login)
            }
            releaseTestConfig()
        }
        assertNull(CommandPalette.find("/")?.item("baud"))
    }

    @Test
    fun `PPP commands persist shared settings and update active handler without reconnecting`() {
        val saved = mutableListOf<Boolean>()
        val store = ConfigStore(persist = { saved += it[PppSettings.DisconnectOnTerminate]!!.value })
        installConfig(store)
        val key = PppSettings.DisconnectOnTerminate
        val functions = FunctionStore()
        var disconnects = 0
        var reconnects = 0
        functions.register(ConnectionFunctions.Disconnect) { disconnects++ }
        functions.register(ConnectionFunctions.Reconnect) { reconnects++ }
        val handler = RetroPppHandler(logger = {})
        val output = ByteArrayOutputStream()
        val modem = HayesModem(output, 115200,
            config = HayesModemConfig(toneSimulationEnabled = false),
            logger = {}, pppHandler = handler)
        try {
            modem.receive("ATE0\rATD123\r".toByteArray())
            TuiYameObserver(functions, Terminal(interactive = false)).use { ui ->
                ui.type("/ppp-disconnect-on-terminate")
                assertEquals(listOf("/ppp-disconnect-on-terminate"), ui.paletteSnapshot()?.options?.map { it.value })
                ui.handleKeyAndAwait("Enter")
                val options = assertNotNull(ui.paletteSnapshot()).options
                assertEquals(listOf("on", "off"), options.map { it.value })
                assertEquals(listOf("current", ""), options.map { it.description })
                ui.type("off")
                ui.handleKeyAndAwait("Enter")
                assertFalse(store[key])
                assertFalse(ConfigStore[key])
                assertFalse(handler.disconnectOnTerminate)
                assertEquals(HayesModem.State.CONNECTED, modem.state)

                output.reset()
                val termination = PppEncoder().encode(PppFrame(
                    PppSession.LCP_PROTOCOL,
                    LcpPacket(LcpPacket.TERMINATE_REQUEST, 42, byteArrayOf()).encode()))
                modem.receive(termination)
                assertEquals(HayesModem.State.CONNECTED, modem.state)
                assertTrue(output.size() > 0) // Termination is still acknowledged when carrier is retained.

                ui.type("/ppp-disconnect-on-terminate")
                ui.handleKeyAndAwait("Enter")
                assertEquals(listOf("", "current"), ui.paletteSnapshot()?.options?.map { it.description })
                ui.handleKeyAndAwait("Escape")
                ui.type("/ppp-disconnect-on-terminate on")
                ui.handleKeyAndAwait("Enter")
                assertTrue(store[key])
                assertTrue(handler.disconnectOnTerminate)
                assertEquals(HayesModem.State.CONNECTED, modem.state)
                ui.type("/ppp-disconnect-on-terminate off")
                ui.handleKeyAndAwait("Enter")
                assertFalse(handler.disconnectOnTerminate)
                ui.type("/ppp-disconnect-on-terminate on")
                ui.handleKeyAndAwait("Enter")
                assertEquals(listOf(false, true, false, true), saved)
                assertEquals(0, disconnects)
                assertEquals(0, reconnects)
                modem.receive(termination)
                assertEquals(HayesModem.State.COMMAND, modem.state)
            }
        } finally { modem.close() }
        store.set(key to false)
        assertTrue(handler.disconnectOnTerminate) // Handler released its subscription on close.
    }

    @Test
    fun `PPP palette registrations are released with presentation`() {
        val registry = CommandPalette()
        installConfig(ConfigStore())
        val registrations = registerPppCommands(registry)
        assertEquals(listOf("on", "off"), registry.find("/ppp-disconnect-on-terminate")?.items()?.map { it.command })
        registrations.forEach(AutoCloseable::close)
        assertNull(registry.find("/ppp-disconnect-on-terminate"))
        val ui = TuiYameObserver(FunctionStore(), Terminal(interactive = false))
        ui.type("/ppp-disconnect-on-terminate")
        ui.handleKeyAndAwait("Enter")
        assertEquals(2, ui.paletteSnapshot()?.options?.size)
        ui.close()
        // Closing a presentation removes its registrations, including those held by an open palette.
        val field = TuiYameObserver::class.java.getDeclaredField("paletteRegistry").apply { isAccessible = true }
        assertNull((field.get(ui) as CommandPalette).find("/ppp-disconnect-on-terminate"))
    }

    private fun observer(
        onTone: (Boolean) -> Unit = {},
        onLogin: (String, String) -> Unit = { _, _ -> },
        configStore: no.skasti.yame.config.ConfigStore = ConfigStore(),
    ): TuiYameObserver {
        configStore.onChange[no.skasti.yame.modem.ModemSettings.Group].subscribe { changes ->
            changes[no.skasti.yame.modem.ModemSettings.Tone]?.let { onTone(it.value) }
            changes[no.skasti.yame.modem.ModemSettings.Login]?.let { onLogin(it.value.username!!, it.value.password!!) }
        }
        return run { installConfig(configStore); TuiYameObserver(functions = FunctionStore(), terminal = Terminal(interactive = false),
        ) }
    }

    private fun TuiYameObserver.type(text: String) { text.forEach { handleKeyAndAwait(it.toString()) } }
}
