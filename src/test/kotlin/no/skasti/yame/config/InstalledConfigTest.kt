package no.skasti.yame.config

import org.junit.jupiter.api.AfterEach

/** Holds one test's process configuration until its components have been closed. */
open class InstalledConfigTest {
    private var installedStore: ConfigStore? = null
    private var installation: AutoCloseable? = null

    protected fun installConfig(store: ConfigStore) {
        if (installedStore != null) {
            check(installedStore === store) { "A test must close its components before selecting another store" }
            return
        }
        installation = ConfigStore.install(store)
        installedStore = store
    }

    @AfterEach fun releaseTestConfig() {
        installation?.close()
        installation = null
        installedStore = null
    }
}

/** Factories return immutable values, so a short installation is sufficient. */
fun <T> withInstalledConfig(store: ConfigStore, read: () -> T): T = synchronized(ConfigStore) {
    if (runCatching { ConfigStore.current }.getOrNull() === store) read()
    else ConfigStore.install(store).use { read() }
}
