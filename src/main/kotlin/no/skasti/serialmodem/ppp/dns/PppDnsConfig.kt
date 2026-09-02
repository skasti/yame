package no.skasti.serialmodem.ppp.dns

import no.skasti.serialmodem.ppp.ip.Ipv4Address

data class PppDnsConfig(
    val upstreamServer: Ipv4Address = DEFAULT_UPSTREAM_SERVER,
    val upstreamPort: Int = DNS_PORT,
) {
    init {
        require(upstreamServer != Ipv4Address.Companion.ZERO) {
            "DNS upstream server must not be 0.0.0.0"
        }
        require(upstreamPort in 1..0xffff) {
            "DNS upstream port must be 1..65535"
        }
    }

    companion object {
        const val DNS_PORT = 53
        val DEFAULT_UPSTREAM_SERVER: Ipv4Address = Ipv4Address.Companion.parse("8.8.8.8")
    }
}