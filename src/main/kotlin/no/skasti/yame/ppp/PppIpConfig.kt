package no.skasti.yame.ppp

import no.skasti.yame.config.ConfigStore
import no.skasti.yame.config.ConfigChanges
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.ip.Ipv4Cidr

data class PppIpConfig(
    val configuredSubnet: Ipv4Cidr? = null,
) {
    companion object {
        fun fromConfigStore(store: ConfigStore = ConfigStore.current): PppIpConfig {
            store.ensureLoaded(PppSettings);
            return fromSnapshot(store.snapshot())
        }

        internal fun fromSnapshot(snapshot: ConfigChanges): PppIpConfig {
            return PppIpConfig(configuredSubnet = requireNotNull(snapshot[PppSettings.Subnet]).value)
        }
    }

    init {
        configuredSubnet?.let {
            require(it.prefixLength <= 30) {
                "PPP subnet $it must contain at least two usable host addresses"
            }
        }
    }
}

data class PppAddresses(
    val localAddress: Ipv4Address,
    val peerAddress: Ipv4Address,
    val allocationSubnet: Ipv4Cidr,
)