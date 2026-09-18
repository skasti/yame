package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.ppp.proxy.ReferenceRole
import no.skasti.yame.ppp.proxy.ResourceKind
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ImageResourceTransformerTest {
    @Test
    fun `large landscape jpeg is resized and recompressed`() {
        val sourceBytes = jpegFixture(1600, 1000, quality = 0.95f)
        val source = representation(sourceBytes)
        val resource = Resource(URI("https://modern.test/large.jpg"), source)

        val result = pipeline().transform(context(), resource)

        assertSame(source, result.resource.source)
        val transformed = requireNotNull(result.resource.transformed)
        val output = ImageIO.read(ByteArrayInputStream(transformed.representation.body))
        assertTrue(output.width <= 640)
        assertTrue(output.height <= 480)
        assertEquals(600, output.width)
        assertEquals(375, output.height)
        assertTrue(transformed.representation.body.size < sourceBytes.size)
        assertEquals(listOf("image/jpeg"), transformed.representation.headers["Content-Type"])
        assertEquals(listOf("\"fixture\""), transformed.representation.headers["ETag"])
        assertTrue(transformed.representation.headers.keys.none { it.equals("Content-Length", true) })
        assertTrue(transformed.representation.headers.keys.none { it.equals("Content-MD5", true) })
    }

    @Test
    fun `ten megabyte jpeg is reduced to dialup friendly size`() {
        val sourceBytes = loadOrGenerateLargeJpegFixture()
        assertTrue(
            sourceBytes.size > 10 * 1024 * 1024,
            "Fixture must exceed 10 MiB to exercise a genuinely large modern JPEG; was ${sourceBytes.size} bytes",
        )

        val result =
            pipeline().transform(
                context(),
                Resource(URI("https://modern.test/huge.jpg"), representation(sourceBytes)),
            )

        val transformed = requireNotNull(result.resource.transformed)
        val outputBytes = transformed.representation.body
        val output = ImageIO.read(ByteArrayInputStream(outputBytes))
        assertTrue(output.width <= 600)
        assertTrue(output.height <= 400)
        assertTrue(
            outputBytes.size < 200 * 1024,
            "Optimized JPEG should stay below 200 KiB for a practical dialup transfer; was ${outputBytes.size} bytes",
        )
    }

    @Test
    fun `large portrait jpeg is constrained by legacy canvas`() {
        val sourceBytes = jpegFixture(800, 1600, quality = 0.95f)

        val result = pipeline().transform(
            context(),
            Resource(URI("https://modern.test/portrait.jpg"), representation(sourceBytes)),
        )

        val output = ImageIO.read(ByteArrayInputStream(requireNotNull(result.resource.transformed).representation.body))
        assertEquals(200, output.width)
        assertEquals(400, output.height)
    }

    @Test
    fun `small jpeg is never upscaled`() {
        val sourceBytes = jpegFixture(320, 200, quality = 1.0f)

        val result = pipeline().transform(
            context(),
            Resource(URI("https://modern.test/small.jpg"), representation(sourceBytes)),
        )

        val transformed = result.resource.transformed
        if (transformed != null) {
            val output = ImageIO.read(ByteArrayInputStream(transformed.representation.body))
            assertEquals(320, output.width)
            assertEquals(200, output.height)
        }
    }

    @Test
    fun `jpeg is passed through when recompression is not smaller`() {
        val sourceBytes = jpegFixture(64, 64, quality = 0.01f)
        val transformer =
            ImageResourceTransformer(
                ImageOptimizationPolicy(jpegQuality = 1.0f),
            )

        val result =
            ResourceTransformationPipeline(listOf(transformer))
                .transform(
                    context(),
                    Resource(URI("https://modern.test/already-small.jpg"), representation(sourceBytes)),
                )

        assertNull(result.resource.transformed)
    }

    @Test
    fun `malformed jpeg is passed through unchanged`() {
        val source = representation(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3))

        val result = pipeline().transform(
            context(),
            Resource(URI("https://modern.test/broken.jpg"), source),
        )

        assertSame(source, result.resource.source)
        assertNull(result.resource.transformed)
    }

    @Test
    fun `jpeg beyond decoded pixel limit is passed through without decoding`() {
        val sourceBytes = jpegFixture(1000, 1000, quality = 0.9f)
        val transformer =
            ImageResourceTransformer(
                ImageOptimizationPolicy(maxDecodedPixels = 500_000),
            )

        val result =
            ResourceTransformationPipeline(listOf(transformer))
                .transform(
                    context(),
                    Resource(URI("https://modern.test/too-large.jpg"), representation(sourceBytes)),
                )

        assertNull(result.resource.transformed)
    }

    @Test
    fun `non jpeg content is ignored`() {
        val source = representation(byteArrayOf(1, 2, 3), contentType = "image/png")

        val result = pipeline().transform(
            context(),
            Resource(URI("https://modern.test/image.png"), source),
        )

        assertNull(result.resource.transformed)
    }

    private fun pipeline() = ResourceTransformationPipeline(listOf(ImageResourceTransformer()))

    private fun context() =
        ResourceTransformationContext(
            navigationIds = setOf(1L),
            legacyUri = URI("http://legacy.test/image.jpg"),
            upstreamUri = URI("https://modern.test/image.jpg"),
            kind = ResourceKind.IMAGE,
            relation = null,
            role = ReferenceRole.SUBRESOURCE,
            requestHeaders = emptyMap(),
            requestMethod = "GET",
            transformationProfile = "netscape-4.08-v1",
            rewriteText = { it },
        )

    private fun representation(
        body: ByteArray,
        contentType: String = "image/jpeg",
    ) =
        ResourceRepresentation(
            statusCode = 200,
            headers =
                mapOf(
                    "Content-Type" to listOf(contentType),
                    "Content-Length" to listOf(body.size.toString()),
                    "Content-MD5" to listOf("source-digest"),
                    "ETag" to listOf("\"fixture\""),
                ),
            body = body,
        )

    private fun loadOrGenerateLargeJpegFixture(): ByteArray =
        ImageResourceTransformerTest::class.java
            .getResourceAsStream(LARGE_JPEG_FIXTURE)
            ?.use { it.readBytes() }
            ?: highEntropyJpegFixture(4000, 3000, quality = 1.0f)

    private fun jpegFixture(
        width: Int,
        height: Int,
        quality: Float,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val random = Random(12345)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val gradient = ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8)
                val noise = random.nextInt(0, 32)
                image.setRGB(x, y, gradient or noise)
            }
        }
        return encodeJpeg(image, quality)
    }

    private fun highEntropyJpegFixture(
        width: Int,
        height: Int,
        quality: Float,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val random = Random(67890)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, random.nextInt() and 0x00ffffff)
            }
        }
        return encodeJpeg(image, quality)
    }

    private fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        return try {
            ByteArrayOutputStream().use { output ->
                MemoryCacheImageOutputStream(output).use { imageOutput ->
                    writer.output = imageOutput
                    val params = writer.defaultWriteParam
                    params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    params.compressionQuality = quality
                    params.progressiveMode = ImageWriteParam.MODE_DISABLED
                    writer.write(null, IIOImage(image, null, null), params)
                }
                output.toByteArray()
            }
        } finally {
            writer.dispose()
        }
    }

    private companion object {
        const val LARGE_JPEG_FIXTURE = "/images/large-photo.jpg"
    }
}
