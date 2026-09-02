package no.skasti.serialmodem.ppp

data class PppControlOption(
    val type: Int,
    val data: ByteArray,
) {
    fun encode(): ByteArray =
        byteArrayOf(type.toByte(), (data.size + 2).toByte()) + data

    companion object {
        const val MRU = 1
        const val ACCM = 2
        const val AUTHENTICATION_PROTOCOL = 3
        const val MAGIC_NUMBER = 5
        const val PROTOCOL_FIELD_COMPRESSION = 7
        const val ADDRESS_CONTROL_FIELD_COMPRESSION = 8

        const val IPCP_IP_COMPRESSION_PROTOCOL = 2
        const val IPCP_IP_ADDRESS = 3
        const val IPCP_PRIMARY_DNS = 129
        const val IPCP_SECONDARY_DNS = 131

        fun parseAll(data: ByteArray): List<PppControlOption>? {
            val result = mutableListOf<PppControlOption>()
            var offset = 0

            while (offset < data.size) {
                if (offset + 2 > data.size) return null

                val type = data[offset].toInt() and 0xff
                val length = data[offset + 1].toInt() and 0xff
                if (length < 2 || offset + length > data.size) return null

                result += PppControlOption(
                    type = type,
                    data = data.copyOfRange(offset + 2, offset + length),
                )
                offset += length
            }

            return result
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PppControlOption

        if (type != other.type) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + data.contentHashCode()
        return result
    }
}