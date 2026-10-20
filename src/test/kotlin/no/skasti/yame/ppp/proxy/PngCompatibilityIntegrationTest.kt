package no.skasti.yame.ppp.proxy

import com.sun.net.httpserver.HttpServer
import no.skasti.yame.ppp.http.HttpConnection
import no.skasti.yame.ppp.http.HttpRequest
import no.skasti.yame.ppp.http.HttpResponse
import no.skasti.yame.ppp.http.HttpResponseEncoder
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.proxy.transform.PngCompatibilityMode
import no.skasti.yame.ppp.proxy.transform.PngImageSettings
import no.skasti.yame.ppp.proxy.transform.ResourceTransformationSettings
import no.skasti.yame.ppp.tcp.TcpFlowKey
import no.skasti.yame.ppp.tcp.TcpProxyFlow
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PngCompatibilityIntegrationTest {
    @Test
    fun `live png mode changes produce distinct cached representations with correct http framing`() {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 16) {
            for (x in 0 until 16) image.setRGB(x, y, (x shl 16) or (y shl 8) or (x + y))
        }
        val png = encodePng(image)
        val upstreamRequests = LinkedBlockingQueue<Unit>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tiny.png") { exchange ->
            exchange.use {
                upstreamRequests.offer(Unit)
                it.responseHeaders.add("ETag", "\"tiny-source\"")
                it.responseHeaders.add("Content-MD5", "source-digest")
                it.responseHeaders.add("Cache-Control", "no-cache")
                it.responseHeaders.add("Content-Type", "image/png")
                it.sendResponseHeaders(200, png.size.toLong())
                it.responseBody.write(png)
            }
        }
        server.start()
        val store = no.skasti.yame.persistence.applicationConfigStore(no.skasti.yame.persistence.YameConfiguration())
        val handler = SystemHttpCompatibilityHandler(
            config = PppHttpCompatibilityConfig(),
            transformerSettings = { ResourceTransformationSettings.fromSnapshot(store.snapshot()) },
        )
        val flow = TcpProxyFlow(TcpFlowKey(
            Ipv4Address.parse("10.0.0.2"), 2402, Ipv4Address.parse("127.0.0.1"), 80,
        ), 1)
        val request = HttpRequest(
            "GET", "/tiny.png", "HTTP/1.0",
            listOf("Host" to "127.0.0.1:${server.address.port}"), byteArrayOf(),
        )
        try {
            val optimized = handler.handle(HttpConnection(flow) { true }, request)
            assertEquals("image/png", header(optimized, "Content-Type"))
            assertContentEquals(png, optimized.body)
            assertEquals("source-digest", header(optimized, "Content-MD5"))
            assertEquals(Unit, upstreamRequests.poll(2, TimeUnit.SECONDS))

            store.set(PngImageSettings.CompatibilityMode to PngCompatibilityMode.REQUIRE_LEGACY_FORMAT)
            val forced = handler.handle(HttpConnection(flow.copy(generation = 2)) { true }, request)
            assertEquals("image/gif", header(forced, "Content-Type"))
            assertTrue(forced.body.size > png.size)
            assertEquals(forced.body.size.toString(), header(forced, "Content-Length"))
            assertEquals("\"tiny-source\"", header(forced, "ETag"))
            assertNull(header(forced, "Content-MD5"))
            assertEquals("close", header(forced, "Connection"))
            assertNull(header(forced, "Transfer-Encoding"))
            assertTrue(HttpResponseEncoder.encodeHead(forced).decodeToString().startsWith("HTTP/1.0 200 OK\r\n"))
            assertEquals(16, ImageIO.read(ByteArrayInputStream(forced.body)).width)
            assertEquals(Unit, upstreamRequests.poll(2, TimeUnit.SECONDS))

            store.set(PngImageSettings.CompatibilityMode to PngCompatibilityMode.OPTIMIZE)
            val optimizedAgain = handler.handle(HttpConnection(flow.copy(generation = 3)) { true }, request)
            assertEquals("image/png", header(optimizedAgain, "Content-Type"))
            assertContentEquals(png, optimizedAgain.body)
            assertNull(upstreamRequests.poll(150, TimeUnit.MILLISECONDS))
        } finally {
            handler.close()
            server.stop(0)
        }
    }

    @Test
    fun `proxy emits png as gif and reuses an identical source across PPP generations`() {
        val image = BufferedImage(1200, 800, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                image.setRGB(x, y, if ((x / 8 + y / 8) % 2 == 0) 0 else 0xffff0000.toInt())
            }
        }
        val png = ByteArrayOutputStream().use { output ->
            assertTrue(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
        val conditionalHeaders = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/image.png") { exchange ->
            exchange.use {
                val conditional = exchange.requestHeaders.getFirst("If-None-Match")
                conditionalHeaders.offer(conditional ?: "none")
                exchange.responseHeaders.add("ETag", "\"png-original\"")
                exchange.responseHeaders.add("Cache-Control", "no-cache")
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.responseHeaders.add("Content-MD5", "original-digest")
                exchange.responseHeaders.add("Date", "Tue, 29 Sep 2026 12:00:00 GMT")
                exchange.sendResponseHeaders(200, png.size.toLong())
                exchange.responseBody.write(png)
            }
        }
        server.start()
        val handler = SystemHttpCompatibilityHandler(config = PppHttpCompatibilityConfig())
        val flow = TcpProxyFlow(TcpFlowKey(Ipv4Address.parse("10.0.0.2"), 2401, Ipv4Address.parse("127.0.0.1"), 80), 1)
        val request = HttpRequest("GET", "/image.png", "HTTP/1.0", listOf("Host" to "127.0.0.1:${server.address.port}"), byteArrayOf())
        try {
            val first = handler.handle(HttpConnection(flow) { true }, request)
            assertEquals(200, first.statusCode)
            assertEquals("none", conditionalHeaders.poll(2, TimeUnit.SECONDS))
            assertEquals("image/gif", header(first, "Content-Type"))
            assertEquals("\"png-original\"", header(first, "ETag"))
            assertNull(header(first, "Content-MD5"))
            assertTrue(first.body.size < png.size)
            assertEquals(first.body.size.toString(), header(first, "Content-Length"))
            assertEquals("close", header(first, "Connection"))
            assertNull(header(first, "Transfer-Encoding"))
            assertTrue(HttpResponseEncoder.encodeHead(first).decodeToString().startsWith("HTTP/1.0 200 OK\r\n"))
            val output = ImageIO.read(ByteArrayInputStream(first.body))
            assertEquals(600, output.width)
            assertEquals(400, output.height)
            assertEquals(0, output.getRGB(0, 0) ushr 24)
            assertEquals(255, output.getRGB(4, 0) ushr 24)

            val expected = first.body.copyOf()
            first.body[0] = 0
            // Fresh connection/PPP generation, same process-owned representation cache.
            val second = handler.handle(HttpConnection(flow.copy(generation = 2)) { true }, request)
            assertEquals("none", conditionalHeaders.poll(2, TimeUnit.SECONDS))
            assertEquals(200, second.statusCode)
            assertEquals("image/gif", header(second, "Content-Type"))
            assertEquals(first.body.size.toString(), header(second, "Content-Length"))
            assertContentEquals(expected, second.body)
        } finally {
            handler.close()
            server.stop(0)
        }
    }

    private fun header(response: HttpResponse, name: String): String? =
        response.headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    private fun encodePng(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        return try {
            ByteArrayOutputStream().use { output ->
                MemoryCacheImageOutputStream(output).use { imageOutput ->
                    writer.output = imageOutput
                    val params = writer.defaultWriteParam
                    params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    params.compressionQuality = 0f
                    writer.write(null, IIOImage(image, null, null), params)
                }
                output.toByteArray()
            }
        } finally {
            writer.dispose()
        }
    }
}
