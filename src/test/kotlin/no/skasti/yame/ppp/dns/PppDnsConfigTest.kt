package no.skasti.yame.ppp.dns
import no.skasti.yame.ppp.dns.PppDnsConfig
import no.skasti.yame.ppp.ip.Ipv4Address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PppDnsConfigTest {
    @Test
    fun `default upstream DNS is Google public DNS`() {
        assertEquals(
            Ipv4Address.parse("8.8.8.8"),
            PppDnsConfig().upstreamServer,
        )
    }

    @Test
    fun `zero upstream DNS is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PppDnsConfig(upstreamServer = Ipv4Address.ZERO)
        }
    }
}
