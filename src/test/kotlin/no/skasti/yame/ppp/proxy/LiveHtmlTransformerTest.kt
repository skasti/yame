package no.skasti.yame.ppp.proxy

import com.sun.net.httpserver.HttpServer
import no.skasti.yame.ppp.proxy.transform.HtmlTransformer
import no.skasti.yame.ppp.proxy.transform.HtmlTransformerSettings
import no.skasti.yame.ppp.http.HttpConnection
import no.skasti.yame.ppp.http.HttpRequest
import no.skasti.yame.ppp.http.HttpResponse
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.tcp.TcpFlowKey
import no.skasti.yame.ppp.tcp.TcpProxyFlow
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveHtmlTransformerTest {
    private val source = "<html><script>run()</script><style>body{color:red}</style><main class=\"news\">News</main></html>"

    @Test
    fun `same handler toggles profiles without stale transformed cache or browser 304 reuse`() {
        val conditional = LinkedBlockingQueue<Boolean>()
        val server = server { exchange ->
            conditional.offer(exchange.requestHeaders.containsKey("If-None-Match") || exchange.requestHeaders.containsKey("If-Modified-Since"))
        }
        run {
            val store = no.skasti.yame.persistence.applicationConfigStore(no.skasti.yame.persistence.YameConfiguration())
            val handler = SystemHttpCompatibilityHandler(config = PppHttpCompatibilityConfig(),
                transformerSettings = { HtmlTransformerSettings.fromConfigStore(store) })
            try {
                val request = request(server).copy(headers = request(server).headers + listOf(
                    "If-None-Match" to "\"source\"", "If-Modified-Since" to "Tue, 29 Sep 2026 12:00:00 GMT",
                ))
                val first = handler.handle(connection(), request)
                assertEquals("<html><div>News</div></html>", first.body.toString(Charsets.UTF_8))
                store.set(no.skasti.yame.ppp.proxy.transform.TransformerSettings.Style to false)
                val second = handler.handle(connection(), request)
                assertEquals("<html><style>body{color:red}</style><div>News</div></html>", second.body.toString(Charsets.UTF_8))
                assertFalse(second.body.toString(Charsets.UTF_8).contains("<script>"))
                store.set(no.skasti.yame.ppp.proxy.transform.TransformerSettings.Style to true)
                val third = handler.handle(connection(), request)
                assertEquals(first.body.toList(), third.body.toList())
                HtmlTransformer.entries.forEach { store.set(no.skasti.yame.ppp.proxy.transform.TransformerSettings.key(it) to false) }
                assertEquals(source, handler.handle(connection(), request).body.toString(Charsets.UTF_8))
                repeat(4) { assertEquals(false, conditional.poll(2, TimeUnit.SECONDS)) }
                for (response in listOf(first, second, third)) {
                    assertEquals(200, response.statusCode)
                    assertEquals("no-store", header(response, "Cache-Control"))
                    assertNull(header(response, "ETag"))
                    assertEquals(response.body.size.toString(), header(response, "Content-Length"))
                }
            } finally { handler.close(); server.stop(0) }
        }
    }

    @Test
    fun `in flight response keeps its settings object and next request gets new setting`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = server {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        run {
            val store = no.skasti.yame.persistence.applicationConfigStore(no.skasti.yame.persistence.YameConfiguration())
            val handler = SystemHttpCompatibilityHandler(config = PppHttpCompatibilityConfig(),
                transformerSettings = { HtmlTransformerSettings.fromConfigStore(store) })
            try {
                val response = CompletableFuture.supplyAsync { handler.handle(connection(), request(server)) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                store.set(no.skasti.yame.ppp.proxy.transform.TransformerSettings.Script to false)
                release.countDown()
                assertFalse(response.get(5, TimeUnit.SECONDS).body.toString(Charsets.UTF_8).contains("<script>"))
                assertTrue(handler.handle(connection(), request(server)).body.toString(Charsets.UTF_8).contains("<script>run()</script>"))
            } finally { release.countDown(); handler.close(); server.stop(0) }
        }
    }

    @Test
    fun `fixed explicit settings stay independent of process store`() {
        val store = no.skasti.yame.persistence.applicationConfigStore(no.skasti.yame.persistence.YameConfiguration())
        val server = server {}
        no.skasti.yame.config.ConfigStore.install(store).use {
            val handler = SystemHttpCompatibilityHandler(config = PppHttpCompatibilityConfig(
                htmlTransformers = HtmlTransformerSettings(false, false, false, false),
            ))
            try {
                assertEquals(source, handler.handle(connection(), request(server)).body.toString(Charsets.UTF_8))
                store.set(no.skasti.yame.ppp.proxy.transform.TransformerSettings.Style to false)
                assertEquals(source, handler.handle(connection(), request(server)).body.toString(Charsets.UTF_8))
            } finally { handler.close(); server.stop(0) }
        }
    }

    private fun server(beforeResponse: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/page") { exchange ->
            exchange.use {
                beforeResponse(it)
                it.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                it.responseHeaders.add("Cache-Control", "max-age=3600")
                it.responseHeaders.add("ETag", "\"source\"")
                val bytes = source.toByteArray()
                it.sendResponseHeaders(200, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        server.start()
        return server
    }

    private fun request(server: HttpServer) = HttpRequest("GET", "/page", "HTTP/1.0",
        listOf("Host" to "127.0.0.1:${server.address.port}"), byteArrayOf())

    private fun connection() = HttpConnection(TcpProxyFlow(TcpFlowKey(
        Ipv4Address.parse("10.0.0.2"), 2451, Ipv4Address.parse("127.0.0.1"), 80,
    ), 1)) { true }

    private fun header(response: HttpResponse, name: String): String? =
        response.headers.firstOrNull { it.first.equals(name, true) }?.second
}
