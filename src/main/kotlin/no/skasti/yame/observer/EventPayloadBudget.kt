package no.skasti.yame.observer

/** Fixed 128-byte envelope/value allowance, 40 + 2*length per non-null string.
 * Packet arrays add 24 + length. Exhaustive matching forces new event shapes to
 * define their accounting. Current event values are immutable; no admission copy.
 */
internal object EventPayloadBudget {
    fun bytes(event: YameEvent): Long = 128L + when (event) {
        is YameEvent.UnhandledPacket -> strings(event.packetType, event.reason) + 24L + event.byteCount
        is YameEvent.DnsQuery -> strings(event.key, event.name, event.type, event.upstream)
        is YameEvent.DnsResponse -> strings(event.key, event.name)
        is YameEvent.DnsFailure -> strings(event.key, event.message)
        is YameEvent.TransferStarted -> strings(event.flowId, event.destination, event.via)
        is YameEvent.TransferStateChanged -> strings(event.flowId, event.detail)
        is YameEvent.TransferBytes -> strings(event.flowId)
        is YameEvent.HttpProxyAction -> strings(event.flowId, event.message)
    }
    private fun strings(vararg values: String?): Long = values.sumOf { if (it == null) 0L else 40L + 2L * it.length }
}
