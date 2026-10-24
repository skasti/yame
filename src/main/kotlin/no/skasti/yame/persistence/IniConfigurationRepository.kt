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

private fun safeReplace(source: Path, destination: Path) {
    try { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
    catch (_: AtomicMoveNotSupportedException) { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING) }
}

/** No directory scanning or document cache: every save merges a fresh bounded read. */
class IniConfigurationRepository internal constructor(
    private val primaryFile: Path,
    private val replaceFile: (Path, Path) -> Unit,
) : ConfigurationRepository {
    constructor(primaryFile: Path) : this(primaryFile, ::safeReplace)
    private val directory = requireNotNull(primaryFile.toAbsolutePath().normalize().parent)
    private val primaryName = primaryFile.fileName.toString()
    /** Repository-local ownership; a failed definition load never enters this map. */
    private val owners = linkedMapOf<IniPropertyId, SettingKey<*>>()
    init { require(primaryName.endsWith(".ini")) }

    fun file(definition: Settings): Path =
        definition.file?.let { directory.resolve("$it.ini") } ?: directory.resolve(primaryName)

    override fun load(definition: Settings): ConfigurationLoad {
        val path = file(definition)
        val context = IniReadContext(path.fileName.toString(), read(path), Files.exists(path), definition = definition)
        val entries = definition.loadFromIni(context)
        val staged = context.claims().mapKeys { (location, _) ->
            IniPropertyId(path.toString(), location.first, location.second)
        }
        return ConfigurationLoad(entries) {
            synchronized(owners) {
                staged.forEach { (location, owner) -> checkOwner(location, owner, owners) }
                owners.putAll(staged)
            }
        }
    }

    private fun checkOwner(location: IniPropertyId, owner: SettingKey<*>, registry: Map<IniPropertyId, SettingKey<*>>) {
        val previous = registry[location]
        require(previous == null || previous === owner) {
            "${location.file} [${location.section}] ${location.property}: ${owner.path} conflicts with ${previous?.path}"
        }
    }

    /** The store holds its transaction lock across this call. */
    override fun persist(changes: Collection<ConfigChange<*>>, snapshot: ConfigChanges) {
        val replaced = mutableListOf<Path>()
        try {
            val byOwner = changes.groupBy { change ->
                requireNotNull(change.key.group.configuration) { "No INI owner for ${change.key.path}" }
            }
            val prepared = mutableListOf<Pair<Path, ByteArray>>()
            val pending = synchronized(owners) { LinkedHashMap(owners) }
            byOwner.entries.groupBy { file(it.key) }.forEach { (path, definitions) ->
                val document = read(path)
                val claims = linkedMapOf<Pair<String, String>, SettingKey<*>>()
                pending.filterKeys { it.file == path.toString() }.forEach { (location, owner) ->
                    claims[location.section to location.property] = owner
                }
                definitions.forEach { (definition, changed) ->
                    definition.saveToIni(IniWriteContext(snapshot, document, changed.map { it.key }.toSet(),
                        path.fileName.toString(), claims, definition = definition))
                }
                pending.keys.removeAll { it.file == path.toString() }
                claims.forEach { (location, owner) ->
                    val id = IniPropertyId(path.toString(), location.first, location.second)
                    checkOwner(id, owner, pending)
                    pending[id] = owner
                }
                val bytes = IniWriter.write(document).toByteArray(Charsets.UTF_8)
                require(bytes.size <= IniParser.MAX_BYTES) { "Configuration file exceeds ${IniParser.MAX_BYTES} bytes: $path" }
                prepared += path to bytes
            }
            // Every document and ownership mutation is validated before replacing any file.
            prepared.forEach { (path, bytes) ->
                Files.createDirectories(directory)
                val temporary = Files.createTempFile(directory, ".yame-ini-", ".tmp")
                try {
                    restrictPermissions(temporary)
                    Files.write(temporary, bytes)
                    replaceFile(temporary, path)
                    replaced.add(path)
                } finally { Files.deleteIfExists(temporary) }
            }
            synchronized(owners) { owners.clear(); owners.putAll(pending) }
        } catch (error: Exception) {
            if (replaced.isNotEmpty()) throw PartialIniWriteException(replaced, error)
            throw error
        }
    }

    private fun read(path: Path): IniDocument {
        if (!Files.exists(path)) return IniParser.parse("", path.fileName.toString())
        require(Files.isRegularFile(path)) { "Configuration path must be a regular file: $path" }
        // The file may contain secrets owned by definitions that have not loaded yet.
        // Restrict every existing INI file rather than enumerating feature keys.
        restrictPermissions(path)
        val bytes = Files.newInputStream(path).use { it.readNBytes(IniParser.MAX_BYTES + 1) }
        require(bytes.size <= IniParser.MAX_BYTES) { "Configuration file is larger than ${IniParser.MAX_BYTES} bytes: $path" }
        val doc = IniParser.parse(bytes.toString(Charsets.UTF_8), path.fileName.toString())
        if (path.fileName.toString() == primaryName) {
            doc.get("", "version")?.let { require(it == "1") { "Unsupported yame.ini version '$it'" } }
        }
        return doc
    }

    private fun restrictPermissions(path: Path) {
        try { Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
        catch (_: UnsupportedOperationException) { /* Filesystem does not implement POSIX permissions. */ }
    }
}
