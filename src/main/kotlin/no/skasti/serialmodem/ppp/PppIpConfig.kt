package no.skasti.serialmodem.ppp

import no.skasti.serialmodem.ppp.ip.Ipv4Address
import no.skasti.serialmodem.ppp.ip.Ipv4Cidr

data class PppIpConfig(
    val configuredSubnet: Ipv4Cidr? = null,
) {
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