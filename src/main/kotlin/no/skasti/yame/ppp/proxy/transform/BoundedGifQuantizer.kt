package no.skasti.yame.ppp.proxy.transform

import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel

/** A fixed 6x7x6 palette bounds legacy GIF output and makes fallback colors deterministic. */
internal object BoundedGifQuantizer {
    private const val RED_LEVELS = 6
    private const val GREEN_LEVELS = 7
    private const val BLUE_LEVELS = 6
    private const val COLOR_COUNT = RED_LEVELS * GREEN_LEVELS * BLUE_LEVELS
    private const val TRANSPARENT_INDEX = COLOR_COUNT

    fun quantize(source: BufferedImage, preserveBinaryTransparency: Boolean): BufferedImage {
        val hasTransparentPixels =
            preserveBinaryTransparency && source.colorModel.hasAlpha() && hasTransparentPixels(source)
        val paletteSize = COLOR_COUNT + if (hasTransparentPixels) 1 else 0
        val reds = ByteArray(paletteSize)
        val greens = ByteArray(paletteSize)
        val blues = ByteArray(paletteSize)
        for (red in 0 until RED_LEVELS) {
            for (green in 0 until GREEN_LEVELS) {
                for (blue in 0 until BLUE_LEVELS) {
                    val index = index(red, green, blue)
                    reds[index] = level(red, RED_LEVELS).toByte()
                    greens[index] = level(green, GREEN_LEVELS).toByte()
                    blues[index] = level(blue, BLUE_LEVELS).toByte()
                }
            }
        }
        val model = IndexColorModel(
            8,
            paletteSize,
            reds,
            greens,
            blues,
            if (hasTransparentPixels) TRANSPARENT_INDEX else -1,
        )
        val result = BufferedImage(source.width, source.height, BufferedImage.TYPE_BYTE_INDEXED, model)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val argb = source.getRGB(x, y)
                val alpha = argb ushr 24
                val colorIndex = if (hasTransparentPixels && alpha == 0) {
                    TRANSPARENT_INDEX
                } else {
                    val red = compositeOnWhite((argb ushr 16) and 255, alpha)
                    val green = compositeOnWhite((argb ushr 8) and 255, alpha)
                    val blue = compositeOnWhite(argb and 255, alpha)
                    index(
                        nearestLevel(red, RED_LEVELS),
                        nearestLevel(green, GREEN_LEVELS),
                        nearestLevel(blue, BLUE_LEVELS),
                    )
                }
                result.raster.setSample(x, y, 0, colorIndex)
            }
        }
        return result
    }

    private fun hasTransparentPixels(image: BufferedImage): Boolean {
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.getRGB(x, y) ushr 24 == 0) return true
            }
        }
        return false
    }

    private fun index(red: Int, green: Int, blue: Int): Int =
        red * GREEN_LEVELS * BLUE_LEVELS + green * BLUE_LEVELS + blue

    private fun level(index: Int, count: Int): Int =
        (index * 255 + (count - 1) / 2) / (count - 1)

    private fun nearestLevel(value: Int, count: Int): Int =
        (value * (count - 1) + 127) / 255

    private fun compositeOnWhite(component: Int, alpha: Int): Int =
        (component * alpha + 255 * (255 - alpha) + 127) / 255
}
