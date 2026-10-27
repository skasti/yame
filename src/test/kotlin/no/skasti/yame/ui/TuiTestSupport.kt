package no.skasti.yame.ui

/** Callback admission stays asynchronous; assertions explicitly await worker processing. */
internal fun TuiYameObserver.handleKeyAndAwait(key: String) {
    handleKey(key)
    awaitIdle()
}
