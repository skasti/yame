package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.ppp.proxy.ReferenceRole
import no.skasti.yame.ppp.proxy.ResourceKind
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResourceTransformerTest {
    @Test
    fun `pipeline orders transformers by phase priority and id`() {
        val order = mutableListOf<String>()
        fun transformer(
            transformerId: String,
            transformPhase: ResourceTransformPhase,
            transformPriority: Int,
        ) = object : ResourceTransformer {
            override val id = transformerId
            override val phase = transformPhase
            override val priority = transformPriority

            override fun supports(
                context: ResourceTransformationContext,
                state: ResourceTransformationState,
            ) = true

            override fun transform(
                context: ResourceTransformationContext,
                state: ResourceTransformationState,
            ): ResourceTransformationState {
                order += id
                return state
            }
        }

        val pipeline = ResourceTransformationPipeline(
            listOf(
                transformer("z-compat", ResourceTransformPhase.COMPATIBILITY, 0),
                transformer("encode", ResourceTransformPhase.ENCODE, 0),
                transformer("a-compat", ResourceTransformPhase.COMPATIBILITY, 0),
                transformer("structural-late", ResourceTransformPhase.STRUCTURAL, 10),
                transformer("structural-early", ResourceTransformPhase.STRUCTURAL, -10),
            ),
        )

        pipeline.transform(context(), resource())

        assertEquals(
            listOf("structural-early", "structural-late", "a-compat", "z-compat", "encode"),
            order,
        )
    }

    @Test
    fun `pipeline rejects duplicate transformer ids`() {
        fun transformer(phase: ResourceTransformPhase) =
            object : ResourceTransformer {
                override val id = "duplicate"
                override val phase = phase

                override fun supports(
                    context: ResourceTransformationContext,
                    state: ResourceTransformationState,
                ) = true

                override fun transform(
                    context: ResourceTransformationContext,
                    state: ResourceTransformationState,
                ) = state
            }

        val error = assertFailsWith<IllegalArgumentException> {
            ResourceTransformationPipeline(
                listOf(
                    transformer(ResourceTransformPhase.STRUCTURAL),
                    transformer(ResourceTransformPhase.COMPATIBILITY),
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("duplicate"))
    }

    @Test
    fun `cacheability follows transformers as representation evolves`() {
        val lateNonCacheable =
            object : ResourceTransformer {
                override val id = "late"
                override val phase = ResourceTransformPhase.OPTIMIZATION
                override val cacheable = false

                override fun supports(
                    context: ResourceTransformationContext,
                    state: ResourceTransformationState,
                ) = state.resource.representation.headers["X-Ready"] == listOf("yes")

                override fun transform(
                    context: ResourceTransformationContext,
                    state: ResourceTransformationState,
                ) = state
            }
        val makeApplicable =
            object : ResourceTransformer {
                override val id = "early"
                override val phase = ResourceTransformPhase.STRUCTURAL

                override fun supports(
                    context: ResourceTransformationContext,
                    state: ResourceTransformationState,
                ) = true

                override fun transform(
                    context: ResourceTransformationContext,
                    state: ResourceTransformationState,
                ): ResourceTransformationState {
                    val current = state.resource.representation
                    return state.copy(
                        resource = state.resource.copy(
                            transformed = TransformedRepresentation(
                                context.transformationProfile,
                                current.copy(headers = current.headers + ("X-Ready" to listOf("yes"))),
                            ),
                        ),
                    )
                }
            }

        val result = ResourceTransformationPipeline(listOf(lateNonCacheable, makeApplicable))
            .transform(context(), resource())

        assertFalse(result.cacheable)
    }

    @Test
    fun `legacy text transform preserves source and creates transformed representation`() {
        val source = representation(
            headers = mapOf(
                "Content-Type" to listOf("text/html"),
                "ETag" to listOf("\"upstream\""),
                "Last-Modified" to listOf("Wed, 02 Sep 2026 12:30:00 GMT"),
                "Cache-Control" to listOf("public, max-age=3600"),
                "Expires" to listOf("Wed, 02 Sep 2026 13:30:00 GMT"),
                "Content-Length" to listOf("31"),
                "Content-MD5" to listOf("source-digest"),
            ),
            body = "<img src=\"https://example.test/a\">".toByteArray(),
        )
        val resource = Resource(URI("https://modern.test/page"), source)

        val result = ResourceTransformationPipeline(listOf(LegacyTextResourceTransformer()))
            .transform(context(rewriteText = { it.replace("https://", "http://") }), resource)

        assertSame(source, result.resource.source)
        assertFalse(result.cacheable)
        val transformed = requireNotNull(result.resource.transformed)
        assertEquals("netscape-4.08-v1", transformed.profile)
        assertTrue(transformed.representation.body.decodeToString().contains("http://example.test/a"))
        assertEquals(listOf("\"upstream\""), transformed.representation.headers["ETag"])
        assertEquals(
            listOf("Wed, 02 Sep 2026 12:30:00 GMT"),
            transformed.representation.headers["Last-Modified"],
        )
        assertEquals(listOf("public, max-age=3600"), transformed.representation.headers["Cache-Control"])
        assertEquals(
            listOf("Wed, 02 Sep 2026 13:30:00 GMT"),
            transformed.representation.headers["Expires"],
        )
        assertFalse(transformed.representation.headers.keys.any { it.equals("Content-Length", true) })
        assertFalse(transformed.representation.headers.keys.any { it.equals("Content-MD5", true) })
    }

    @Test
    fun `image tag transformer scales numeric dimensions proportionally`() {
        val source = representation(
            headers = mapOf("Content-Type" to listOf("text/html")),
            body = "<img class='hero' src='hero.jpg' width='900' height='700'>".toByteArray(),
        )
        val result =
            ResourceTransformationPipeline(listOf(ImageTagTransformer()))
                .transform(context(), Resource(URI("https://modern.test/page"), source))

        assertEquals(
            "<img class='hero' src='hero.jpg' width=\"514\" height=\"400\">",
            requireNotNull(result.resource.transformed).representation.body.decodeToString(),
        )
    }

    @Test
    fun `image tag transformer leaves missing or non pixel dimensions unchanged`() {
        val source = representation(
            headers = mapOf("Content-Type" to listOf("text/html")),
            body = """
                <img src="missing-height.jpg" width="900">
                <img src="percentage.jpg" width="100%" height="700">
            """.trimIndent().toByteArray(),
        )
        val result =
            ResourceTransformationPipeline(listOf(ImageTagTransformer()))
                .transform(context(), Resource(URI("https://modern.test/page"), source))

        val body = result.resource.representation.body.decodeToString()
        assertTrue(body.contains("<img src=\"missing-height.jpg\" width=\"900\">"))
        assertTrue(body.contains("<img src=\"percentage.jpg\" width=\"100%\" height=\"700\">"))
    }

    @Test
    fun `image tag transformer does not treat data dimensions as image dimensions`() {
        val source = representation(
            headers = mapOf("Content-Type" to listOf("text/html")),
            body = """
                <img src="hero.jpg" data-width="900" data-height="700" width="900" height="700">
            """.trimIndent().toByteArray(),
        )
        val result =
            ResourceTransformationPipeline(listOf(ImageTagTransformer()))
                .transform(context(), Resource(URI("https://modern.test/page"), source))

        assertEquals(
            """<img src="hero.jpg" data-width="900" data-height="700" width="514" height="400">""",
            requireNotNull(result.resource.transformed).representation.body.decodeToString(),
        )
    }

    @Test
    fun `pipeline rejects transformers that replace source representation`() {
        val transformer =
            object : ResourceTransformer {
                override val id = "bad-source-mutation"
                override val phase = ResourceTransformPhase.COMPATIBILITY

                override fun supports(
                    context: ResourceTransformationContext,
                    state: ResourceTransformationState,
                ) = true

                override fun transform(
                    context: ResourceTransformationContext,
                    state: ResourceTransformationState,
                ): ResourceTransformationState =
                    state.copy(
                        resource = state.resource.copy(
                            source = state.resource.source.copy(body = byteArrayOf(9)),
                        ),
                    )
            }

        val error = assertFailsWith<IllegalArgumentException> {
            ResourceTransformationPipeline(listOf(transformer)).transform(context(), resource())
        }

        assertTrue(error.message.orEmpty().contains("must preserve the source representation"))
    }

    @Test
    fun `pipeline can transform a bodyless resource from headers alone`() {
        val transformer =
            object : ResourceTransformer {
                override val id = "header-only"
                override val phase = ResourceTransformPhase.COMPATIBILITY

                override fun supports(
                    context: ResourceTransformationContext,
                    state: ResourceTransformationState,
                ) = state.resource.representation.headers["X-Modern"] == listOf("yes")

                override fun transform(
                    context: ResourceTransformationContext,
                    state: ResourceTransformationState,
                ): ResourceTransformationState {
                    val current = state.resource.representation
                    return state.copy(
                        resource = state.resource.copy(
                            transformed = TransformedRepresentation(
                                context.transformationProfile,
                                current.copy(headers = current.headers + ("X-Legacy" to listOf("yes"))),
                            ),
                        ),
                    )
                }
            }
        val source = representation(
            headers = mapOf("X-Modern" to listOf("yes")),
            body = ByteArray(0),
        )

        val result =
            ResourceTransformationPipeline(listOf(transformer))
                .transform(context(), Resource(URI("https://modern.test/empty"), source))

        assertEquals(listOf("yes"), result.resource.representation.headers["X-Legacy"])
        assertTrue(result.resource.representation.body.isEmpty())
    }

    @Test
    fun `unchanged resource has no transformed representation`() {
        val resource = resource()
        val result = ResourceTransformationPipeline(listOf(LegacyTextResourceTransformer()))
            .transform(context(), resource)

        assertSame(resource.source, result.resource.source)
        assertNull(result.resource.transformed)
        assertTrue(result.cacheable)
    }

    private fun context(
        rewriteText: (String) -> String = { it },
    ) = ResourceTransformationContext(
        navigationIds = setOf(1L),
        legacyUri = URI("http://legacy.test/image.png"),
        upstreamUri = URI("https://modern.test/image.png"),
        kind = ResourceKind.IMAGE,
        relation = null,
        role = ReferenceRole.SUBRESOURCE,
        requestHeaders = emptyMap(),
        requestMethod = "GET",
        transformationProfile = "netscape-4.08-v1",
        rewriteText = rewriteText,
    )

    private fun resource() = Resource(
        upstreamUri = URI("https://modern.test/image.png"),
        source = representation(),
    )

    private fun representation(
        headers: Map<String, List<String>> = mapOf("Content-Type" to listOf("text/plain")),
        body: ByteArray = byteArrayOf(1, 2, 3),
    ) = ResourceRepresentation(
        statusCode = 200,
        headers = headers,
        body = body,
    )
}
