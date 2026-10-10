package no.skasti.yame.ppp.proxy

import com.sun.net.httpserver.HttpServer
import no.skasti.yame.ppp.http.HttpConnection
import no.skasti.yame.ppp.http.HttpRequest
import no.skasti.yame.ppp.http.HttpResponse
import no.skasti.yame.ppp.http.HttpResponseEncoder
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.tcp.TcpFlowKey
import no.skasti.yame.ppp.tcp.TcpProxyFlow
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PngCompatibilityIntegrationTest {
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
        val handler = SystemHttpCompatibilityHandler()
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
}
