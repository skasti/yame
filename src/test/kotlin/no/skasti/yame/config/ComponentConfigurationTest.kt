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

    @Test fun `lazy in memory defaults preserve explicit and transient values`() {
        val owner = object : Settings("isolated") {
            val group = SettingGroup(this)
            val Supplied = SettingKey<Int>("supplied", group)
            val Defaulted = SettingKey<Int>("defaulted", group, default = { 7 })
        }
        val store = ConfigStore(listOf(owner.Supplied to 3), listOf(owner.Supplied to 5))
        assertEquals(7, store[owner.Defaulted])
        assertEquals(5, store[owner.Supplied])
        store.set(owner.Defaulted to 9)
        assertEquals(5, store[owner.Supplied])
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
        val store = ConfigStore(listOf(ElementSimplifierSettings.Rules to rules))
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
