package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.ppp.proxy.ReferenceRole
import no.skasti.yame.ppp.proxy.ResourceKind
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.zip.CRC32
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PngImageResourceTransformerTest {
    @Test
    fun `large true color png becomes smaller baseline jpeg within legacy canvas`() {
        for ((width, height, expectedWidth, expectedHeight) in listOf(
            listOf(1600, 1000, 600, 375),
            listOf(800, 1600, 200, 400),
        )) {
            val source = png(photo(width, height))
            val result = transform(source)
            val output = output(result, source, "image/jpeg")
            assertEquals(expectedWidth, output.width)
            assertEquals(expectedHeight, output.height)
            val bytes = result.resource.representation.body
            assertTrue(hasJpegMarker(bytes, 0xc0), "JPEG must have baseline SOF0")
            assertTrue(!hasJpegMarker(bytes, 0xc2), "JPEG must not be progressive")
        }
    }

    @Test
    fun `small opaque argb png is not enlarged and can become jpeg`() {
        val source = png(photo(320, 200, BufferedImage.TYPE_INT_ARGB))
        val output = output(transform(source), source, "image/jpeg")
        assertEquals(320, output.width)
        assertEquals(200, output.height)
    }

    @Test
    fun `true color artwork uses exact gif colors instead of lossy jpeg`() {
        val image = artwork(240, 160)
        val source = png(image, uncompressed = true)
        val result = transform(source)
        val output = output(result, source, "image/gif")
        assertPixelsEqual(image, output)
        assertEquals("GIF89a", result.resource.representation.body.copyOfRange(0, 6).decodeToString())
    }

    @Test
    fun `indexed png including tRNS preserves transparency and opaque black`() {
        val model = IndexColorModel(
            8, 4,
            byteArrayOf(0, 0, 255.toByte(), 17),
            byteArrayOf(0, 0, 0, 23),
            byteArrayOf(0, 0, 0, 47),
            byteArrayOf(0, 255.toByte(), 255.toByte(), 0),
        )
        val image = BufferedImage(240, 160, BufferedImage.TYPE_BYTE_INDEXED, model)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) image.raster.setSample(x, y, 0, (x + y) % 4)
        }
        val source = png(image, uncompressed = true)
        val output = output(transform(source), source, "image/gif")
        assertPixelsEqual(image, output)
        assertEquals(0, output.getRGB(0, 0) ushr 24)
        assertEquals(0xff000000.toInt(), output.getRGB(1, 0))
    }

    @Test
    fun `binary alpha survives resizing without inventing partial transparency`() {
        val source = png(artwork(1200, 600, transparent = true), uncompressed = true)
        val output = output(transform(source), source, "image/gif")
        assertEquals(600, output.width)
        assertEquals(300, output.height)
        val alphas = (0 until output.height).flatMap { y ->
            (0 until output.width).map { x -> output.getRGB(x, y) ushr 24 }
        }.toSet()
        assertEquals(setOf(0, 255), alphas)
    }

    @Test
    fun `gif allows 255 opaque colors plus a transparent palette entry`() {
        val image = paletteBoundary(opaqueColors = 255)
        val source = png(image, uncompressed = true)
        assertPixelsEqual(image, output(transform(source), source, "image/gif"))
    }

    @Test
    fun `binary transparency beyond gif palette capacity is passed through`() {
        val source = png(paletteBoundary(opaqueColors = 256), uncompressed = true)
        assertPassthrough(source)
    }

    @Test
    fun `partial alpha is passed through without assuming a page background`() {
        val image = artwork(1200, 600, transparent = true)
        image.setRGB(4, 5, 0x80ff0000.toInt())
        assertPassthrough(png(image, uncompressed = true))
    }

    @Test
    fun `partial palette alpha is also passed through`() {
        val model = IndexColorModel(8, 2, byteArrayOf(0, -1), byteArrayOf(0, 0), byteArrayOf(0, 0), byteArrayOf(-1, 127))
        val image = BufferedImage(120, 80, BufferedImage.TYPE_BYTE_INDEXED, model)
        image.raster.setSample(1, 1, 0, 1)
        assertPassthrough(png(image, uncompressed = true))
    }

    @Test
    fun `noisy high color detail is not damaged by jpeg compression`() {
        val image = BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB)
        val random = Random(37)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) image.setRGB(x, y, random.nextInt() and 0xffffff)
        }
        val source = png(image)
        val candidate = requireNotNull(LegacyImageCodec(ImageOptimizationPolicy()).encodeJpeg(image))
        assertTrue(candidate.size < source.size, "This must exercise the quality guard, not size fallback")
        assertPassthrough(source)
    }

    @Test
    fun `already compact png is retained when exact gif would be larger`() {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 16) {
            for (x in 0 until 16) image.setRGB(x, y, (x shl 16) or (y shl 8) or (x + y))
        }
        val source = png(image)
        val candidate = requireNotNull(PngImageEncoder(ImageOptimizationPolicy(), LegacyImageCodec(ImageOptimizationPolicy())).encode(image))
        assertEquals("image/gif", candidate.contentType)
        assertTrue(candidate.bytes.size >= source.size)
        assertPassthrough(source)
    }

    @Test
    fun `malformed truncated mismatched and animated png remain unchanged`() {
        val source = png(artwork(120, 80), uncompressed = true)
        assertPassthrough(byteArrayOf(1, 2, 3))
        assertPassthrough(source.copyOf(source.size / 2))
        assertPassthrough(withAnimationControl(source))
        assertPassthrough(source, contentType = "image/jpeg")
        assertPassthrough(source, contentType = "image/gif")
    }

    @Test
    fun `pixel and encoded input limits prevent png work`() {
        val source = png(artwork(120, 80), uncompressed = true)
        assertPassthrough(source, policy = ImageOptimizationPolicy(maxDecodedPixels = 9599))
        assertPassthrough(source, policy = ImageOptimizationPolicy(maxEncodedBytes = source.size - 1))
        output(transform(source, policy = ImageOptimizationPolicy(maxDecodedPixels = 9600, maxEncodedBytes = source.size)), source, "image/gif")
    }

    @Test
    fun `content type matching ignores case and parameters but encoded bodies are skipped`() {
        val source = png(artwork(120, 80), uncompressed = true)
        output(transform(source, contentType = "IMAGE/PNG; charset=binary"), source, "image/gif")
        val representation = representation(source).copy(headers = mapOf("Content-Type" to listOf("image/png"), "Content-Encoding" to listOf("gzip")))
        val result = pipeline(ImageOptimizationPolicy()).transform(context(), Resource(context().upstreamUri, representation))
        assertSame(representation, result.resource.source)
        assertNull(result.resource.transformed)
    }

    private fun transform(
        bytes: ByteArray,
        policy: ImageOptimizationPolicy = ImageOptimizationPolicy(),
        contentType: String = "image/png",
    ): ResourceTransformationState = pipeline(policy).transform(context(), Resource(context().upstreamUri, representation(bytes, contentType)))

    private fun pipeline(policy: ImageOptimizationPolicy) = ResourceTransformationPipeline(listOf(ImageResourceTransformer(policy)))

    private fun assertPassthrough(
        bytes: ByteArray,
        policy: ImageOptimizationPolicy = ImageOptimizationPolicy(),
        contentType: String = "image/png",
    ) {
        val result = transform(bytes, policy, contentType)
        assertNull(result.resource.transformed)
        assertSame(bytes, result.resource.source.body)
        assertContentEquals(bytes, result.resource.representation.body)
        assertEquals(listOf(contentType), result.resource.representation.headers["Content-Type"])
    }

    private fun output(result: ResourceTransformationState, source: ByteArray, type: String): BufferedImage {
        val representation = requireNotNull(result.resource.transformed).representation
        assertTrue(representation.body.size < source.size)
        assertEquals(listOf(type), representation.headers["Content-Type"])
        assertEquals(listOf("\"png-source\""), representation.headers["ETag"])
        assertEquals(listOf("Tue, 29 Sep 2026 12:00:00 GMT"), representation.headers["Last-Modified"])
        for (header in listOf("Content-Length", "Content-MD5", "Digest")) {
            assertTrue(representation.headers.keys.none { it.equals(header, true) })
        }
        assertEquals(listOf("identity"), representation.headers["Content-Encoding"])
        assertSame(source, result.resource.source.body)
        return requireNotNull(ImageIO.read(ByteArrayInputStream(representation.body)))
    }

    private fun representation(bytes: ByteArray, contentType: String = "image/png") = ResourceRepresentation(
        200,
        mapOf(
            "Content-Type" to listOf(contentType),
            "Content-Length" to listOf(bytes.size.toString()),
            "Content-MD5" to listOf("source-md5"),
            "Digest" to listOf("source-digest"),
            "Content-Encoding" to listOf("identity"),
            "ETag" to listOf("\"png-source\""),
            "Last-Modified" to listOf("Tue, 29 Sep 2026 12:00:00 GMT"),
        ),
        bytes,
    )

    private fun context() = ResourceTransformationContext(
        setOf(1), URI("http://legacy.test/image.png"), URI("https://modern.test/image.png"),
        ResourceKind.IMAGE, null, ReferenceRole.SUBRESOURCE, emptyMap(), "GET", "netscape-4.08-v1", { it },
    )

    private fun photo(width: Int, height: Int, type: Int = BufferedImage.TYPE_INT_RGB): BufferedImage {
        val image = BufferedImage(width, height, type)
        val random = Random(37)
        for (y in 0 until height) {
            for (x in 0 until width) image.setRGB(x, y, 0xff000000.toInt() or ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8) or random.nextInt(32))
        }
        return image
    }

    private fun artwork(width: Int, height: Int, transparent: Boolean = false): BufferedImage {
        val image = BufferedImage(width, height, if (transparent) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val colors = intArrayOf(if (transparent) 0x00112233 else 0xff000000.toInt(), 0xff000000.toInt(), 0xffff0000.toInt(), 0xff00ff00.toInt())
        for (y in 0 until height) {
            for (x in 0 until width) image.setRGB(x, y, colors[((x / 8) + (y / 8)) % colors.size])
        }
        return image
    }

    private fun paletteBoundary(opaqueColors: Int): BufferedImage {
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val index = (x + y * image.width) % (opaqueColors + 1)
                image.setRGB(x, y, if (index == opaqueColors) 0 else 0xff000000.toInt() or index)
            }
        }
        return image
    }

    private fun png(image: BufferedImage, uncompressed: Boolean = false): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        return try {
            ByteArrayOutputStream().use { output ->
                MemoryCacheImageOutputStream(output).use { imageOutput ->
                    writer.output = imageOutput
                    val params = writer.defaultWriteParam
                    params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    params.compressionQuality = if (uncompressed) 1f else 0f
                    writer.write(null, IIOImage(image, null, null), params)
                }
                output.toByteArray()
            }
        } finally {
            writer.dispose()
        }
    }

    private fun assertPixelsEqual(expected: BufferedImage, actual: BufferedImage) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                val argb = expected.getRGB(x, y)
                if (argb ushr 24 == 0) assertEquals(0, actual.getRGB(x, y) ushr 24)
                else assertEquals(argb, actual.getRGB(x, y), "Pixel $x,$y")
            }
        }
    }

    private fun hasJpegMarker(bytes: ByteArray, marker: Int): Boolean =
        (0 until bytes.size - 1).any { bytes[it] == 0xff.toByte() && bytes[it + 1] == marker.toByte() }

    private fun withAnimationControl(png: ByteArray): ByteArray {
        val control = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0)
        val type = "acTL".toByteArray()
        val crc = CRC32().apply { update(type); update(control) }.value
        val chunk = byteArrayOf(0, 0, 0, 8) + type + control + ByteArray(4) { (crc ushr (24 - it * 8)).toByte() }
        return png.copyOfRange(0, 33) + chunk + png.copyOfRange(33, png.size)
    }
}
