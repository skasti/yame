package no.skasti.serialmodem.ppp.lcp

data class PppControlPacket(
    val code: Int,
    val identifier: Int,
    val data: ByteArray,
) {
    fun encode(): ByteArray {
        val length = HEADER_SIZE + data.size
        return byteArrayOf(
            code.toByte(),
            identifier.toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        ) + data
    }

    companion object {
        const val CONFIGURE_REQUEST = 1
        const val CONFIGURE_ACK = 2
        const val CONFIGURE_NAK = 3
        const val CONFIGURE_REJECT = 4

        private const val HEADER_SIZE = 4

        fun parse(payload: ByteArray): PppControlPacket? {
            if (payload.size < HEADER_SIZE) return null

            val length =
                ((payload[2].toInt() and 0xff) shl 8) or
                    (payload[3].toInt() and 0xff)

            if (length < HEADER_SIZE || length > payload.size) return null

            return PppControlPacket(
                code = payload[0].toInt() and 0xff,
                identifier = payload[1].toInt() and 0xff,
                data = payload.copyOfRange(HEADER_SIZE, length),
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PppControlPacket

        if (code != other.code) return false
        if (identifier != other.identifier) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = code
        result = 31 * result + identifier
        result = 31 * result + data.contentHashCode()
        return result
    }
}

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

typealias LcpPacket = PppControlPacket
typealias LcpOption = PppControlOption
