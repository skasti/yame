package no.skasti.yame.ppp.proxy.transform

internal class JpegResourceTransformer(
    private val policy: ImageOptimizationPolicy = ImageOptimizationPolicy(),
) : ResourceTransformer {
    private val codec = LegacyImageCodec(policy)

    override val id: String = "legacy-jpeg-optimization"
    override val phase: ResourceTransformPhase = ResourceTransformPhase.OPTIMIZATION

    override fun supports(
        context: ResourceTransformationContext,
        state: ResourceTransformationState,
    ): Boolean =
        state.resource.representation.supportsImageOptimization(policy, JPEG_CONTENT_TYPES)

    override fun transform(
        context: ResourceTransformationContext,
        state: ResourceTransformationState,
    ): ResourceTransformationState {
        val decoded = codec.decode(state.resource.representation.body, "jpeg") ?: return state
        val dimensions = policy.targetDimensions(decoded.width, decoded.height)
        val resized = codec.resize(decoded, dimensions)
        val encoded = codec.encodeJpeg(resized) ?: return state
        return state.withOptimizedImage(
            context,
            id,
            ImageDimensions(decoded.width, decoded.height),
            LegacyImageOutput(encoded, "image/jpeg", resized.width, resized.height),
        )
    }

    private companion object {
        val JPEG_CONTENT_TYPES = setOf("image/jpeg", "image/jpg", "image/pjpeg")
    }
}
