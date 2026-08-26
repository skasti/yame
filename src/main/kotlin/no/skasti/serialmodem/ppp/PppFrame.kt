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
}
