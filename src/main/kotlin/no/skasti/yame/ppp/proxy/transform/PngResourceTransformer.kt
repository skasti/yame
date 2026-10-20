package no.skasti.yame.ppp.proxy.transform

internal class PngResourceTransformer(
    private val policy: ImageOptimizationPolicy = ImageOptimizationPolicy(),
    private val diagnostic: (String) -> Unit = {},
) : ResourceTransformer {
    private val codec = LegacyImageCodec(policy)
    private val encoder = PngImageEncoder(policy, codec)

    override val id: String = "legacy-png-transcoder"
    override val phase: ResourceTransformPhase = ResourceTransformPhase.OPTIMIZATION
    // Run after JPEG optimization, so PNG -> JPEG output is not recompressed again.
    override val priority: Int = 10

    override fun supports(
        context: ResourceTransformationContext,
        state: ResourceTransformationState,
    ): Boolean = if (context.pngCompatibilityMode == PngCompatibilityMode.OPTIMIZE) {
        state.resource.representation.supportsImageOptimization(policy, PNG_CONTENT_TYPES)
    } else {
        // Let transform() report unsafe byte limits and content encodings instead of skipping silently.
        state.resource.representation.supportsImageContentType(PNG_CONTENT_TYPES, requireIdentityEncoding = false)
    }

    override fun transform(
        context: ResourceTransformationContext,
        state: ResourceTransformationState,
    ): ResourceTransformationState {
        val current = state.resource.representation
        val bytes = current.body
        val mode = context.pngCompatibilityMode
        if (mode == PngCompatibilityMode.REQUIRE_LEGACY_FORMAT) {
            if (bytes.isEmpty()) return skipped(context, state, "empty PNG body")
            if (bytes.size > policy.maxEncodedBytes) {
                return skipped(context, state, "encoded input exceeds ${policy.maxEncodedBytes} byte limit")
            }
            val encoding = current.headers.entries
                .firstOrNull { it.key.equals("content-encoding", ignoreCase = true) }
                ?.value?.firstOrNull()
            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                return skipped(context, state, "encoded body uses unsupported Content-Encoding '$encoding'")
            }
        }
        if (!PngImageEncoder.isStaticPng(bytes)) {
            return if (mode == PngCompatibilityMode.REQUIRE_LEGACY_FORMAT) {
                skipped(context, state, "malformed, truncated, or animated PNG (APNG is unsupported)")
            } else {
                state
            }
        }
        val decoded = codec.decode(bytes, "png") ?: return if (mode == PngCompatibilityMode.REQUIRE_LEGACY_FORMAT) {
            skipped(context, state, "PNG decoder rejected the input or image decode limits were exceeded")
        } else {
            state
        }
        val attempt = encoder.encodeAttempt(decoded, mode)
        val candidate = attempt.output ?: return if (mode == PngCompatibilityMode.REQUIRE_LEGACY_FORMAT) {
            skipped(context, state, attempt.failureReason ?: "no safe legacy encoding was produced")
        } else {
            state
        }
        if (mode == PngCompatibilityMode.REQUIRE_LEGACY_FORMAT && candidate.bytes.size > policy.maxEncodedBytes) {
            return skipped(context, state, "encoded output exceeds ${policy.maxEncodedBytes} byte limit")
        }
        return state.withImageOutput(
            context,
            id,
            ImageDimensions(decoded.width, decoded.height),
            candidate,
            requireSmaller = mode == PngCompatibilityMode.OPTIMIZE,
        )
    }

    private fun skipped(
        context: ResourceTransformationContext,
        state: ResourceTransformationState,
        reason: String,
    ): ResourceTransformationState {
        diagnostic("PNG legacy-format conversion skipped for ${context.upstreamUri}: $reason")
        return state
    }

    private companion object {
        val PNG_CONTENT_TYPES = setOf("image/png")
    }
}
