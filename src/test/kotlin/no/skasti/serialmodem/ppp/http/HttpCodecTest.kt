package no.skasti.serialmodem.ppp.http

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpRequestDecoderTest {
    @Test
    fun `decodes request across multiple TCP payloads`() {
        val decoder = HttpRequestDecoder(maxRequestBytes = 1024)

        assertIs<HttpRequestDecodeResult.NeedMoreData>(
            decoder.accept("GET /path HTTP/1.0\r\nHost: example.test\r\n".toByteArray(StandardCharsets.US_ASCII)),
        )
        val result =
            decoder.accept("\r\n".toByteArray(StandardCharsets.US_ASCII))

        val complete = assertIs<HttpRequestDecodeResult.Complete>(result)
        assertEquals("GET", complete.request.method)
        assertEquals("/path", complete.request.target)
        assertEquals("HTTP/1.0", complete.request.version)
        assertEquals("example.test", complete.request.headers.single().second)
        assertTrue(complete.request.body.isEmpty())
    }

    @Test
    fun `requests 100 continue only once while waiting for body`() {
        val decoder = HttpRequestDecoder(maxRequestBytes = 1024)
        val headers =
            "POST /submit HTTP/1.1\r\n" +
                "Host: example.test\r\n" +
                "Content-Length: 4\r\n" +
                "Expect: 100-continue\r\n\r\n"

        assertIs<HttpRequestDecodeResult.ContinueRequired>(
            decoder.accept(headers.toByteArray(StandardCharsets.US_ASCII)),
        )
        assertIs<HttpRequestDecodeResult.NeedMoreData>(
            decoder.accept("ab".toByteArray(StandardCharsets.US_ASCII)),
        )
        val complete =
            assertIs<HttpRequestDecodeResult.Complete>(
                decoder.accept("cd".toByteArray(StandardCharsets.US_ASCII)),
            )
        assertEquals("abcd", complete.request.body.toString(StandardCharsets.US_ASCII))
    }

    @Test
    fun `rejects pipelined bytes after complete request`() {
        val decoder = HttpRequestDecoder(maxRequestBytes = 1024)
        val result =
            decoder.accept(
                (
                    "GET /one HTTP/1.0\r\nHost: example.test\r\n\r\n" +
                        "GET /two HTTP/1.0\r\nHost: example.test\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII),
            )

        val rejected = assertIs<HttpRequestDecodeResult.Rejected>(result)
        assertEquals(400, rejected.status)
        assertTrue(rejected.message.contains("pipelining"))
    }

    @Test
    fun `rejects unterminated headers when request limit is exactly full`() {
        val request = "GET / HTTP/1.0\r\nHost: example.test\r\nX-Fill: "
        val maxBytes = request.toByteArray(StandardCharsets.US_ASCII).size + 8
        val decoder = HttpRequestDecoder(maxRequestBytes = maxBytes)
        val payload =
            (request + "12345678")
                .toByteArray(StandardCharsets.US_ASCII)

        assertEquals(maxBytes, payload.size)
        val rejected = assertIs<HttpRequestDecodeResult.Rejected>(decoder.accept(payload))
        assertEquals(413, rejected.status)
        assertEquals(0, decoder.availableCapacity)
    }

    @Test
    fun `rejects unsupported expectation`() {
        val decoder = HttpRequestDecoder(maxRequestBytes = 1024)
        val result =
            decoder.accept(
                (
                    "POST /submit HTTP/1.1\r\n" +
                        "Host: example.test\r\n" +
                        "Content-Length: 4\r\n" +
                        "Expect: magic\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII),
            )

        assertEquals(417, assertIs<HttpRequestDecodeResult.Rejected>(result).status)
    }
}

class HttpResponseEncoderTest {
    @Test
    fun `encodes response head separately from body chunks`() {
        val response =
            HttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers =
                    listOf(
                        "Content-Type" to "text/plain",
                        "Content-Length" to "6",
                        "Connection" to "close",
                    ),
                body = "abcdef".toByteArray(StandardCharsets.US_ASCII),
            )

        assertEquals(
            "HTTP/1.0 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: 6\r\n" +
                "Connection: close\r\n\r\n",
            HttpResponseEncoder.encodeHead(response).toString(StandardCharsets.ISO_8859_1),
        )
        assertEquals(
            listOf("abcd", "ef"),
            HttpResponseEncoder.bodyChunks(response, maxChunkBytes = 4)
                .map { it.toString(StandardCharsets.US_ASCII) }
                .toList(),
        )
    }
}
