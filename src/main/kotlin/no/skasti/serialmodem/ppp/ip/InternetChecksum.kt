package no.skasti.serialmodem.ppp.ip

internal fun internetChecksum(
    bytes: ByteArray,
    offset: Int = 0,
    length: Int = bytes.size - offset,
): Int {
    require(offset >= 0 && length >= 0 && offset + length <= bytes.size)

    var sum = 0L
    var index = offset
    val end = offset + length

    while (index + 1 < end) {
        sum +=
            ((bytes[index].toInt() and 0xff) shl 8) or
                (bytes[index + 1].toInt() and 0xff)
        index += 2
    }

    if (index < end) {
        sum += (bytes[index].toInt() and 0xff) shl 8
    }

    while (sum ushr 16 != 0L) {
        sum = (sum and 0xffffL) + (sum ushr 16)
    }

    return sum.inv().toInt() and 0xffff
}
