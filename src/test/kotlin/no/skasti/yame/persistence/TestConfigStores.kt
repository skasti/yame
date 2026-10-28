package no.skasti.yame.persistence

import no.skasti.yame.config.ConfigStore
import java.nio.file.Path

fun lazyApplicationConfigStore(path: Path): ConfigStore =
    ConfigStore(repository = IniConfigurationRepository(path))
