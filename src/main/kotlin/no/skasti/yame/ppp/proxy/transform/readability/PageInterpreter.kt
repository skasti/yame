package no.skasti.yame.ppp.proxy.transform.readability

import java.net.URI

/** An interpreter works on original markup, without network or filesystem access. */
internal fun interface PageInterpreter {
    /** Null means no useful result; callers retain the existing representation. */
    fun interpret(uri: URI, html: String): InterpretedPage?
}

/** Library-independent boundary: extracted markup is still untrusted. */
internal data class InterpretedPage(
    val title: String,
    val byline: String?,
    val contentHtml: String,
    val baseUri: URI,
)
