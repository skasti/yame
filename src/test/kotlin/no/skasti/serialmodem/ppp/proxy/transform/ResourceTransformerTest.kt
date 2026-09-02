package no.skasti.serialmodem.ppp.proxy.transform

import no.skasti.serialmodem.ppp.proxy.ReferenceRole
import no.skasti.serialmodem.ppp.proxy.ResourceKind
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ResourceTransformerTest {
    @Test
    fun `pipeline orders transformers by phase priority and id`() {
        val order = mutableListOf<String>()
        fun transformer(
            id: String,
            phase: ResourceTransformPhase,
            priority: Int,
        ) = object : ResourceTransformer {
            override val id = id
            override val phase = phase
            override val priority = priority

            override fun supports(
                context: ResourceTransformationContext,
                representation: ResourceRepresentation,
            ) = true

            override fun transform(
                context: ResourceTransformationContext,
                representation: ResourceRepresentation,
            ): ResourceRepresentation {
                order += id
                return representation
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

        pipeline.transform(context(), representation())

        assertEquals(
            listOf("structural-early", "structural-late", "a-compat", "z-compat", "encode"),
            order,
        )
    }

    @Test
    fun `changed body strips stale validators while preserving transformer metadata`() {
        val pipeline = ResourceTransformationPipeline(
            listOf(
                object : ResourceTransformer {
                    override val id = "image-example"
                    override val phase = ResourceTransformPhase.OPTIMIZATION

                    override fun supports(
                        context: ResourceTransformationContext,
                        representation: ResourceRepresentation,
                    ) = true

                    override fun transform(
                        context: ResourceTransformationContext,
                        representation: ResourceRepresentation,
                    ) = representation.copy(
                        headers = representation.headers + ("Content-Type" to listOf("image/gif")),
                        body = byteArrayOf(4, 5),
                    )
                },
            ),
        )

        val transformed = pipeline.transform(
            context(),
            representation(
                headers = mapOf(
                    "Content-Type" to listOf("image/png"),
                    "ETag" to listOf("\"upstream\""),
                    "Content-Length" to listOf("3"),
                ),
            ),
        )

        assertContentEquals(byteArrayOf(4, 5), transformed.body)
        assertEquals(listOf("image/gif"), transformed.headers["Content-Type"])
        assertFalse(transformed.headers.keys.any { it.equals("ETag", true) })
        assertFalse(transformed.headers.keys.any { it.equals("Content-Length", true) })
    }

    private fun context() = ResourceTransformationContext(
        navigationIds = setOf(1L),
        legacyUri = URI("http://legacy.test/image.png"),
        upstreamUri = URI("https://modern.test/image.png"),
        kind = ResourceKind.IMAGE,
        relation = null,
        role = ReferenceRole.SUBRESOURCE,
        requestHeaders = emptyMap(),
        rewriteText = { it },
    )

    private fun representation(
        headers: Map<String, List<String>> = mapOf("Content-Type" to listOf("text/plain")),
    ) = ResourceRepresentation(
        statusCode = 200,
        headers = headers,
        body = byteArrayOf(1, 2, 3),
    )
}
