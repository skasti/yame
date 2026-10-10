package no.skasti.yame.ppp.proxy.transform

import no.skasti.yame.ppp.proxy.ReferenceRole
import no.skasti.yame.ppp.proxy.ResourceKind
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Visual regression examples rendered by the real image transformation pipeline.
 * All fixtures and resulting files are deterministic and generated at test time.
 */
class ImageTransformationVisualReportTest {
    private data class Example(
        val id: String,
        val title: String,
        val description: String,
        val original: BufferedImage,
        val format: String,
        val expectedMime: String,
        val alpha: Boolean = false,
        val black: Boolean = false,
    )

    private data class Result(
        val example: Example,
        val sourceName: String,
        val outputName: String,
        val sourceSize: Int,
        val outputSize: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val outputFormat: String,
        val transformed: Boolean,
    )

    @Test
    fun generatesOriginalAndTransformedImageGallery() {
        val directory = Path.of("build", "reports", "image-transformations")
        Files.createDirectories(directory)
        val examples = listOf(
            Example(
                "transparent-logo", "Transparent logo: PNG → GIF",
                "Binary transparency and opaque black must survive resizing and format conversion.",
                transparentLogo(), "png", "image/gif", alpha = true, black = true,
            ),
            Example(
                "palette-art", "Palette illustration: PNG → GIF",
                "A small exact-color palette must survive without JPEG artifacts.",
                paletteArtwork(), "png", "image/gif",
            ),
            Example(
                "photo-png", "Photographic PNG → JPEG",
                "High-color PNG is resized to the legacy canvas and encoded as baseline JPEG.",
                photo(), "png", "image/jpeg",
            ),
            Example(
                "photo-jpeg", "Photographic JPEG → JPEG",
                "Existing JPEG is downscaled and recompressed through its own transformer.",
                photo(), "jpeg", "image/jpeg",
            ),
            Example(
                "partial-alpha", "Soft transparency: PNG passthrough",
                "Partial alpha cannot be faithfully represented as GIF, so the original is retained.",
                softAlphaLogo(), "png", "image/png", alpha = true,
            ),
        )
        val pipeline = ResourceTransformationPipeline(listOf(JpegResourceTransformer(), PngResourceTransformer()))
        val results = examples.map { example ->
            val sourceBytes = encode(example.original, example.format)
            val inputMime = if (example.format == "png") "image/png" else "image/jpeg"
            val sourceName = example.id + "-original." + extension(example.format)
            val uri = URI("https://modern.test/" + sourceName)
            val context = ResourceTransformationContext(
                navigationIds = setOf(1L),
                legacyUri = URI("http://legacy.test/" + sourceName),
                upstreamUri = uri,
                kind = ResourceKind.IMAGE,
                relation = null,
                role = ReferenceRole.SUBRESOURCE,
                requestHeaders = emptyMap(),
                requestMethod = "GET",
                transformationProfile = "netscape-4.08-v1",
                rewriteText = { it },
            )
            val representation = ResourceRepresentation(
                200, mapOf("Content-Type" to listOf(inputMime)), sourceBytes,
            )
            val state = pipeline.transform(context, Resource(uri, representation))
            val output = state.resource.representation
            val mime = output.headers.entries
                .first { it.key.equals("Content-Type", ignoreCase = true) }.value.single()
            assertEquals(example.expectedMime, mime, example.id)
            val shouldTransform = example.id != "partial-alpha"
            if (shouldTransform) {
                assertNotNull(state.resource.transformed, example.id)
                assertTrue(output.body.size < sourceBytes.size, example.id)
            } else {
                assertNull(state.resource.transformed)
                assertContentEquals(sourceBytes, output.body)
            }
            val image = assertNotNull(ImageIO.read(ByteArrayInputStream(output.body)), example.id)
            if (shouldTransform) assertTrue(image.width <= 600 && image.height <= 400)
            if (example.alpha) assertTrue(anyPixel(image) { it ushr 24 == 0 }, example.id)
            if (example.black) assertTrue(anyPixel(image) { it == Color.BLACK.rgb }, example.id)
            if (example.id == "palette-art") {
                assertEquals(example.original.width, image.width)
                assertEquals(example.original.height, image.height)
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    assertEquals(example.original.getRGB(x, y), image.getRGB(x, y), "at ($x, $y)")
                }
            }
            val outputFormat = when (mime) {
                "image/gif" -> "gif"
                "image/jpeg" -> "jpeg"
                else -> "png"
            }
            val outputName = example.id + "-result." + extension(outputFormat)
            Files.write(directory.resolve(sourceName), sourceBytes)
            Files.write(directory.resolve(outputName), output.body)
            Result(example, sourceName, outputName, sourceBytes.size, output.body.size,
                image.width, image.height, outputFormat, shouldTransform)
        }
        Files.writeString(directory.resolve("index.html"), renderHtml(results))
    }

    private fun transparentLogo() = BufferedImage(900, 450, BufferedImage.TYPE_INT_ARGB).apply {
        val g = createGraphics()
        try {
            g.composite = AlphaComposite.Src
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, width, height)
            g.color = Color.BLACK
            g.fillRoundRect(56, 80, 788, 288, 55, 55)
            g.color = Color(15, 55, 94)
            g.fillRoundRect(66, 90, 768, 268, 44, 44)
            g.color = Color(255, 192, 62)
            g.fillRoundRect(95, 123, 165, 200, 34, 34)
            g.color = Color.BLACK
            g.fillOval(135, 165, 84, 84)
            g.color = Color.WHITE
            g.font = Font("SansSerif", Font.BOLD, 130)
            g.drawString("YAME", 290, 273)
            g.color = Color(255, 192, 62)
            g.fillRect(301, 291, 445, 9)
        } finally {
            g.dispose()
        }
    }

    private fun paletteArtwork() = BufferedImage(480, 320, BufferedImage.TYPE_INT_RGB).apply {
        val colors = intArrayOf(0xff142c46.toInt(), 0xff256a92.toInt(), 0xff52bdc4.toInt(),
            0xfff7e4a5.toInt(), 0xffffb55d.toInt(), 0xffe8695b.toInt())
        val random = Random(91)
        for (y in 0 until height) for (x in 0 until width) {
            setRGB(x, y, colors[random.nextInt(colors.size)])
        }
        val g = createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g.color = Color(20, 44, 70)
            g.fillRoundRect(78, 90, 324, 140, 16, 16)
            g.color = Color(255, 181, 93)
            g.font = Font("Monospaced", Font.BOLD, 64)
            g.drawString("YAME", 138, 178)
        } finally {
            g.dispose()
        }
    }

    private fun photo() = BufferedImage(1280, 800, BufferedImage.TYPE_INT_RGB).apply {
        val random = Random(37)
        for (y in 0 until height) for (x in 0 until width) {
            val noise = random.nextInt(16) - 8
            setRGB(x, y, Color(
                (x * 180 / width + 30 + noise).coerceIn(0, 255),
                (y * 170 / height + 25 + noise).coerceIn(0, 255),
                (95 + (x + y) * 75 / (width + height) + noise).coerceIn(0, 255),
            ).rgb)
        }
        val g = createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(238, 197, 112)
            g.fillOval(width / 2 - 145, height / 2 - 130, 290, 260)
            g.color = Color(24, 64, 69)
            g.fillRoundRect(width / 8, height * 3 / 5, width * 3 / 4, height / 7, 70, 70)
        } finally {
            g.dispose()
        }
    }

    private fun softAlphaLogo() = BufferedImage(520, 280, BufferedImage.TYPE_INT_ARGB).apply {
        val g = createGraphics()
        try {
            g.composite = AlphaComposite.Src
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, width, height)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0, 0, 0, 75)
            g.fillRoundRect(49, 52, 424, 188, 36, 36)
            g.color = Color(59, 148, 184, 220)
            g.fillRoundRect(42, 43, 424, 188, 36, 36)
            g.color = Color.WHITE
            g.font = Font("SansSerif", Font.BOLD, 82)
            g.drawString("YAME", 119, 167)
        } finally {
            g.dispose()
        }
    }

    private fun encode(image: BufferedImage, format: String): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName(format).next()
        return try {
            ByteArrayOutputStream().use { out ->
                MemoryCacheImageOutputStream(out).use { stream ->
                    writer.output = stream
                    val params = writer.defaultWriteParam
                    if (params.canWriteCompressed()) {
                        params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                        params.compressionQuality = if (format == "jpeg") 0.95f else 0f
                    }
                    writer.write(null, IIOImage(image, null, null), params)
                }
                out.toByteArray()
            }
        } finally {
            writer.dispose()
        }
    }

    private fun anyPixel(image: BufferedImage, predicate: (Int) -> Boolean): Boolean =
        (0 until image.height).any { y ->
            (0 until image.width).any { x -> predicate(image.getRGB(x, y)) }
        }

    private fun extension(format: String) = if (format == "jpeg") "jpg" else format

    private fun renderHtml(results: List<Result>): String = buildString {
        appendLine("""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>YAME image transformation visual tests</title>
<style>
:root{font-family:system-ui,sans-serif;color:#1b2a3c;background:#eef2f6;color-scheme:light}
body{max-width:1220px;margin:auto;padding:28px 18px 65px}
h1{margin:0 0 8px}.intro{color:#586a81;margin:0 0 28px}
article{background:#fff;border:1px solid #d7e0e9;border-radius:14px;padding:20px;margin-bottom:24px}
article h2{margin:0 0 8px}.desc{color:#516177;margin:0 0 16px}
.meta{display:flex;gap:10px;flex-wrap:wrap;margin:0 0 18px}
.meta span{background:#eaf0f5;border-radius:6px;padding:6px 9px;font-size:.85rem}
.compare{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}
.samples{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}
.panel{border:1px solid #ccd7e1;border-radius:8px;min-height:155px;padding:8px;
display:flex;justify-content:center;align-items:center;overflow:hidden}
.panel img{display:block;max-width:100%;max-height:250px;object-fit:contain}
.checker{background-color:white;background-image:linear-gradient(45deg,#cbd5df 25%,transparent 25%),linear-gradient(-45deg,#cbd5df 25%,transparent 25%),linear-gradient(45deg,transparent 75%,#cbd5df 75%),linear-gradient(-45deg,transparent 75%,#cbd5df 75%);background-size:24px 24px;background-position:0 0,0 12px,12px -12px,-12px 0}
.dark{background:#152235}.caption{font-size:.8rem;color:#56677b}
@media(max-width:740px){.compare{grid-template-columns:1fr}}
</style></head><body><h1>YAME image transformations</h1>
<p class="intro">Deterministic examples run through the production resource transformers. Images are shown against checkerboard and dark backgrounds to make transparency visible. All assets are local; open this file from the CI artifact.</p>""")
        for (result in results) {
            val e = result.example
            val saving = String.format(Locale.ROOT, "%.1f", 100.0 * (result.sourceSize - result.outputSize) / result.sourceSize)
            appendLine("<article><h2>" + e.title + "</h2><p class=\"desc\">" + e.description + "</p>")
            appendLine("<div class=\"meta\"><span>" + (if (result.transformed) "Transformed" else "Passthrough") +
                "</span><span>" + e.format.uppercase(Locale.ROOT) + " " + e.original.width + "×" + e.original.height +
                ": " + kib(result.sourceSize) + "</span><span>" + result.outputFormat.uppercase(Locale.ROOT) +
                " " + result.outputWidth + "×" + result.outputHeight + ": " + kib(result.outputSize) +
                "</span><span>" + saving + "% saved</span></div>")
            appendLine("<div class=\"compare\">")
            for ((label, name) in listOf("Original" to result.sourceName, "Result" to result.outputName)) {
                appendLine("<section><h3>" + label + "</h3><div class=\"samples\">")
                for ((background, style) in listOf("Checkerboard" to "checker", "Dark background" to "dark")) {
                    appendLine("<div><div class=\"panel " + style + "\"><img src=\"" + name +
                        "\" alt=\"" + label + " on " + background + "\"></div><p class=\"caption\">" +
                        background + "</p></div>")
                }
                appendLine("</div></section>")
            }
            appendLine("</div></article>")
        }
        appendLine("<p class=\"intro\">These generated fixtures complement pixel/header assertions; they do not replace tests with actual legacy browsers or a real-world photo corpus.</p></body></html>")
    }

    private fun kib(bytes: Int) = String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
}
