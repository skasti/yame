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
    val transformationProfile: String,
    val rewriteText: (String) -> String,
)

internal data class ResourceRepresentation(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)

internal data class TransformedRepresentation(
    val profile: String,
    val representation: ResourceRepresentation,
)

internal data class Resource(
    val upstreamUri: URI,
    val source: ResourceRepresentation,
    val transformed: TransformedRepresentation? = null,
) {
    val representation: ResourceRepresentation
        get() = transformed?.representation ?: source
}

internal data class ResourceTransformationState(
    val resource: Resource,
    val cacheable: Boolean = true,
)

internal interface ResourceTransformer {
    val id: String
    val phase: ResourceTransformPhase
    val priority: Int get() = 0
    val cacheable: Boolean get() = true

    fun supports(
        context: ResourceTransformationContext,
        state: ResourceTransformationState,
    ): Boolean

    fun transform(
        context: ResourceTransformationContext,
        state: ResourceTransformationState,
    ): ResourceTransformationState
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

    fun transform(
        context: ResourceTransformationContext,
        resource: Resource,
    ): ResourceTransformationState =
        transformers.fold(ResourceTransformationState(resource)) { current, transformer ->
            if (!transformer.supports(context, current)) {
                current
            } else {
                val next = transformer.transform(context, current)
                next.copy(
                    cacheable = current.cacheable && transformer.cacheable && next.cacheable,
                )
            }
        }
}
