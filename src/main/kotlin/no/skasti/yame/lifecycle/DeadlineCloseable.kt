package no.skasti.yame.lifecycle

/** A consumer whose drain shares the application deadline, measured with System.nanoTime(). */
interface DeadlineCloseable : AutoCloseable {
    fun closeBefore(deadlineNanos: Long)
}
