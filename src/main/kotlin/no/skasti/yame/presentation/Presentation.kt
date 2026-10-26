package no.skasti.yame.presentation

/**
 * Construction installs subscriptions without starting runtime producers.
 * [start] starts presentation-owned workers and returns without waiting for the
 * input/render loop. Main starts presentation before constructing the runtime;
 * unavailable core functions are ordinary command failures handled by the UI.
 */
interface Presentation : AutoCloseable {
    fun start()
}
