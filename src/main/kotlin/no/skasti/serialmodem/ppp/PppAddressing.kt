package no.skasti.serialmodem.ppp

import no.skasti.serialmodem.ppp.ip.Ipv4Address
import no.skasti.serialmodem.ppp.ip.Ipv4Cidr
import no.skasti.serialmodem.ppp.ip.LocalIpv4Network
import no.skasti.serialmodem.ppp.ip.discoverLocalIpv4Networks

private data class ReservedIpv4Range(
    val subnet: Ipv4Cidr,
    val reason: String,
)

private val UNUSABLE_PPP_ENDPOINT_RANGES =
    listOf(
        ReservedIpv4Range(Ipv4Cidr.parse("0.0.0.0/8"), "this-network"),
        ReservedIpv4Range(Ipv4Cidr.parse("127.0.0.0/8"), "loopback"),
        ReservedIpv4Range(Ipv4Cidr.parse("169.254.0.0/16"), "link-local"),
        ReservedIpv4Range(Ipv4Cidr.parse("224.0.0.0/4"), "multicast"),
        ReservedIpv4Range(Ipv4Cidr.parse("240.0.0.0/4"), "reserved/broadcast"),
    )

private fun reservedRangeFor(address: Ipv4Address): ReservedIpv4Range? =
    UNUSABLE_PPP_ENDPOINT_RANGES.firstOrNull { it.subnet.contains(address) }

private fun reservedRangeOverlapping(subnet: Ipv4Cidr): ReservedIpv4Range? =
    UNUSABLE_PPP_ENDPOINT_RANGES.firstOrNull { subnet.overlaps(it.subnet) }

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

class PppAddressResolver(
    private val config: PppIpConfig = PppIpConfig(),
    localNetworksProvider: () -> List<LocalIpv4Network> = ::discoverLocalIpv4Networks,
    private val logger: (String) -> Unit = ::println,
) {
    private val localNetworks: List<LocalIpv4Network> by lazy(localNetworksProvider)
    private val addresses: PppAddresses by lazy(::resolveAddresses)

    fun validateConfiguredSubnet() {
        config.configuredSubnet?.let {
            requireUsablePppSubnet(it)
            requireNoLocalOverlap(it)
        }
    }

    fun resolve(): PppAddresses = addresses

    fun selectPeerAddress(requested: Ipv4Address): Ipv4Address {
        val resolved = addresses

        if (config.configuredSubnet != null || requested == Ipv4Address.ZERO) {
            return resolved.peerAddress
        }

        val reserved = reservedRangeFor(requested)
        if (reserved != null) {
            logger(
                "PPP IP requested peer address $requested is in unusable " +
                    "${reserved.subnet} (${reserved.reason}); suggesting ${resolved.peerAddress}",
            )
            return resolved.peerAddress
        }

        val conflict = localNetworks.firstOrNull { it.subnet.contains(requested) }
        if (conflict != null) {
            logger(
                "PPP IP requested peer address $requested overlaps local subnet " +
                    "${conflict.subnet} on ${conflict.interfaceName}; " +
                    "suggesting ${resolved.peerAddress}",
            )
            return resolved.peerAddress
        }

        if (requested == resolved.localAddress) {
            logger(
                "PPP IP requested peer address $requested conflicts with YAME's local PPP address; " +
                    "suggesting ${resolved.peerAddress}",
            )
            return resolved.peerAddress
        }

        return requested
    }

    private fun resolveAddresses(): PppAddresses {
        val subnet =
            config.configuredSubnet?.also {
                requireUsablePppSubnet(it)
                requireNoLocalOverlap(it)
            }
                ?: AUTO_SUBNETS.firstOrNull { candidate ->
                    reservedRangeOverlapping(candidate) == null &&
                        localNetworks.none { candidate.overlaps(it.subnet) }
                }
                ?: error(
                    "Could not find a private PPP subnet that does not overlap a local interface",
                )

        val source = if (config.configuredSubnet != null) "configured" else "automatic"
        logger("PPP IP using $source subnet $subnet")

        return PppAddresses(
            localAddress = subnet.firstUsableAddress(),
            peerAddress = subnet.secondUsableAddress(),
            allocationSubnet = subnet,
        )
    }

    private fun requireUsablePppSubnet(subnet: Ipv4Cidr) {
        val reserved = reservedRangeOverlapping(subnet) ?: return
        error(
            "Configured PPP subnet $subnet overlaps unusable IPv4 range " +
                "${reserved.subnet} (${reserved.reason})",
        )
    }

    private fun requireNoLocalOverlap(subnet: Ipv4Cidr) {
        val conflict = localNetworks.firstOrNull { subnet.overlaps(it.subnet) } ?: return
        error(
            "Configured PPP subnet $subnet overlaps local subnet ${conflict.subnet} " +
                "on ${conflict.interfaceName}",
        )
    }

    companion object {
        private val AUTO_SUBNETS =
            listOf(
                "10.0.0.0/30",
                "10.64.0.0/30",
                "10.128.0.0/30",
                "10.192.0.0/30",
                "172.16.0.0/30",
                "172.31.255.252/30",
                "192.168.0.0/30",
                "192.168.255.252/30",
            ).map(Ipv4Cidr::parse)
    }
}

