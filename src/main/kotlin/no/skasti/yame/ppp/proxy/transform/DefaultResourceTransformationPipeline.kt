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
            ScriptRemover(control),
            StyleRemover(control),
            AttributeCleaner(control),
            ElementSimplifier(elementSimplifier, maxResponseBytes, control),
            LegacyTextResourceTransformer(),
            ImageTagTransformer(imagePolicy),
            JpegResourceTransformer(imagePolicy),
            PngResourceTransformer(imagePolicy),
        ),
    )
}
