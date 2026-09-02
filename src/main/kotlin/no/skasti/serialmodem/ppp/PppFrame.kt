package no.skasti.serialmodem.ppp

data class PppFrame(
    val protocol: Int,
    val payload: ByteArray,
) {
    fun protocolName(): String = when (protocol) {
        0xC021 -> "LCP"
        0x8021 -> "IPCP"
        0x0021 -> "IPv4"
        else -> "0x%04X".format(protocol)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PppFrame

        if (protocol != other.protocol) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = protocol
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
