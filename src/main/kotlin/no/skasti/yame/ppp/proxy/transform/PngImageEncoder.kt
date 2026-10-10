package no.skasti.yame.ppp.proxy.transform

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.abs

/** Netscape 4.08 renders PNG but ignores its transparency; GIF retains binary alpha. */
internal class PngImageEncoder(
    private val policy: ImageOptimizationPolicy,
    private val codec: LegacyImageCodec,
) {
    fun encode(source: BufferedImage): LegacyImageOutput? {
        var transparent = false
        if (source.colorModel.hasAlpha()) {
            for (y in 0 until source.height) {
                for (x in 0 until source.width) {
                    when (source.getRGB(x, y) ushr 24) {
                        0 -> transparent = true
                        255 -> Unit
                        // A matte requires page-background knowledge we do not have.
                        else -> return null
                    }
                }
            }
        }

        val sourcePalette = ExactGifPalette.from(source)
        val resized = codec.resize(
            source,
            policy.targetDimensions(source.width, source.height),
            // Keep palette colors and binary alpha; bilinear filtering introduces new colors/alpha.
            preservePalette = sourcePalette != null || transparent,
        )
        val palette = if (resized === source) sourcePalette else ExactGifPalette.from(resized)
        val output = if (palette != null) {
            encodeGif(palette.indexedImage(resized))?.let {
                LegacyImageOutput(it, "image/gif", resized.width, resized.height)
            }
        } else {
            if (transparent) return null
            // An ARGB PNG can be completely opaque. JPEG writers require an RGB raster.
            val rgb = opaqueRgb(resized)
            val jpeg = codec.encodeJpeg(rgb) ?: return null
            val decoded = codec.decode(jpeg, "jpeg") ?: return null
            if (!preservesDetail(rgb, decoded)) return null
            LegacyImageOutput(jpeg, "image/jpeg", resized.width, resized.height)
        }
        return output
    }

    private fun opaqueRgb(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_INT_RGB) return image
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val graphics = rgb.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return rgb
    }

    private fun encodeGif(image: BufferedImage): ByteArray? = runCatching {
        val writers = ImageIO.getImageWritersByFormatName("gif")
        if (!writers.hasNext()) return@runCatching null
        val writer = writers.next()
        try {
            ByteArrayOutputStream().use { output ->
                MemoryCacheImageOutputStream(output).use { imageOutput ->
                    writer.output = imageOutput
                    val params = writer.defaultWriteParam
                    if (params.canWriteProgressive()) params.progressiveMode = ImageWriteParam.MODE_DISABLED
                    writer.write(null, IIOImage(image, null, null), params)
                }
                output.toByteArray()
            }
        } finally {
            writer.dispose()
        }
    }.getOrNull()

    /** Bound both global error and severe local changes (e.g. text edges/chroma bleeding). */
    private fun preservesDetail(source: BufferedImage, output: BufferedImage): Boolean {
        if (source.width != output.width || source.height != output.height) return false
        var squaredError = 0L
        var severePixels = 0L
        val pixels = source.width.toLong() * source.height
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val original = source.getRGB(x, y)
                val candidate = output.getRGB(x, y)
                var severe = false
                for (shift in 0..16 step 8) {
                    val error = abs(((original ushr shift) and 255) - ((candidate ushr shift) and 255))
                    squaredError += error.toLong() * error
                    if (error > 48) severe = true
                }
                if (severe) severePixels++
            }
        }
        return squaredError <= pixels * 3 * 12 * 12 && severePixels * 100 <= pixels
    }

    companion object {
        /** ImageIO ignores APNG animation chunks; avoid silently returning only its default frame. */
        fun isStaticPng(bytes: ByteArray): Boolean {
            val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
            if (bytes.size < signature.size || !bytes.copyOfRange(0, 8).contentEquals(signature)) return false
            var offset = 8
            while (offset.toLong() + 12 <= bytes.size) {
                var length = 0L
                for (index in offset until offset + 4) length = (length shl 8) or (bytes[index].toLong() and 255)
                val next = offset.toLong() + length + 12
                if (next > bytes.size) return false
                val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
                if (type == "acTL") return false
                if (type == "IEND") return length == 0L
                offset = next.toInt()
            }
            return false
        }
    }
}
