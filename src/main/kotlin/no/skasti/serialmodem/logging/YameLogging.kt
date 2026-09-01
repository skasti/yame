package no.skasti.serialmodem.logging

import no.skasti.serialmodem.observer.HttpProxyActionKind
import no.skasti.serialmodem.observer.TransferState
import no.skasti.serialmodem.observer.YameEvent
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
) : AutoCloseable {
    private data class ModuleWriter(
        val writer: BufferedWriter,
        @Volatile var level: YameLogLevel,
    )

    private val lock = Any()
    private val writers: Map<YameLogModule, ModuleWriter>

    init {
        Files.createDirectories(logDirectory)
        writers = YameLogModule.entries.associateWith { module ->
            val active = logDirectory.resolve("${module.fileName}.log")
            rotateExisting(active, module)
            val writer = Files.newBufferedWriter(
                active,
                StandardCharsets.UTF_8,
            )
            ModuleWriter(writer, levels[module] ?: YameLogLevel.INFO)
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
        if (level.priority > target.level.priority) return

        synchronized(lock) {
            target.writer
                .append(LocalDateTime.now().format(LOG_TIME_FORMAT))
                .append(" ")
                .append(level.name)
                .append(" ")
                .append(message)
                .newLine()
            target.writer.flush()
        }
    }

    override fun close() {
        synchronized(lock) {
            writers.values.forEach { entry ->
                runCatching { entry.writer.close() }
            }
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
                "error" in message.lowercase() -> YameLogLevel.ERROR
            "warning" in message.lowercase() ||
                "unsupported" in message.lowercase() -> YameLogLevel.WARN
            "<=" in message ||
                "=>" in message ||
                " .. " in message -> YameLogLevel.DEBUG
            else -> YameLogLevel.INFO
        }

    companion object {
        fun defaultLevels(): Map<YameLogModule, YameLogLevel> =
            YameLogModule.entries.associateWith { YameLogLevel.INFO }

        private val LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        private val ARCHIVE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm")
    }
}
