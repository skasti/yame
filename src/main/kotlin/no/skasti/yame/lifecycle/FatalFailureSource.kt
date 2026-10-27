package no.skasti.yame.lifecycle

/** Read without callback dispatch or progress on the failed component's worker. */
interface FatalFailureSource {
    val fatalFailure: Throwable?
}
