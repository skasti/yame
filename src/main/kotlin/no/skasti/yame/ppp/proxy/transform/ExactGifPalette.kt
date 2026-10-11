package no.skasti.yame.ppp.proxy.transform

import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel

/** Exact colors only: never let ImageIO silently quantize artwork or alpha. */
internal class ExactGifPalette private constructor(private val colors: Map<Int, Int>) {
    fun indexedImage(source: BufferedImage): BufferedImage {
        val entries = colors.keys.toList()
        val model = IndexColorModel(
            8,
            entries.size,
            entries.map { (it ushr 16).toByte() }.toByteArray(),
            entries.map { (it ushr 8).toByte() }.toByteArray(),
            entries.map { it.toByte() }.toByteArray(),
            entries.indexOf(0),
        )
        val target = BufferedImage(source.width, source.height, BufferedImage.TYPE_BYTE_INDEXED, model)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                target.raster.setSample(x, y, 0, colors.getValue(canonicalColor(source.getRGB(x, y))))
            }
        }
        return target
    }

    companion object {
        fun from(image: BufferedImage): ExactGifPalette? {
            val colors = linkedMapOf<Int, Int>()
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val argb = image.getRGB(x, y)
                    val alpha = argb ushr 24
                    if (alpha != 0 && alpha != 255) return null
                    val color = canonicalColor(argb)
                    if (color !in colors) {
                        if (colors.size == 256) return null
                        colors[color] = colors.size
                    }
                }
            }
            return ExactGifPalette(colors)
        }

        private fun canonicalColor(argb: Int): Int = if (argb ushr 24 == 0) 0 else argb
    }
}
