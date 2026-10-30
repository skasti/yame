package no.skasti.yame.ppp.proxy

import com.sun.net.httpserver.HttpServer
import no.skasti.yame.observer.*
import no.skasti.yame.config.ConfigStore
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.session.PppSessionIdentity
import no.skasti.yame.ppp.session.TransferIdentity
import no.skasti.yame.ppp.tcp.*
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Runs with an installed broker in its own JVM, like the other facade integration tests. */
class HttpTransferMetadataTest {
    private val configInstallation = ConfigStore.install(ConfigStore())
    private val broker = EventBroker()
    private val installation = YameEvents.install(broker)
    private val observed = CopyOnWriteArrayList<TransferResourceChanged>()
    private val subscription = YameEvents.subscribe("transfer-metadata") { if (it is TransferResourceChanged) observed += it }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val session = PppSessionIdentity.create()
    private val diagnostics = CopyOnWriteArrayList<TransferResourceChanged>()
    private val proxy = SystemHttpCompatibilityProxy(
        config = PppHttpCompatibilityConfig(enabled = true, requestTimeoutMillis = 5000),
        diagnosticSink = { if (it is TransferResourceChanged) diagnostics += it },
    )
    private val host get() = "127.0.0.1:${server.address.port}"

    @AfterTest fun close() {
        proxy.close()
        assertTrue(proxy.awaitQuiescence(Duration.ofSeconds(5)))
        server.stop(0)
        broker.close()
        assertEquals(BrokerWaitResult.COMPLETED, broker.awaitClosed(Duration.ofSeconds(5)))
        subscription.close()
        installation.close()
        configInstallation.close()
    }

    private fun flow(generation: Long = 0) = TcpProxyFlow(
        TcpFlowKey(Ipv4Address.parse("10.0.0.2"), 2345, Ipv4Address.parse("127.0.0.1"), 80), generation,
        transferIdentity = TransferIdentity.create(session, generation),
    )

    private fun request(flow: TcpProxyFlow, path: String): LinkedBlockingQueue<TcpProxyEvent> {
        val events = LinkedBlockingQueue<TcpProxyEvent>()
        proxy.connect(flow, events::offer)
        assertIs<TcpProxyEvent.Connected>(events.poll(5, TimeUnit.SECONDS))
        proxy.send(flow, "GET $path HTTP/1.0\r\nHost: $host\r\n\r\n".toByteArray()).getOrThrow()
        return events
    }

    private fun finish(events: LinkedBlockingQueue<TcpProxyEvent>): String {
        val response = StringBuilder()
        while (true) when (val event = events.poll(5, TimeUnit.SECONDS)) {
            is TcpProxyEvent.Payload -> response.append(event.bytes.toString(Charsets.ISO_8859_1))
            is TcpProxyEvent.EndOfStream -> return response.toString()
            is TcpProxyEvent.WriteCompleted -> Unit
            else -> fail("Unexpected transport event: $event")
        }
    }

    private fun observations(): List<TransferResourceChanged> {
        assertEquals(BrokerWaitResult.COMPLETED, broker.drain().await(Duration.ofSeconds(5)))
        return observed.toList()
    }

    @Test fun `header metadata reaches the broker for each reused endpoint before downstream delivery`() {
        server.createContext("/download") { exchange ->
            exchange.responseHeaders.add("Content-Disposition", "attachment; filename=WINZIP95.EXE; filename*=UTF-8''bl%C3%A5b%C3%A6r.exe")
            val bytes = ByteArray(10000) { 42 }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val flows = listOf(flow(), flow(), flow(1))
        for (flow in flows) {
            val response = finish(request(flow, "/download?token=not-a-filename"))
            assertTrue(response.startsWith("HTTP/1.0 200"))
            proxy.closeFlow(flow)
        }
        val events = observations()
        assertEquals(6, events.size)
        flows.forEach { flow ->
            val own = events.filter { it.transferIdentity == flow.transferIdentity }
            assertEquals(listOf(TransferResourcePhase.REQUEST, TransferResourcePhase.RESPONSE), own.map { it.phase })
            assertEquals(listOf("download", "blåbær.exe"), own.map { it.displayFilename })
            assertTrue(own.all { it.requestNumber == 1L && it.session == session })
            assertEquals(URI("http://$host/download?token=not-a-filename"), own.last().requestUri)
            assertEquals(own.last().transferIdentity, own.last()["transferIdentity"])
        }
        assertEquals(events.toSet(), diagnostics.toSet())
    }

    @Test fun `visible redirects stay on their own transfer and internal redirects describe final subresource`() {
        server.createContext("/page") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html")
            val body = "<html><body><img src=\"http://$host/start\"></body></html>".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Location", "/files/final.gif")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/files/final.gif") { exchange ->
            exchange.responseHeaders.add("Content-Type", "image/gif")
            exchange.sendResponseHeaders(200, 3)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
        }
        server.start()
        val navigation = flow()
        assertTrue(finish(request(navigation, "/start")).startsWith("HTTP/1.0 302"))
        proxy.closeFlow(navigation)
        val page = flow()
        finish(request(page, "/page"))
        proxy.closeFlow(page)
        val image = flow()
        assertTrue(finish(request(image, "/start")).startsWith("HTTP/1.0 200"))
        val events = observations()
        assertEquals("start", events.last { it.transferIdentity == navigation.transferIdentity }.displayFilename)
        val final = events.last { it.transferIdentity == image.transferIdentity }
        assertEquals("final.gif", final.displayFilename)
        assertEquals(URI("http://$host/start"), final.requestUri)
        assertEquals(URI("http://$host/files/final.gif"), final.effectiveUri)
    }

    @Test fun `shared upstream work still emits response metadata for both downstream transfers`() {
        val upstreamEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.createContext("/shared.exe") { exchange ->
            upstreamEntered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            exchange.responseHeaders.add("Content-Disposition", "attachment; filename=shared.exe")
            exchange.sendResponseHeaders(200, 3)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
        }
        server.start()
        val first = flow()
        val second = flow()
        val secondObserved = CountDownLatch(1)
        val waiter = YameEvents.subscribe("second-request") {
            if (it is TransferResourceChanged && it.transferIdentity == second.transferIdentity) secondObserved.countDown()
        }
        try {
            val one = request(first, "/shared.exe")
            assertTrue(upstreamEntered.await(5, TimeUnit.SECONDS))
            val two = request(second, "/shared.exe")
            assertTrue(secondObserved.await(5, TimeUnit.SECONDS))
            release.countDown()
            finish(one)
            finish(two)
            val responses = observations().filter { it.phase == TransferResourcePhase.RESPONSE }
            assertEquals(setOf(first.transferIdentity, second.transferIdentity), responses.map { it.transferIdentity }.toSet())
            assertTrue(responses.all { it.displayFilename == "shared.exe" })
        } finally { release.countDown(); waiter.close() }
    }
}
