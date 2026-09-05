package no.skasti.serialmodem.ppp.http

internal data class HttpResponse(
    val statusCode: Int,
    val reasonPhrase: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
    val version: String = "HTTP/1.0",
)
