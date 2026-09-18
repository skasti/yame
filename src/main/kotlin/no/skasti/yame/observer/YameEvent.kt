package no.skasti.yame.observer

enum class DnsTransport {
    UDP,
    TCP,
}

enum class TransferDirection {
    TO_HOST,
    TO_PEER,
}

enum class TransferKind {
    TCP,
    DNS,
}

enum class TransferState {
    CONNECTING,
    OPEN,
    CLOSED,
    FAILED,
}

enum class HttpProxyActionKind {
    ROUTED,
    REQUEST,
    REDIRECT,
    RESPONSE,
    ERROR,
}

sealed interface YameEvent {
    data class DnsQuery(
        val key: String,
        val transport: DnsTransport,
        val id: Int?,
        val name: String?,
        val type: String?,
        val upstream: String,
        val bytes: Int,
    ) : YameEvent

    data class DnsResponse(
        val key: String,
        val transport: DnsTransport,
        val id: Int?,
        val name: String?,
        val responseCode: Int?,
        val answerCount: Int?,
        val truncated: Boolean,
        val bytes: Int,
    ) : YameEvent

    data class DnsFailure(
        val key: String,
        val transport: DnsTransport,
        val message: String,
    ) : YameEvent

    data class TransferStarted(
        val flowId: String,
        val destination: String,
        val via: String,
        val kind: TransferKind,
    ) : YameEvent

    data class TransferStateChanged(
        val flowId: String,
        val state: TransferState,
        val detail: String? = null,
    ) : YameEvent

    data class TransferBytes(
        val flowId: String,
        val direction: TransferDirection,
        val bytes: Int,
    ) : YameEvent

    data class HttpProxyAction(
        val flowId: String,
        val kind: HttpProxyActionKind,
        val message: String,
    ) : YameEvent
}
