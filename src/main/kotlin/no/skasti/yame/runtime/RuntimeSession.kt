package no.skasti.yame.runtime

/** Blocking core execution, independent of any presentation. */
internal interface RuntimeSession : AutoCloseable {
    fun run()
}
