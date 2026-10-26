package no.skasti.yame.presentation

/**
 * Construction installs subscriptions without starting runtime producers.
 * [start] starts presentation-owned workers and returns without waiting for the
 * input/render loop. Bootstrap calls it after core functions are registered.
 */
interface Presentation : AutoCloseable {
    fun start()
}
