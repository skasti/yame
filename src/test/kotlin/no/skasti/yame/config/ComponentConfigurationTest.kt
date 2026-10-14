package no.skasti.yame.config

import no.skasti.yame.ppp.proxy.transform.*
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
    fun `factories read installed settings while explicit config stays independent`() {
        val previous = ConfigStore.current
        val rules = ElementSimplifierConfiguration(mapOf("article" to ElementSimplifierRule("div")))
        val saved = YameConfiguration(modemConfig = HayesModemConfig(
            pppHttpCompatibilityConfig = no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig(elementSimplifier = rules),
        ))
        val store = applicationConfigStore(saved)
        try {
            ConfigStore.install(store)
            store.set(TransformerSettings.Style to false, ModemSettings.Tone to false)
            assertEquals(false, HtmlTransformerSettings.fromConfigStore().styleRemover)
            assertEquals(false, HayesModemConfig.fromConfigStore().toneSimulationEnabled)
            assertEquals(rules, ElementSimplifierConfiguration.fromConfigStore())
            val explicit = ElementSimplifierConfiguration(mapOf("article" to ElementSimplifierRule("p")))
            val component = ElementSimplifier(explicit)
            assertEquals("<p>text</p>", apply(component))
            assertEquals("<div>text</div>", apply(ElementSimplifier()))
            assertFailsWith<IllegalArgumentException> {
                store.set(TransformerSettings.Rules to explicit)
            }
            assertEquals(rules, ElementSimplifierConfiguration.fromConfigStore())
        } finally {
            ConfigStore.install(previous)
        }
    }
}
