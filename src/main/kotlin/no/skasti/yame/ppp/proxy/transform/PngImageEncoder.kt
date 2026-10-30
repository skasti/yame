package no.skasti.yame.ppp.proxy.transform

import java.awt.image.BufferedImage
import java.awt.AlphaComposite
import java.awt.Color
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.abs

/** Netscape 4.08 renders PNG but ignores its transparency; GIF retains binary alpha. */
internal data class PngEncodingAttempt(
    val output: LegacyImageOutput?,
    val failureReason: String? = null,
)

internal class PngImageEncoder(
    private val policy: ImageOptimizationPolicy,
    private val codec: LegacyImageCodec,
) {
    fun encode(source: BufferedImage): LegacyImageOutput? =
        encode(source, PngCompatibilityMode.OPTIMIZE)

    fun encode(source: BufferedImage, mode: PngCompatibilityMode): LegacyImageOutput? =
        encodeAttempt(source, mode).output

    fun encodeAttempt(source: BufferedImage, mode: PngCompatibilityMode): PngEncodingAttempt {
        var transparent = false
        var partialAlpha = false
        if (source.colorModel.hasAlpha()) {
            for (y in 0 until source.height) {
                for (x in 0 until source.width) {
                    when (source.getRGB(x, y) ushr 24) {
                        0 -> transparent = true
                        255 -> Unit
                        else -> partialAlpha = true
                    }
                }
            }
        }

        val sourcePalette = ExactGifPalette.from(source)
        val resized = codec.resize(
            source,
            policy.targetDimensions(source.width, source.height),
            // Keep palette colors and binary alpha; bilinear filtering introduces new colors/alpha.
            preservePalette = sourcePalette != null || (transparent && !partialAlpha),
        )
        val palette = if (resized === source) sourcePalette else ExactGifPalette.from(resized)
        if (mode == PngCompatibilityMode.OPTIMIZE) {
            if (partialAlpha) return PngEncodingAttempt(null, "partial alpha needs a selected matte")
            if (palette != null) {
                return encodeGifOutput(palette.indexedImage(resized), resized)
                    ?: PngEncodingAttempt(null, "GIF encoding failed for an exact palette")
            }
            if (transparent) return PngEncodingAttempt(null, "binary transparency exceeds the exact GIF palette limit")

            val rgb = opaqueRgb(resized)
            val jpeg = codec.encodeJpeg(rgb)
                ?: return PngEncodingAttempt(null, "baseline JPEG encoder is unavailable")
            val decoded = codec.decode(jpeg, "jpeg")
                ?: return PngEncodingAttempt(null, "encoded JPEG could not be decoded for its fidelity check")
            if (!preservesDetail(rgb, decoded)) {
                return PngEncodingAttempt(null, "JPEG candidate failed the configured fidelity guard")
            }
            return PngEncodingAttempt(LegacyImageOutput(jpeg, "image/jpeg", resized.width, resized.height))
        }

        if (palette != null) {
            encodeGifOutput(palette.indexedImage(resized), resized)?.let { return it }
        }

        if (partialAlpha) {
            val matted = compositeOnWhite(resized)
            ExactGifPalette.from(matted)?.let { exactMattePalette ->
                encodeGifOutput(exactMattePalette.indexedImage(matted), matted)?.let { return it }
            }
            val quantized = BoundedGifQuantizer.quantize(matted, preserveBinaryTransparency = false)
            encodeGifOutput(quantized, resized)?.let { return it }
        } else if (transparent) {
            val quantized = BoundedGifQuantizer.quantize(resized, preserveBinaryTransparency = true)
            encodeGifOutput(quantized, resized)?.let { return it }
        }

        // Compatibility mode deliberately prioritizes a supported format over the optimizer's
        // JPEG fidelity guard. Partial alpha is first matted onto white before JPEG encoding.
        val rgb = if (partialAlpha || transparent) compositeOnWhite(resized) else opaqueRgb(resized)
        val jpeg = codec.encodeJpeg(rgb)
        if (jpeg != null) {
            return PngEncodingAttempt(LegacyImageOutput(jpeg, "image/jpeg", resized.width, resized.height))
        }

        // If a JPEG writer is unavailable, the fixed GIF palette still provides a bounded fallback.
        val quantized = BoundedGifQuantizer.quantize(resized, preserveBinaryTransparency = false)
        return encodeGifOutput(quantized, resized)
            ?: PngEncodingAttempt(null, "neither the GIF nor baseline JPEG encoder produced a safe output")
    }

    private fun encodeGifOutput(image: BufferedImage, dimensions: BufferedImage): PngEncodingAttempt? =
        encodeGif(image)?.let { bytes ->
            PngEncodingAttempt(LegacyImageOutput(bytes, "image/gif", dimensions.width, dimensions.height))
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

    private fun compositeOnWhite(image: BufferedImage): BufferedImage {
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val graphics = rgb.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.composite = AlphaComposite.SrcOver
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
