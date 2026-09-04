package no.skasti.serialmodem.ppp.http

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

internal sealed interface HttpRequestDecodeResult {
    data object NeedMoreData : HttpRequestDecodeResult
    data object ContinueRequired : HttpRequestDecodeResult
    data class Complete(val request: HttpRequest) : HttpRequestDecodeResult
    data class Rejected(val status: Int, val reason: String, val message: String) : HttpRequestDecodeResult
}

internal class HttpRequestDecoder(
    private val maxRequestBytes: Int,
) {
    init {
        require(maxRequestBytes > 0) { "maxRequestBytes must be positive" }
    }

    private val buffer = ByteArrayOutputStream()
    private var continueEmitted = false
    private var complete = false
    private var parsedRequest: ParsedRequestMetadata? = null

    val bufferedBytes: Int
        get() = buffer.size()

    val availableCapacity: Int
        get() = if (complete) 0 else (maxRequestBytes - buffer.size()).coerceAtLeast(0)

    fun accept(payload: ByteArray): HttpRequestDecodeResult {
        if (complete) {
            return HttpRequestDecodeResult.Rejected(
                400,
                "Bad Request",
                "HTTP pipelining is not supported by compatibility mode",
            )
        }
        if (payload.isEmpty()) return inspect()

        if (buffer.size() + payload.size > maxRequestBytes) {
            complete = true
            return HttpRequestDecodeResult.Rejected(
                413,
                "Content Too Large",
                "HTTP request exceeds compatibility limit",
            )
        }
        buffer.write(payload)
        return inspect()
    }

    fun endOfInput(): HttpRequestDecodeResult {
        if (complete) return HttpRequestDecodeResult.NeedMoreData
        if (buffer.size() == 0) return HttpRequestDecodeResult.NeedMoreData
        complete = true
        return HttpRequestDecodeResult.Rejected(
            400,
            "Bad Request",
            "Incomplete HTTP request",
        )
    }

    private fun inspect(): HttpRequestDecodeResult {
        val metadata =
            parsedRequest
                ?: run {
                    val bytes = buffer.toByteArray()
                    val headerEnd = findHeaderEnd(bytes)
                    if (headerEnd < 0) return HttpRequestDecodeResult.NeedMoreData

                    val parsedHeaders =
                        parseHeaders(bytes, headerEnd)
                            ?: return reject(400, "Bad Request", "Malformed HTTP request headers")
                    val expectation = expectationDisposition(parsedHeaders.headers)
                    if (expectation == ExpectationDisposition.UNSUPPORTED) {
                        return reject(417, "Expectation Failed", "Unsupported HTTP Expect header")
                    }

                    val contentLengthResult = contentLength(parsedHeaders.headers)
                    if (contentLengthResult is ContentLengthResult.Invalid) {
                        return reject(400, "Bad Request", contentLengthResult.message)
                    }
                    val contentLength = (contentLengthResult as ContentLengthResult.Valid).bytes
                    val totalLength = headerEnd + HEADER_DELIMITER.size + contentLength
                    if (totalLength > maxRequestBytes) {
                        return reject(413, "Content Too Large", "HTTP request body exceeds compatibility limit")
                    }
                    ParsedRequestMetadata(
                        headerEnd = headerEnd,
                        parsedHeaders = parsedHeaders,
                        contentLength = contentLength,
                        totalLength = totalLength,
                        expectation = expectation,
                    ).also { parsedRequest = it }
                }

        val buffered = buffer.size()
        if (buffered > metadata.totalLength) {
            return reject(400, "Bad Request", "HTTP pipelining is not supported by compatibility mode")
        }
        if (buffered < metadata.totalLength) {
            if (metadata.expectation == ExpectationDisposition.CONTINUE && !continueEmitted) {
                continueEmitted = true
                return HttpRequestDecodeResult.ContinueRequired
            }
            return HttpRequestDecodeResult.NeedMoreData
        }

        val bytes = buffer.toByteArray()
        val request =
            parseCompleteRequest(
                bytes,
                metadata.headerEnd,
                metadata.parsedHeaders,
                metadata.contentLength,
            ) ?: return reject(400, "Bad Request", "Malformed HTTP request")
        complete = true
        return HttpRequestDecodeResult.Complete(request)
    }

    private fun reject(status: Int, reason: String, message: String): HttpRequestDecodeResult {
        complete = true
        return HttpRequestDecodeResult.Rejected(status, reason, message)
    }

    private fun parseCompleteRequest(
        bytes: ByteArray,
        headerEnd: Int,
        parsed: ParsedHeaders,
        contentLength: Int,
    ): HttpRequest? {
        val requestLine = parsed.requestLine.split(' ', limit = 3)
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/")) return null
        val method = requestLine[0].uppercase(Locale.ROOT)
        if (!METHOD_PATTERN.matches(method) || method == "CONNECT") return null
        val bodyStart = headerEnd + HEADER_DELIMITER.size
        if (bytes.size - bodyStart != contentLength) return null
        return HttpRequest(
            method = method,
            target = requestLine[1],
            version = requestLine[2],
            headers = parsed.headers,
            body = bytes.copyOfRange(bodyStart, bytes.size),
        )
    }

    private fun parseHeaders(bytes: ByteArray, headerEnd: Int): ParsedHeaders? {
        val lines = String(bytes, 0, headerEnd, StandardCharsets.ISO_8859_1).split("\r\n")
        val requestLine = lines.firstOrNull() ?: return null
        val headers = mutableListOf<Pair<String, String>>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf(':')
            if (separator <= 0) return null
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (!HEADER_NAME_PATTERN.matches(name)) return null
            if (value.any { it == '\r' || it == '\n' || it.code == 0 }) return null
            headers += name to value
        }
        if (headers.any { it.first.equals("Transfer-Encoding", true) }) return null
        return ParsedHeaders(requestLine, headers)
    }

    private fun contentLength(headers: List<Pair<String, String>>): ContentLengthResult {
        val values = headers
            .filter { it.first.equals("Content-Length", true) }
            .map {
                val parsed = it.second.toLongOrNull()
                    ?: return ContentLengthResult.Invalid("Invalid Content-Length")
                parsed
            }
            .distinct()
        if (values.size > 1) return ContentLengthResult.Invalid("Conflicting Content-Length headers")
        val value = values.singleOrNull() ?: 0L
        if (value < 0 || value > Int.MAX_VALUE) {
            return ContentLengthResult.Invalid("Invalid Content-Length")
        }
        return ContentLengthResult.Valid(value.toInt())
    }

    private fun expectationDisposition(headers: List<Pair<String, String>>): ExpectationDisposition {
        val values = headers
            .filter { it.first.equals("Expect", true) }
            .flatMap { it.second.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return when {
            values.isEmpty() -> ExpectationDisposition.NONE
            values.all { it.equals("100-continue", true) } -> ExpectationDisposition.CONTINUE
            else -> ExpectationDisposition.UNSUPPORTED
        }
    }

    private fun findHeaderEnd(bytes: ByteArray): Int {
        if (bytes.size < HEADER_DELIMITER.size) return -1
        for (i in 0..bytes.size - HEADER_DELIMITER.size) {
            if (
                bytes[i] == 13.toByte() &&
                bytes[i + 1] == 10.toByte() &&
                bytes[i + 2] == 13.toByte() &&
                bytes[i + 3] == 10.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private data class ParsedHeaders(
        val requestLine: String,
        val headers: List<Pair<String, String>>,
    )

    private data class ParsedRequestMetadata(
        val headerEnd: Int,
        val parsedHeaders: ParsedHeaders,
        val contentLength: Int,
        val totalLength: Int,
        val expectation: ExpectationDisposition,
    )

    private sealed interface ContentLengthResult {
        data class Valid(val bytes: Int) : ContentLengthResult
        data class Invalid(val message: String) : ContentLengthResult
    }

    private enum class ExpectationDisposition { NONE, CONTINUE, UNSUPPORTED }

    private companion object {
        val HEADER_DELIMITER = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        val METHOD_PATTERN = Regex("""[A-Z!#$%&\'*+.^_`|~-]+""")
        val HEADER_NAME_PATTERN = Regex("""[!#$%&\'*+.^_`|~0-9A-Za-z-]+""")
    }
}
