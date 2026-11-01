package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.ppp.proxy.transform.readability.ReadablePageTransformer

internal fun defaultResourceTransformationPipeline(
    maxResponseBytes: Int,
    elementSimplifier: ElementSimplifierConfiguration = ElementSimplifierConfiguration.defaults(),
): ResourceTransformationPipeline {
    val imagePolicy = ImageOptimizationPolicy(maxEncodedBytes = maxResponseBytes)
    return ResourceTransformationPipeline(
        listOf(
            ReadablePageTransformer(maxResponseBytes),
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
