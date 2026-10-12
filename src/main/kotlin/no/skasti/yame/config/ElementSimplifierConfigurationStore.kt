package no.skasti.yame.config

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class ElementSimplifierConfigurationStore(private val path: Path) {
    fun load(defaults: ElementSimplifierConfiguration = ElementSimplifierConfiguration.defaults()): ElementSimplifierConfiguration {
        if (!Files.exists(path)) return defaults
        require(Files.isRegularFile(path)) { "Element-simplifier configuration must be a regular file: $path" }
        return Files.newInputStream(path).use { ElementSimplifierIni.parse(ElementSimplifierIni.readBounded(it)) }
    }

    /** Never replace an existing user's rules, including during automatic upgrades. */
    fun createDefaultIfMissing() {
        Files.createDirectories(path.toAbsolutePath().parent)
        try {
            Files.writeString(path, ElementSimplifierIni.defaultText, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (_: FileAlreadyExistsException) {
            // An existing file belongs to the user.
        }
    }

    companion object {
        const val FILE_NAME = "element-simplifier.ini"
        fun alongside(configPath: Path): ElementSimplifierConfigurationStore =
            ElementSimplifierConfigurationStore(configPath.resolveSibling(FILE_NAME))
    }
}
