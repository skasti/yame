package no.skasti.yame.persistence

import no.skasti.yame.config.*
import no.skasti.yame.config.ini.*
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

class PartialIniWriteException(val replacedFiles: List<Path>, cause: Exception) :
    IllegalStateException("INI write failed after replacing ${replacedFiles.joinToString()}; restart to reload disk state", cause)

/** No directory scanning or document cache: every save merges a fresh bounded read. */
class IniConfigurationRepository(private val primaryFile: Path) : ConfigurationRepository {
    private val directory = requireNotNull(primaryFile.toAbsolutePath().normalize().parent)
    private val primaryName = primaryFile.fileName.toString()
    init { require(primaryName.endsWith(".ini")) }

    fun file(definition: Settings): Path =
        definition.file?.let { directory.resolve("$it.ini") } ?: directory.resolve(primaryName)

    override fun fileIdentity(definition: Settings): String = file(definition).toString()

    override fun load(definition: Settings): Pair<List<ConfigChange<*>>, Set<Pair<String, String>>> {
        val path = file(definition)
        val context = IniReadContext(path.fileName.toString(), read(path), Files.exists(path))
        return definition.loadFromIni(context) to context.claims()
    }

    /** The store holds its transaction lock across this call. */
    override fun persist(changes: Collection<ConfigChange<*>>, snapshot: ConfigChanges) {
        val replaced = mutableListOf<Path>()
        try {
            val byOwner = changes.groupBy { change ->
                requireNotNull(change.key.group.configuration) { "No INI owner for ${change.key.path}" }
            }
            byOwner.entries.groupBy { file(it.key) }.forEach { (path, owners) ->
                val document = read(path)
                owners.forEach { (definition, changed) ->
                    definition.saveToIni(IniWriteContext(snapshot, document, changed.map { it.key }.toSet()))
                }
                val bytes = IniWriter.write(document).toByteArray(Charsets.UTF_8)
                require(bytes.size <= IniParser.MAX_BYTES) { "Configuration file exceeds ${IniParser.MAX_BYTES} bytes: $path" }
                Files.createDirectories(directory)
                val temporary = Files.createTempFile(directory, ".yame-ini-", ".tmp")
                try {
                    restrictPermissions(temporary)
                    Files.write(temporary, bytes)
                    try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                    catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
                    replaced.add(path)
                } finally { Files.deleteIfExists(temporary) }
            }
        } catch (error: Exception) {
            if (replaced.isNotEmpty()) throw PartialIniWriteException(replaced, error)
            throw error
        }
    }

    private fun read(path: Path): IniDocument {
        if (!Files.exists(path)) return IniParser.parse("", path.fileName.toString())
        require(Files.isRegularFile(path)) { "Configuration path must be a regular file: $path" }
        val bytes = Files.newInputStream(path).use { it.readNBytes(IniParser.MAX_BYTES + 1) }
        require(bytes.size <= IniParser.MAX_BYTES) { "Configuration file is larger than ${IniParser.MAX_BYTES} bytes: $path" }
        val doc = IniParser.parse(bytes.toString(Charsets.UTF_8), path.fileName.toString())
        if (path.fileName.toString() == primaryName) {
            doc.get("", "version")?.let { require(it == "1") { "Unsupported yame.ini version '$it'" } }
            if (doc.get("", "login.password") != null) restrictPermissions(path)
        }
        return doc
    }

    private fun restrictPermissions(path: Path) {
        try { Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
        catch (_: UnsupportedOperationException) { /* Filesystem does not implement POSIX permissions. */ }
    }
}
