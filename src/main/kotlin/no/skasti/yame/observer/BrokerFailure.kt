package no.skasti.yame.observer

/** Bounded metadata only: never retains an event, callback, throwable or credentials. */
data class BrokerFailure(
    val reason: Reason,
    val capacity: Int,
    val queuedEvents: Int,
    val activeSubscriber: String?,
    val lastAcceptedSequence: Long,
) {
    enum class Reason { QUEUE_FULL }
}

class QueueFullException(val failure: BrokerFailure) : IllegalStateException(
    "Event queue full: ${failure.queuedEvents}/${failure.capacity} events; active=${failure.activeSubscriber}"
)
class BrokerFailedException(val failure: BrokerFailure) : IllegalStateException("Event broker has failed: ${failure.reason}")
class BrokerClosedException : IllegalStateException("Event broker is closed for publication")

data class SubscriberFailure(val subscriber: String, val phase: Phase) {
    enum class Phase { FILTER, HANDLER }
}

enum class BrokerWaitResult { COMPLETED, TIMED_OUT, WOULD_DEADLOCK }
