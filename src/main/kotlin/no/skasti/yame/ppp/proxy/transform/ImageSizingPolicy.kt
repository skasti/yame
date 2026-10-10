package no.skasti.yame.ppp.proxy.transform

import kotlin.math.min
import kotlin.math.roundToInt

internal data class ImageDimensions(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0) { "Image width must be positive" }
        require(height > 0) { "Image height must be positive" }
    }
}

internal data class ImageOptimizationPolicy(
    val maxWidth: Int = 600,
    val maxHeight: Int = 400,
    val jpegQuality: Float = 0.55f,
    val maxDecodedPixels: Long = 16_000_000L,
    val maxDecodedRasterBytes: Long = 48L * 1024 * 1024,
    val maxEncodedBytes: Int = 32 * 1024 * 1024,
) {
    init {
        require(maxWidth > 0) { "Image maxWidth must be positive" }
        require(maxHeight > 0) { "Image maxHeight must be positive" }
        require(jpegQuality in 0f..1f) { "JPEG quality must be between 0 and 1" }
        require(maxEncodedBytes > 0) { "Image maxEncodedBytes must be positive" }
        require(maxDecodedPixels > 0) { "Image maxDecodedPixels must be positive" }
        require(maxDecodedRasterBytes > 0) { "Image maxDecodedRasterBytes must be positive" }
    }

    fun targetDimensions(sourceWidth: Int, sourceHeight: Int): ImageDimensions {
        val scale =
            min(
                1.0,
                min(
                    maxWidth.toDouble() / sourceWidth.toDouble(),
                    maxHeight.toDouble() / sourceHeight.toDouble(),
                ),
            )
        return ImageDimensions(
            width = (sourceWidth * scale).roundToInt().coerceAtLeast(1),
            height = (sourceHeight * scale).roundToInt().coerceAtLeast(1),
        )
    }
}
