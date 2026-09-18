package no.skasti.yame.logging

import no.skasti.yame.observer.HttpProxyActionKind
import no.skasti.yame.observer.TransferState
import no.skasti.yame.observer.YameEvent
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class YameLogLevel(val priority: Int) {
    ERROR(0),
    WARN(1),
    INFO(2),
    DEBUG(3);

    companion object {
        fun parse(value: String): YameLogLevel =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error("Log level must be error, warn, info, or debug, got '$value'")
    }
}

enum class YameLogModule(val fileName: String) {
    MODEM("modem"),
    SERIAL("serial"),
    PPP("ppp"),
    DNS("dns"),
    PROXY("proxy"),
    TRANSFERS("transfers"),
}

class YameLogManager(
    private val levels: Map<YameLogModule, YameLogLevel>,
    private val logDirectory: Path = Path.of("logs"),
    private val maxActiveLogBytes: Long = 10L * 1024L * 1024L,
    private val maxArchivesPerModule: Int = 5,
) : AutoCloseable {
    init {
        require(maxActiveLogBytes > 0) { "maxActiveLogBytes must be positive" }
        require(maxArchivesPerModule >= 0) { "maxArchivesPerModule must not be negative" }
    }
    private data class ModuleWriter(
        val activePath: Path,
        var writer: BufferedWriter?,
        @Volatile var level: YameLogLevel,
        @Volatile var writeFailed: Boolean = writer == null,
    )

    private val lock = Any()
    private val writers: Map<YameLogModule, ModuleWriter>

    init {
        val directoryReady = runCatching {
            Files.createDirectories(logDirectory)
        }.onFailure { error ->
            reportLoggingFailure("all", "initialization", error)
        }.isSuccess

        writers = YameLogModule.entries.associateWith { module ->
            val level = levels[module] ?: YameLogLevel.INFO
            val active = logDirectory.resolve("${module.fileName}.log")
            if (!directoryReady) {
                ModuleWriter(active, null, level)
            } else {
                runCatching {
                    rotateExisting(active, module)
                    pruneArchives(module)
                    Files.newBufferedWriter(
                        active,
                        StandardCharsets.UTF_8,
                    )
                }.fold(
                    onSuccess = { writer -> ModuleWriter(active, writer, level) },
                    onFailure = { error ->
                        reportLoggingFailure(module.fileName, "initialization", error)
                        ModuleWriter(active, null, level)
                    },
                )
            }
        }
    }

    fun level(module: YameLogModule): YameLogLevel =
        writers[module]?.level ?: YameLogLevel.INFO

    fun setLevel(module: YameLogModule, level: YameLogLevel) {
        writers[module]?.level = level
        log(module, YameLogLevel.INFO, "Log level changed to ${level.name.lowercase()}")
    }

    fun logger(module: YameLogModule): (String) -> Unit = { message ->
        log(module, inferLevel(message), message)
    }

    fun debugLogger(module: YameLogModule): (String) -> Unit = { message ->
        log(module, YameLogLevel.DEBUG, message)
    }

    fun eventSink(event: YameEvent) {
        when (event) {
            is YameEvent.DnsQuery ->
                log(
                    YameLogModule.DNS,
                    YameLogLevel.INFO,
                    "${event.transport} query id=${event.id ?: "?"} ${event.name ?: "?"} " +
                        "type=${event.type ?: "?"} upstream=${event.upstream} bytes=${event.bytes}",
                )

            is YameEvent.DnsResponse ->
                log(
                    YameLogModule.DNS,
                    YameLogLevel.INFO,
                    "${event.transport} response id=${event.id ?: "?"} ${event.name ?: "?"} " +
                        "rcode=${event.responseCode ?: "?"} answers=${event.answerCount ?: "?"} " +
                        "truncated=${event.truncated} bytes=${event.bytes}",
                )

            is YameEvent.DnsFailure ->
                log(
                    YameLogModule.DNS,
                    YameLogLevel.ERROR,
                    "${event.transport} failure flow=${event.key}: ${event.message}",
                )

            is YameEvent.TransferStarted ->
                log(
                    YameLogModule.TRANSFERS,
                    YameLogLevel.INFO,
                    "started flow=${event.flowId} kind=${event.kind} destination=${event.destination} via=${event.via}",
                )

            is YameEvent.TransferStateChanged ->
                log(
                    YameLogModule.TRANSFERS,
                    if (event.state == TransferState.FAILED) YameLogLevel.ERROR else YameLogLevel.INFO,
                    "state flow=${event.flowId} state=${event.state}" +
                        (event.detail?.let { " detail=$it" } ?: ""),
                )

            is YameEvent.TransferBytes ->
                log(
                    YameLogModule.TRANSFERS,
                    YameLogLevel.DEBUG,
                    "bytes flow=${event.flowId} direction=${event.direction} bytes=${event.bytes}",
                )

            is YameEvent.HttpProxyAction ->
                log(
                    YameLogModule.PROXY,
                    when (event.kind) {
                        HttpProxyActionKind.ERROR -> YameLogLevel.ERROR
                        HttpProxyActionKind.ROUTED -> YameLogLevel.DEBUG
                        else -> YameLogLevel.INFO
                    },
                    "${event.kind} flow=${event.flowId} ${event.message}",
                )
        }
    }

    fun log(module: YameLogModule, level: YameLogLevel, message: String) {
        val target = writers[module] ?: return
        if (level.priority > target.level.priority || target.writeFailed) return

        synchronized(lock) {
            if (target.writeFailed) return
            val writer = target.writer ?: return
            try {
                writer
                    .append(LocalDateTime.now().format(LOG_TIME_FORMAT))
                    .append(" ")
                    .append(level.name)
                    .append(" ")
                    .append(message)
                writer.newLine()
                writer.flush()
                rotateIfNeeded(module, target)
            } catch (e: Exception) {
                target.writeFailed = true
                reportLoggingFailure(module.fileName, "write", e)
                runCatching { writer.close() }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            writers.values.forEach { entry ->
                entry.writer?.let { writer ->
                    runCatching { writer.close() }
                }
            }
        }
    }

    private fun rotateIfNeeded(module: YameLogModule, target: ModuleWriter) {
        if (!Files.exists(target.activePath) || Files.size(target.activePath) < maxActiveLogBytes) return

        val current = target.writer ?: return
        current.close()
        rotateExisting(target.activePath, module)
        pruneArchives(module)
        target.writer = Files.newBufferedWriter(target.activePath, StandardCharsets.UTF_8)
    }

    private fun pruneArchives(module: YameLogModule) {
        val prefix = "${module.fileName}-"
        val archives = Files.list(logDirectory).use { stream ->
            stream
                .filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith(prefix) && name.endsWith(".log")
                }
                .sorted { left, right ->
                    Files.getLastModifiedTime(right).compareTo(Files.getLastModifiedTime(left))
                }
                .toList()
        }
        archives.drop(maxArchivesPerModule).forEach { archive ->
            runCatching { Files.deleteIfExists(archive) }
        }
    }

    private fun rotateExisting(active: Path, module: YameLogModule) {
        if (!Files.exists(active)) return

        val createdAt = originalCreationTime(active)
        val stamp = LocalDateTime.ofInstant(createdAt, ZoneId.systemDefault()).format(ARCHIVE_TIME_FORMAT)
        var archive = logDirectory.resolve("${module.fileName}-$stamp.log")
        var suffix = 2
        while (Files.exists(archive)) {
            archive = logDirectory.resolve("${module.fileName}-$stamp-${suffix++}.log")
        }
        runCatching {
            Files.move(active, archive, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(active, archive)
        }
    }

    private fun originalCreationTime(path: Path): Instant =
        runCatching {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            val creation = attributes.creationTime().toInstant()
            if (creation == Instant.EPOCH) attributes.lastModifiedTime().toInstant() else creation
        }.getOrElse {
            Files.getLastModifiedTime(path).toInstant()
        }

    private fun inferLevel(message: String): YameLogLevel =
        when {
            "!!" in message ||
                "failed" in message.lowercase() ||
                "error" in message.lowercase() ||
                "reader stopped" in message.lowercase() -> YameLogLevel.ERROR
            "warning" in message.lowercase() ||
                "unsupported" in message.lowercase() -> YameLogLevel.WARN
            "<=" in message ||
                "=>" in message ||
                " .. " in message -> YameLogLevel.DEBUG
            else -> YameLogLevel.INFO
        }

    private fun reportLoggingFailure(module: String, phase: String, error: Throwable) {
        runCatching {
            System.err.println(
                "YAME $module logging disabled after $phase failure: " +
                    (error.message ?: error::class.simpleName),
            )
        }
    }

    companion object {
        fun defaultLevels(): Map<YameLogModule, YameLogLevel> =
            YameLogModule.entries.associateWith { YameLogLevel.INFO }

        private val LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        private val ARCHIVE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm")
    }
}
