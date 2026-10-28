package no.skasti.yame.persistence

import no.skasti.yame.config.ConfigStore
import java.nio.file.Path

/** Features request their own definitions on first use. */
fun iniConfigStore(path: Path): ConfigStore =
    ConfigStore(emptyList(), repository = IniConfigurationRepository(path))
