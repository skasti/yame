package no.skasti.yame.ppp.http

import java.nio.charset.StandardCharsets

internal object HttpResponseEncoder {
    fun encodeHead(response: HttpResponse): ByteArray =
        buildString {
            append(response.version)
            append(' ')
            append(response.statusCode)
            append(' ')
            append(response.reasonPhrase)
            append("\r\n")
            response.headers.forEach { (name, value) ->
                append(name)
                append(": ")
                append(value)
                append("\r\n")
            }
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)

    fun bodyChunks(
        response: HttpResponse,
        maxChunkBytes: Int,
    ): Sequence<ByteArray> {
        require(maxChunkBytes > 0) { "maxChunkBytes must be positive" }
        return sequence {
            var offset = 0
            while (offset < response.body.size) {
                val end = minOf(offset + maxChunkBytes, response.body.size)
                yield(response.body.copyOfRange(offset, end))
                offset = end
            }
        }
    }
}
