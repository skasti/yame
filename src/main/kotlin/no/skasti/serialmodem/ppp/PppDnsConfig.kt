package no.skasti.serialmodem.ppp

data class PppDnsConfig(
    val upstreamServer: Ipv4Address = DEFAULT_UPSTREAM_SERVER,
) {
    init {
        require(upstreamServer != Ipv4Address.ZERO) {
            "DNS upstream server must not be 0.0.0.0"
        }
    }

    companion object {
        val DEFAULT_UPSTREAM_SERVER: Ipv4Address = Ipv4Address.parse("8.8.8.8")
    }
}
