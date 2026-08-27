package no.skasti.serialmodem.ppp

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

@JvmInline
value class Ipv4Address(val value: UInt) {
    fun toByteArray(): ByteArray =
        byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte(),
        )

    override fun toString(): String =
        listOf(
            (value shr 24) and 0xffu,
            (value shr 16) and 0xffu,
            (value shr 8) and 0xffu,
            value and 0xffu,
        ).joinToString(".")

    companion object {
        val ZERO = Ipv4Address(0u)

        fun parse(value: String): Ipv4Address {
            val octets = value.split('.')
            require(octets.size == 4) { "Invalid IPv4 address '$value'" }

            var result = 0u
            for (octet in octets) {
                val number = octet.toIntOrNull()
                require(number != null && number in 0..255) {
                    "Invalid IPv4 address '$value'"
                }
                result = (result shl 8) or number.toUInt()
            }
            return Ipv4Address(result)
        }

        fun fromBytes(bytes: ByteArray, offset: Int = 0): Ipv4Address {
            require(offset >= 0 && offset + 4 <= bytes.size) {
                "IPv4 address requires four bytes"
            }
            return Ipv4Address(
                ((bytes[offset].toUInt() and 0xffu) shl 24) or
                    ((bytes[offset + 1].toUInt() and 0xffu) shl 16) or
                    ((bytes[offset + 2].toUInt() and 0xffu) shl 8) or
                    (bytes[offset + 3].toUInt() and 0xffu),
            )
        }
    }
}

data class Ipv4Cidr private constructor(
    val networkAddress: Ipv4Address,
    val prefixLength: Int,
) {
    init {
        require(prefixLength in 0..32) {
            "IPv4 prefix length must be between 0 and 32"
        }
    }

    fun contains(address: Ipv4Address): Boolean =
        (address.value and mask(prefixLength)) == networkAddress.value

    fun overlaps(other: Ipv4Cidr): Boolean =
        contains(other.networkAddress) || other.contains(networkAddress)

    fun firstUsableAddress(): Ipv4Address {
        require(prefixLength <= 30) {
            "$this does not contain two usable host addresses"
        }
        return Ipv4Address(networkAddress.value + 1u)
    }

    fun secondUsableAddress(): Ipv4Address {
        require(prefixLength <= 30) {
            "$this does not contain two usable host addresses"
        }
        return Ipv4Address(networkAddress.value + 2u)
    }

    override fun toString(): String = "$networkAddress/$prefixLength"

    companion object {
        fun parse(value: String): Ipv4Cidr {
            val parts = value.split('/')
            require(parts.size == 2) {
                "Invalid IPv4 subnet '$value'; expected CIDR notation such as 10.0.0.0/30"
            }

            val address = Ipv4Address.parse(parts[0])
            val prefix = parts[1].toIntOrNull()
            require(prefix != null && prefix in 0..32) {
                "Invalid IPv4 subnet '$value'"
            }

            return Ipv4Cidr(
                networkAddress = Ipv4Address(address.value and mask(prefix)),
                prefixLength = prefix,
            )
        }

        private fun mask(prefixLength: Int): UInt =
            when (prefixLength) {
                0 -> 0u
                else -> UInt.MAX_VALUE shl (32 - prefixLength)
            }
    }
}

data class LocalIpv4Network(
    val interfaceName: String,
    val subnet: Ipv4Cidr,
)

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
    private val localNetworksProvider: () -> List<LocalIpv4Network> = ::discoverLocalIpv4Networks,
    private val logger: (String) -> Unit = ::println,
) {
    private val localNetworks: List<LocalIpv4Network> by lazy(localNetworksProvider)
    private val addresses: PppAddresses by lazy(::resolveAddresses)

    fun validateConfiguredSubnet() {
        config.configuredSubnet?.let(::requireNoLocalOverlap)
    }

    fun resolve(): PppAddresses = addresses

    fun selectPeerAddress(requested: Ipv4Address): Ipv4Address {
        val resolved = addresses

        if (config.configuredSubnet != null || requested == Ipv4Address.ZERO) {
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
            config.configuredSubnet?.also(::requireNoLocalOverlap)
                ?: AUTO_SUBNETS.firstOrNull { candidate ->
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

fun discoverLocalIpv4Networks(): List<LocalIpv4Network> {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()

    return Collections.list(interfaces)
        .filter { networkInterface ->
            runCatching {
                networkInterface.isUp && !networkInterface.isLoopback
            }.getOrDefault(false)
        }
        .flatMap { networkInterface ->
            networkInterface.interfaceAddresses.mapNotNull { interfaceAddress ->
                val address = interfaceAddress.address as? Inet4Address
                    ?: return@mapNotNull null
                val prefix = interfaceAddress.networkPrefixLength.toInt()
                if (prefix !in 0..32) return@mapNotNull null

                val ip = Ipv4Address.fromBytes(address.address)
                LocalIpv4Network(
                    interfaceName = networkInterface.name,
                    subnet = Ipv4Cidr.parse("$ip/$prefix"),
                )
            }
        }
}
