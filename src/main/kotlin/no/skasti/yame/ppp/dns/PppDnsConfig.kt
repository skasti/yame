package no.skasti.yame.ppp.dns

import no.skasti.yame.config.ConfigStore
import no.skasti.yame.config.ConfigChanges
import no.skasti.yame.ppp.ip.Ipv4Address

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
        fun fromConfigStore(store: ConfigStore = ConfigStore.current): PppDnsConfig {
            store.ensureLoaded(DnsSettings);
            return fromSnapshot(store.snapshot())
        }

        internal fun fromSnapshot(snapshot: ConfigChanges): PppDnsConfig {
            return PppDnsConfig(upstreamServer = requireNotNull(snapshot[DnsSettings.Upstream]).value)
        }

        const val DNS_PORT = 53
        val DEFAULT_UPSTREAM_SERVER: Ipv4Address = Ipv4Address.Companion.parse("8.8.8.8")
    }
}