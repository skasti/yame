package no.skasti.serialmodem.ppp.proxy

import java.net.URI
import java.util.Locale

internal val ABSOLUTE_HTTP_URL_PATTERN =
    Regex("""https?://(?:[^\s/?#"'<>@]+@)?(?:\[[^\]]+\]|[^\s/:?#"'<>()]+)(?::\d+)?(?:[/?#](?:[^\s"'<>\(\)]|\([^\(\)\s"'<>]*\))*)?""", RegexOption.IGNORE_CASE)

internal object LegacyHttpUrl {
    fun mirrorOf(uri: URI): URI =
        URI(
            buildString {
                append("http://")
                uri.rawUserInfo?.let { append(it).append('@') }
                append(formatHost(requireNotNull(uri.host)))
                append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                uri.rawQuery?.let { append('?').append(it) }
                uri.rawFragment?.let { append('#').append(it) }
            },
        )

    fun withoutFragment(uri: URI): URI =
        URI(
            buildString {
                append(requireNotNull(uri.scheme))
                append("://")
                uri.rawUserInfo?.let { append(it).append('@') }
                append(formatHost(requireNotNull(uri.host)))
                val port = effectivePort(uri)
                val defaultPort =
                    (uri.scheme.equals("http", ignoreCase = true) && port == 80) ||
                        (uri.scheme.equals("https", ignoreCase = true) && port == 443)
                if (!defaultPort) append(':').append(port)
                append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                uri.rawQuery?.let { append('?').append(it) }
            },
        )

    fun formatHost(host: String): String {
        val normalized = host.removePrefix("[").removeSuffix("]")
        return if (normalized.contains(':')) "[$normalized]" else normalized
    }

    fun effectivePort(uri: URI): Int =
        when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }

    fun requestObservableKey(uri: URI): String =
        URI(
            buildString {
                val scheme = requireNotNull(uri.scheme).lowercase(Locale.ROOT)
                append(scheme).append("://")
                append(formatHost(requireNotNull(uri.host).lowercase(Locale.ROOT)))
                val port = effectivePort(uri)
                val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
                if (!defaultPort) append(':').append(port)
                append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                uri.rawQuery?.let { append('?').append(it) }
            },
        ).toString()
}

internal fun legacyRedirectUri(upstreamUri: URI): URI =
    if (upstreamUri.scheme.equals("https", ignoreCase = true)) LegacyHttpUrl.mirrorOf(upstreamUri)
    else upstreamUri

internal fun shouldExposeRedirect(legacyRequestUri: URI, upstreamRedirectUri: URI): Boolean =
    legacyRedirectUri(upstreamRedirectUri).let { legacyRedirect ->
        LegacyHttpUrl.requestObservableKey(legacyRequestUri) !=
            LegacyHttpUrl.requestObservableKey(legacyRedirect) ||
            legacyRequestUri.rawFragment != legacyRedirect.rawFragment
    }

internal fun redirectedMethod(status: Int, method: String): String =
    when {
        status == 303 && !method.equals("HEAD", ignoreCase = true) -> "GET"
        (status == 301 || status == 302) && method.equals("POST", ignoreCase = true) -> "GET"
        else -> method
    }
