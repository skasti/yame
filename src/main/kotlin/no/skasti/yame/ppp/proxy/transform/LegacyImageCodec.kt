package no.skasti.yame.ppp.proxy.transform

import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

internal class LegacyImageCodec(private val policy: ImageOptimizationPolicy) {
    fun decode(bytes: ByteArray, expectedFormat: String): BufferedImage? =
        if (bytes.size > policy.maxEncodedBytes) null else runCatching {
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) return@use null

                val reader = readers.next()
                try {
                    if (!reader.formatName.equals(expectedFormat, ignoreCase = true)) return@use null
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

    fun resize(
        source: BufferedImage,
        dimensions: ImageDimensions,
        preservePalette: Boolean = false,
    ): BufferedImage {
        if (source.width == dimensions.width && source.height == dimensions.height) return source
        val type = if (source.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val target = BufferedImage(dimensions.width, dimensions.height, type)
        val graphics = target.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                if (preservePalette) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                else RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, dimensions.width, dimensions.height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    fun encodeJpeg(image: BufferedImage): ByteArray? =
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
}
