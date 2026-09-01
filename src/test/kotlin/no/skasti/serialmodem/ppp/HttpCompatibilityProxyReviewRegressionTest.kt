package no.skasti.serialmodem.ppp

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpCompatibilityProxyReviewRegressionTest {
    @Test
    fun `base href tag uses base-specific rewriter`() {
        var baseCalls = 0
        val source = "<html><head><base href=\"https://cdn.test/app/\"></head></html>"
        val rewritten = rewriteHtmlUrlContexts(
            source = source,
            rewriteAttribute = { "ordinary:$it" },
            rewriteRaw = { it },
            rewriteBaseAttribute = {
                baseCalls++
                "base:$it"
            },
        )

        assertEquals(1, baseCalls)
        assertTrue(rewritten.contains("href=\"base:https://cdn.test/app/\""), rewritten)
    }

    @Test
    fun `host-only absolute URL stops before CSS closing parenthesis`() {
        val match = requireNotNull(ABSOLUTE_HTTP_URL_PATTERN.find("background:url(https://secure.test)"))
        assertEquals("https://secure.test", match.value)
    }

    @Test
    fun `mapped same-host HTTPS auth supports nondefault ports`() {
        val proxy = SystemHttpCompatibilityProxy()
        try {
            val method = proxy.javaClass.getDeclaredMethod(
                "canForwardSensitiveHeaders",
                URI::class.java,
                URI::class.java,
            ).apply { isAccessible = true }

            assertTrue(
                method.invoke(
                    proxy,
                    URI("http://example.test/private"),
                    URI("https://example.test:8443/private"),
                ) as Boolean,
            )
            assertFalse(
                method.invoke(
                    proxy,
                    URI("http://example.test/private"),
                    URI("https://other.test:8443/private"),
                ) as Boolean,
            )
        } finally {
            proxy.close()
        }
    }
}
