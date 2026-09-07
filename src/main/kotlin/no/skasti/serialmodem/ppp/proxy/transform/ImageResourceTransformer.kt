package no.skasti.serialmodem.ppp.proxy.transform

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

internal class ImageResourceTransformer(
    private val policy: ImageOptimizationPolicy = ImageOptimizationPolicy(),
) : ResourceTransformer {
    override val id: String = "legacy-image-optimization"
    override val phase: ResourceTransformPhase = ResourceTransformPhase.OPTIMIZATION

    override fun supports(
        context: ResourceTransformationContext,
        state: ResourceTransformationState,
    ): Boolean {
        val representation = state.resource.representation
        if (representation.body.isEmpty()) return false

        val contentEncoding = firstHeaderValue(representation.headers, "content-encoding")
        if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) return false

        val contentType =
            firstHeaderValue(representation.headers, "content-type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?: return false

        return contentType in JPEG_CONTENT_TYPES
    }

    override fun transform(
        context: ResourceTransformationContext,
        state: ResourceTransformationState,
    ): ResourceTransformationState {
        val current = state.resource.representation
        val decoded = decodeJpeg(current.body) ?: return state
        val targetDimensions = policy.targetDimensions(decoded.width, decoded.height)
        val resized =
            if (targetDimensions.width == decoded.width && targetDimensions.height == decoded.height) {
                decoded
            } else {
                resizeForLegacyDisplay(decoded, targetDimensions)
            }
        val encoded = encodeJpeg(resized) ?: return state

        // Recompression is an optimization, not a compatibility requirement for JPEG.
        // Never make the slow serial transfer larger than the original.
        if (encoded.size >= current.body.size) return state

        val transformedHeaders =
            transformedHeadersFrom(current.headers)
                .filterKeys { !it.equals("content-type", ignoreCase = true) } +
                ("Content-Type" to listOf("image/jpeg"))

        val transformed =
            ResourceRepresentation(
                statusCode = current.statusCode,
                headers = transformedHeaders,
                body = encoded,
            )

        return state.copy(
            resource =
                state.resource.copy(
                    transformed =
                        TransformedRepresentation(
                            profile = context.transformationProfile,
                            representation = transformed,
                            transformations =
                                state.resource.transformed?.transformations.orEmpty() +
                                    ResourceTransformationSummary(
                                        transformerId = id,
                                        sourceBytes = current.body.size,
                                        outputBytes = encoded.size,
                                        detail = "${decoded.width}x${decoded.height} -> ${resized.width}x${resized.height}",
                                    ),
                        ),
                ),
        )
    }

    private fun decodeJpeg(bytes: ByteArray): BufferedImage? =
        runCatching {
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) return@use null

                val reader = readers.next()
                try {
                    reader.setInput(input, true, true)
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width <= 0 || height <= 0) return@use null
                    if (width.toLong() * height.toLong() > policy.maxDecodedPixels) return@use null
                    reader.read(0)
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()

    private fun resizeForLegacyDisplay(
        source: BufferedImage,
        dimensions: ImageDimensions,
    ): BufferedImage {
        val target = BufferedImage(dimensions.width, dimensions.height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, dimensions.width, dimensions.height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray? =
        runCatching {
            val writers = ImageIO.getImageWritersByFormatName("jpeg")
            if (!writers.hasNext()) return@runCatching null

            val writer = writers.next()
            try {
                ByteArrayOutputStream().use { output ->
                    MemoryCacheImageOutputStream(output).use { imageOutput ->
                        writer.output = imageOutput
                        val params = writer.defaultWriteParam
                        if (params.canWriteCompressed()) {
                            params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                            params.compressionQuality = policy.jpegQuality
                        }
                        if (params.canWriteProgressive()) {
                            params.progressiveMode = ImageWriteParam.MODE_DISABLED
                        }
                        writer.write(null, IIOImage(image, null, null), params)
                    }
                    output.toByteArray()
                }
            } finally {
                writer.dispose()
            }
        }.getOrNull()

    private fun firstHeaderValue(
        headers: Map<String, List<String>>,
        name: String,
    ): String? =
        headers.entries
            .firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

    private companion object {
        val JPEG_CONTENT_TYPES = setOf("image/jpeg", "image/jpg", "image/pjpeg")
    }
}
