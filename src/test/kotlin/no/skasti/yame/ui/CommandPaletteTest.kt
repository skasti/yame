package no.skasti.yame.ui

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

class CommandPaletteTest {
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
            val registrations = registerTransformerCommands(store)
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
        }
        assertNull(CommandPalette.find("/")?.item("baud"))
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
        return TuiYameObserver(
            configStore = configStore, functions = no.skasti.yame.function.FunctionStore(), terminal = Terminal(interactive = false),
        )
    }

    private fun TuiYameObserver.type(text: String) { text.forEach { handleKeyAndAwait(it.toString()) } }
}
