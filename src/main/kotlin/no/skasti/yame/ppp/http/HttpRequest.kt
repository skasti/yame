package no.skasti.yame.ppp.http

internal data class HttpRequest(
    val method: String,
    val target: String,
    val version: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
)
