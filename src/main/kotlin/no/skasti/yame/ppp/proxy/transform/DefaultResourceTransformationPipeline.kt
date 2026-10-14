package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.config.ElementSimplifierConfiguration
import no.skasti.yame.config.HtmlTransformerControl

internal fun defaultResourceTransformationPipeline(
    maxResponseBytes: Int,
    elementSimplifier: ElementSimplifierConfiguration = ElementSimplifierConfiguration.defaults(),
    control: HtmlTransformerControl? = null,
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
