package no.skasti.serialmodem.ppp

import no.skasti.serialmodem.ppp.dns.dnsResponseCodeName
import no.skasti.serialmodem.ppp.dns.summarizeDnsMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DnsMessageSummaryTest {
    @Test
    fun summarizesQuery() {
        val message = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x02, 'v'.code.toByte(), 'g'.code.toByte(),
            0x02, 'n'.code.toByte(), 'o'.code.toByte(),
            0x00,
            0x00, 0x01,
            0x00, 0x01,
        )

        val summary = assertNotNull(summarizeDnsMessage(message))

        assertEquals(0x1234, summary.id)
        assertFalse(summary.response)
        assertEquals("vg.no", summary.questionName)
        assertEquals("A", summary.questionTypeName)
        assertEquals(0, summary.answerCount)
    }

    @Test
    fun summarizesCompressedResponseQuestionAndFlags() {
        val message = byteArrayOf(
            0x12, 0x34, 0x83.toByte(), 0x00, 0x00, 0x01, 0x00, 0x02,
            0x00, 0x00, 0x00, 0x00,
            0x02, 'v'.code.toByte(), 'g'.code.toByte(),
            0x02, 'n'.code.toByte(), 'o'.code.toByte(),
            0x00,
            0x00, 0x1c,
            0x00, 0x01,
        )

        val summary = assertNotNull(summarizeDnsMessage(message))

        assertTrue(summary.response)
        assertTrue(summary.truncated)
        assertEquals("AAAA", summary.questionTypeName)
        assertEquals(2, summary.answerCount)
        assertEquals("NOERROR", dnsResponseCodeName(summary.responseCode))
    }
}
