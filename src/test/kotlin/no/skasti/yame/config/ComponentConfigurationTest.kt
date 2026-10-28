package no.skasti.yame.config

import no.skasti.yame.ppp.proxy.transform.*
import no.skasti.yame.ppp.proxy.transform.ElementSimplifierSettings
import no.skasti.yame.ppp.proxy.transform.TransformerSettings
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.modem.ModemSettings
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComponentConfigurationTest {
    private fun apply(transformer: ResourceTransformer): String {
        val uri = java.net.URI("http://example.com/")
        val context = ResourceTransformationContext(
            emptySet(), uri, uri, no.skasti.yame.ppp.proxy.ResourceKind.DOCUMENT, null,
            no.skasti.yame.ppp.proxy.ReferenceRole.NAVIGATION, emptyMap(), "GET", "default", { it },
        )
        val resource = Resource(uri, ResourceRepresentation(200,
            mapOf("content-type" to listOf("text/html; charset=UTF-8")), "<article>text</article>".toByteArray()))
        return ResourceTransformationPipeline(listOf(transformer)).transform(context, resource)
            .resource.representation.body.toString(Charsets.UTF_8)
    }

    @Test
    fun `process store must be installed and installation has an idempotent lifetime`() {
        assertFailsWith<IllegalStateException> { ConfigStore.current }
        assertFailsWith<IllegalStateException> { HayesModemConfig.fromConfigStore() }
        val first = ConfigStore(emptyList())
        val installation = ConfigStore.install(first)
        try {
            assertEquals(first, ConfigStore.current)
            assertFailsWith<IllegalStateException> { ConfigStore.install(ConfigStore(emptyList())) }
        } finally { installation.close() }
        assertFailsWith<IllegalStateException> { ConfigStore.current }
        ConfigStore.install(first).use {
            installation.close()
            assertEquals(first, ConfigStore.current)
        }
        assertFailsWith<IllegalStateException> { ConfigStore.current }
    }

    @Test
    fun `explicit component configuration does not require an installed store`() {
        assertFailsWith<IllegalStateException> { ConfigStore.current }
        no.skasti.yame.modem.HayesModem(baudRate = 9600, config = HayesModemConfig(), logger = {}).use {
            it.receive("AT\r".toByteArray())
        }
        val rules = ElementSimplifierConfiguration(mapOf("article" to ElementSimplifierRule("p")))
        assertEquals("<p>text</p>", apply(ElementSimplifier(rules)))
        no.skasti.yame.ppp.proxy.SystemHttpCompatibilityHandler(
            config = no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig(),
        ).close()
        no.skasti.yame.ppp.PppAddressResolver(config = no.skasti.yame.ppp.PppIpConfig(), logger = {})
        assertFailsWith<IllegalStateException> { ConfigStore.current }
    }

    @Test
    fun `serial and logging values stay scoped and immutable`() {
        val serial = no.skasti.yame.serial.SerialConfig(baudRate = 9600)
        assertEquals(9600, serial.baudRate)
        assertFailsWith<IllegalStateException> { ConfigStore.current }
        val levels = mutableMapOf(no.skasti.yame.logging.YameLogModule.MODEM to no.skasti.yame.logging.YameLogLevel.DEBUG)
        val logging = no.skasti.yame.logging.LoggingConfig(levels, no.skasti.yame.logging.PacketCaptureLimits())
        levels.clear()
        assertEquals(no.skasti.yame.logging.YameLogLevel.DEBUG, logging.levels[no.skasti.yame.logging.YameLogModule.MODEM])
        assertFailsWith<UnsupportedOperationException> { (logging.levels as MutableMap).clear() }
    }

    @Test
    fun `modem factory needs only modem settings`() {
        val defaults = HayesModemConfig()
        val store = ConfigStore(listOf(
            ModemSettings.PickupTime to defaults.pickupTime,
            ModemSettings.DialToneTime to defaults.dialToneTime,
            ModemSettings.Handshake to defaults.handshakeProfile,
            ModemSettings.Tone to false,
            ModemSettings.Login to no.skasti.yame.modem.LoginCredentials(null, null),
        ))
        ConfigStore.install(store).use {
            assertEquals(defaults.copy(toneSimulationEnabled = false), HayesModemConfig.fromConfigStore())
        }
    }

    @Test
    fun `factories read installed settings while explicit config stays independent`() {
        val rules = ElementSimplifierConfiguration(mapOf("article" to ElementSimplifierRule("div")))
        val store = testConfigStore(ElementSimplifierSettings.Rules to rules)
        store.ensureLoaded(TransformerSettings)
        store.ensureLoaded(ModemSettings)
        ConfigStore.install(store).use {
            store.set(TransformerSettings.Style to false, ModemSettings.Tone to false)
            assertEquals(false, HtmlTransformerSettings.fromConfigStore().styleRemover)
            assertEquals(false, HayesModemConfig.fromConfigStore().toneSimulationEnabled)
            assertEquals(rules, ElementSimplifierConfiguration.fromConfigStore())
            val explicit = ElementSimplifierConfiguration(mapOf("article" to ElementSimplifierRule("p")))
            val component = ElementSimplifier(explicit)
            assertEquals("<p>text</p>", apply(component))
            assertEquals("<div>text</div>", apply(ElementSimplifier()))
            store.set(ElementSimplifierSettings.Rules to explicit)
            assertEquals(explicit, ElementSimplifierConfiguration.fromConfigStore())
            assertEquals("<p>text</p>", apply(component))
        }
    }
}
