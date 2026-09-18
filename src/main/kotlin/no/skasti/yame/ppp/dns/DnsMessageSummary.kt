package no.skasti.yame.ppp.dns

data class DnsMessageSummary(
    val id: Int,
    val response: Boolean,
    val responseCode: Int,
    val truncated: Boolean,
    val questionName: String?,
    val questionType: Int?,
    val answerCount: Int,
) {
    val questionTypeName: String?
        get() = questionType?.let(::dnsTypeName)
}

fun summarizeDnsMessage(bytes: ByteArray): DnsMessageSummary? {
    if (bytes.size < DNS_HEADER_BYTES) return null

    val id = readUnsignedShort(bytes, 0)
    val flags = readUnsignedShort(bytes, 2)
    val questionCount = readUnsignedShort(bytes, 4)
    val answerCount = readUnsignedShort(bytes, 6)

    var questionName: String? = null
    var questionType: Int? = null

    if (questionCount > 0) {
        val parsed = readDnsName(bytes, DNS_HEADER_BYTES)
        if (parsed != null && parsed.nextOffset + 4 <= bytes.size) {
            questionName = parsed.name.ifBlank { "." }
            questionType = readUnsignedShort(bytes, parsed.nextOffset)
        }
    }

    return DnsMessageSummary(
        id = id,
        response = flags and 0x8000 != 0,
        responseCode = flags and 0x000f,
        truncated = flags and 0x0200 != 0,
        questionName = questionName,
        questionType = questionType,
        answerCount = answerCount,
    )
}

fun dnsTypeName(type: Int): String =
    when (type) {
        1 -> "A"
        2 -> "NS"
        5 -> "CNAME"
        6 -> "SOA"
        12 -> "PTR"
        15 -> "MX"
        16 -> "TXT"
        28 -> "AAAA"
        33 -> "SRV"
        255 -> "ANY"
        else -> "TYPE$type"
    }

fun dnsResponseCodeName(code: Int): String =
    when (code) {
        0 -> "NOERROR"
        1 -> "FORMERR"
        2 -> "SERVFAIL"
        3 -> "NXDOMAIN"
        4 -> "NOTIMP"
        5 -> "REFUSED"
        else -> "RCODE$code"
    }

private data class ParsedDnsName(
    val name: String,
    val nextOffset: Int,
)

private fun readDnsName(
    bytes: ByteArray,
    startOffset: Int,
): ParsedDnsName? {
    if (startOffset !in bytes.indices) return null

    val labels = mutableListOf<String>()
    val visitedPointers = mutableSetOf<Int>()
    var cursor = startOffset
    var nextOffset = startOffset
    var jumped = false
    var steps = 0

    while (steps++ < MAX_NAME_STEPS) {
        if (cursor !in bytes.indices) return null
        val length = bytes[cursor].toInt() and 0xff

        when {
            length == 0 -> {
                if (!jumped) nextOffset = cursor + 1
                return ParsedDnsName(labels.joinToString("."), nextOffset)
            }

            length and 0xc0 == 0xc0 -> {
                if (cursor + 1 >= bytes.size) return null
                val pointer =
                    ((length and 0x3f) shl 8) or
                        (bytes[cursor + 1].toInt() and 0xff)
                if (pointer >= bytes.size || !visitedPointers.add(pointer)) return null
                if (!jumped) {
                    nextOffset = cursor + 2
                    jumped = true
                }
                cursor = pointer
            }

            length and 0xc0 != 0 || length > 63 -> return null

            else -> {
                val labelStart = cursor + 1
                val labelEnd = labelStart + length
                if (labelEnd > bytes.size) return null
                labels += bytes
                    .copyOfRange(labelStart, labelEnd)
                    .toString(Charsets.US_ASCII)
                cursor = labelEnd
                if (!jumped) nextOffset = cursor
            }
        }
    }

    return null
}

private fun readUnsignedShort(
    bytes: ByteArray,
    offset: Int,
): Int =
    ((bytes[offset].toInt() and 0xff) shl 8) or
        (bytes[offset + 1].toInt() and 0xff)

private const val DNS_HEADER_BYTES = 12
private const val MAX_NAME_STEPS = 128
