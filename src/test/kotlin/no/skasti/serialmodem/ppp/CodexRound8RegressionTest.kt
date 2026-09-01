package no.skasti.serialmodem.ppp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexRound8RegressionTest {
    @Test
    fun `HTML attributes are rewritten once while script raw text stays raw`() {
        val source =
            "<a href=\"https://example.test/next?a=1&amp;b=2\">next</a>" +
                "<script>window.location.href = \"https://api.test/x?a=1&b=2\"</script>"

        val rewritten = rewriteHtmlUrlContexts(
            source = source,
            rewriteAttribute = { value -> value.replace("https://", "attr://") },
            rewriteRaw = { value -> value.replace("https://", "raw://") },
        )

        assertEquals(
            "<a href=\"attr://example.test/next?a=1&amp;b=2\">next</a>" +
                "<script>window.location.href = \"raw://api.test/x?a=1&b=2\"</script>",
            rewritten,
        )
    }

    @Test
    fun `default compatibility request limit no longer rejects normal uploads above 64 KiB`() {
        assertTrue(PppHttpCompatibilityConfig().maxRequestBytes > 0xffff)
        assertEquals(128 * 1024, PppHttpCompatibilityConfig(maxRequestBytes = 128 * 1024).maxRequestBytes)
    }
}
