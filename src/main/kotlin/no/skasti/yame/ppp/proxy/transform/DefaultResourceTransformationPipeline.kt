package no.skasti.yame.ppp.proxy.transform

internal fun defaultResourceTransformationPipeline(
    maxResponseBytes: Int,
    elementSimplifier: ElementSimplifierConfiguration = ElementSimplifierConfiguration.defaults(),
): ResourceTransformationPipeline {
    val imagePolicy = ImageOptimizationPolicy(maxEncodedBytes = maxResponseBytes)
    return ResourceTransformationPipeline(
        listOf(
            ScriptRemover(),
            StyleRemover(),
            AttributeCleaner(),
            ElementSimplifier(elementSimplifier, maxResponseBytes),
            LegacyTextResourceTransformer(),
            ImageTagTransformer(imagePolicy),
            JpegResourceTransformer(imagePolicy),
            PngResourceTransformer(imagePolicy),
        ),
    )
}
