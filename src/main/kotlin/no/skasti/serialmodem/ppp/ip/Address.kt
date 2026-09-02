package no.skasti.serialmodem.ppp.ip

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

