package no.skasti.yame.ppp.ip

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