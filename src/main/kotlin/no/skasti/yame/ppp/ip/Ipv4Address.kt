package no.skasti.yame.ppp.ip

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

