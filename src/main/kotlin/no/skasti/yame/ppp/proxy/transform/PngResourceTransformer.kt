package no.skasti.yame.ppp.proxy.transform

internal class PngResourceTransformer(
    private val policy: ImageOptimizationPolicy = ImageOptimizationPolicy(),
) : ResourceTransformer {
    private val codec = LegacyImageCodec(policy)
    private val encoder = PngImageEncoder(policy, codec)

    override val id: String = "legacy-png-optimization"
    override val phase: ResourceTransformPhase = ResourceTransformPhase.OPTIMIZATION
    // Run after JPEG optimization, so PNG -> JPEG output is not recompressed again.
    override val priority: Int = 10

    override fun supports(
        context: ResourceTransformationContext,
        state: ResourceTransformationState,
    ): Boolean =
        state.resource.representation.supportsImageOptimization(policy, setOf("image/png"))

    override fun transform(
        context: ResourceTransformationContext,
        state: ResourceTransformationState,
    ): ResourceTransformationState {
        val bytes = state.resource.representation.body
        if (!PngImageEncoder.isStaticPng(bytes)) return state
        val decoded = codec.decode(bytes, "png") ?: return state
        val candidate = encoder.encode(decoded) ?: return state
        return state.withOptimizedImage(context, id, ImageDimensions(decoded.width, decoded.height), candidate)
    }
}
