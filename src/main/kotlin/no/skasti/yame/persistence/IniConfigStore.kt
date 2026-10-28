package no.skasti.yame.persistence

import no.skasti.yame.config.ConfigStore
import java.nio.file.Path

/** No feature registry: the requested key identifies the definition to load. */
fun lazyApplicationConfigStore(path: Path): ConfigStore =
    ConfigStore(repository = IniConfigurationRepository(path))
