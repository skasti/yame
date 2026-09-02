package no.skasti.serialmodem.ppp.proxy.transform

import no.skasti.serialmodem.ppp.proxy.ReferenceRole
import no.skasti.serialmodem.ppp.proxy.ResourceKind
import no.skasti.serialmodem.ppp.proxy.ResourceRelation
import java.net.URI

internal enum class ResourceTransformPhase {
    DECODE,
    STRUCTURAL,
    COMPATIBILITY,
    OPTIMIZATION,
    ENCODE,
}

internal data class ResourceTransformationContext(
    val navigationIds: Set<Long>,
    val legacyUri: URI,
    val upstreamUri: URI,
    val kind: ResourceKind,
    val relation: ResourceRelation?,
    val role: ReferenceRole,
    val requestHeaders: Map<String, List<String>>,
    val rewriteText: (String) -> String,
)

internal data class ResourceRepresentation(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)

internal interface ResourceTransformer {
    val id: String
    val phase: ResourceTransformPhase
    val priority: Int get() = 0
    val cacheable: Boolean get() = true

    fun supports(
        context: ResourceTransformationContext,
        representation: ResourceRepresentation,
    ): Boolean

    fun transform(
        context: ResourceTransformationContext,
        representation: ResourceRepresentation,
    ): ResourceRepresentation
}

internal class ResourceTransformationPipeline(
    transformers: List<ResourceTransformer>,
) {
    private val transformers =
        transformers.sortedWith(
            compareBy<ResourceTransformer> { it.phase.ordinal }
                .thenBy { it.priority }
                .thenBy { it.id },
        )

    fun supports(
        context: ResourceTransformationContext,
        representation: ResourceRepresentation,
    ): Boolean = transformers.any { it.supports(context, representation) }

    fun isCacheable(
        context: ResourceTransformationContext,
        representation: ResourceRepresentation,
    ): Boolean =
        transformers
            .filter { it.supports(context, representation) }
            .all { it.cacheable }

    fun transform(
        context: ResourceTransformationContext,
        representation: ResourceRepresentation,
    ): ResourceRepresentation {
        val transformed =
            transformers.fold(representation) { current, transformer ->
                if (transformer.supports(context, current)) transformer.transform(context, current) else current
            }
        return if (transformed.body.contentEquals(representation.body)) {
            transformed
        } else {
            transformed.copy(
                headers = stripStaleRepresentationMetadata(transformed.headers),
            )
        }
    }
}
