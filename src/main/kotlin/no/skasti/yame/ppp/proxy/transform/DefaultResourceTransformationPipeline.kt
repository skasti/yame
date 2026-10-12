package no.skasti.yame.ppp.proxy.transform

internal fun defaultResourceTransformationPipeline(maxResponseBytes: Int): ResourceTransformationPipeline {
    val imagePolicy = ImageOptimizationPolicy(maxEncodedBytes = maxResponseBytes)
    return ResourceTransformationPipeline(
        listOf(
            ScriptRemover(),
            StyleRemover(),
            ElementSimplifier(),
            AttributeCleaner(),
            LegacyTextResourceTransformer(),
            ImageTagTransformer(imagePolicy),
            JpegResourceTransformer(imagePolicy),
            PngResourceTransformer(imagePolicy),
        ),
    )
}
