package no.skasti.serialmodem.ppp.proxy.transform

import no.skasti.serialmodem.ppp.proxy.ReferenceRole
import no.skasti.serialmodem.ppp.proxy.ResourceKind
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageTagTransformerParsingTest {
    @Test
    fun `percentage dimension is not partially rewritten`() {
        val html = "<img src=\"hero.jpg\" width=100% height=700>"

        val result = transform(html)

        assertNull(result.transformed)
        assertEquals(html, result.representation.body.decodeToString())
    }

    @Test
    fun `dimension-like text inside quoted attribute is ignored`() {
        val html = "<img alt=\"foo width=900 height=700\" src=\"hero.jpg\" width=900 height=700>"

        val result = transform(html)

        assertEquals(
            "<img alt=\"foo width=900 height=700\" src=\"hero.jpg\" width=514 height=400>",
            requireNotNull(result.transformed).representation.body.decodeToString(),
        )
    }

    @Test
    fun `data dimension attributes do not replace real dimensions`() {
        val html = "<img data-width=\"1200\" data-height=\"900\" width=900 height=700 src=hero.jpg>"

        val result = transform(html)

        assertEquals(
            "<img data-width=\"1200\" data-height=\"900\" width=514 height=400 src=hero.jpg>",
            requireNotNull(result.transformed).representation.body.decodeToString(),
        )
    }

    private fun transform(html: String): Resource {
        val representation =
            ResourceRepresentation(
                statusCode = 200,
                headers = mapOf("Content-Type" to listOf("text/html")),
                body = html.toByteArray(),
            )
        return ResourceTransformationPipeline(listOf(ImageTagTransformer()))
            .transform(
                ResourceTransformationContext(
                    navigationIds = setOf(1L),
                    legacyUri = URI("http://legacy.test/page"),
                    upstreamUri = URI("https://modern.test/page"),
                    kind = ResourceKind.DOCUMENT,
                    relation = null,
                    role = ReferenceRole.NAVIGATION,
                    requestHeaders = emptyMap(),
                    requestMethod = "GET",
                    transformationProfile = "netscape-4.08-v1",
                    rewriteText = { it },
                ),
                Resource(URI("https://modern.test/page"), representation),
            ).resource
    }
}
