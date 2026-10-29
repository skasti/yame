package no.skasti.yame.persistence

import no.skasti.yame.config.ConfigStore
import java.nio.file.Path

/** Bootstrap installs the sole process store; consumers never receive an instance. */
fun installApplicationConfigStore(path: Path): AutoCloseable =
    ConfigStore.install(ConfigStore(repository = IniConfigurationRepository(path)))
