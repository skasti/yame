package no.skasti.yame.persistence

import no.skasti.yame.config.ConfigChanges
import no.skasti.yame.config.IniWriteContext
import no.skasti.yame.config.ini.IniParser
import no.skasti.yame.config.ini.IniWriter

import no.skasti.yame.logging.PacketCaptureLimitKind
import no.skasti.yame.logging.LogSettings
import no.skasti.yame.logging.YameLogLevel
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.ppp.proxy.transform.*
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.ppp.PppIpConfig
import no.skasti.yame.ppp.dns.PppDnsConfig
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.ip.Ipv4Cidr
import no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig
import no.skasti.yame.serial.SerialFlowControl
import no.skasti.yame.tone.HandshakeProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import kotlin.time.Duration.Companion.nanoseconds

/** Compatibility file API. Runtime reads and writes use ConfigStore and feature definitions. */
class YameConfigurationStore(private val path: Path) {
    fun load(defaults: YameConfiguration = YameConfiguration()): YameConfiguration {
        if (!Files.exists(path)) return defaults.copy(
            pppHttpCompatibilityConfig = defaults.pppHttpCompatibilityConfig.copy(
                elementSimplifier = ElementSimplifierConfigurationStore.alongside(path)
                    .load(defaults.pppHttpCompatibilityConfig.elementSimplifier),
            ),
        )
        require(Files.isRegularFile(path)) {
            "Configuration path must be a regular file: $path"
        }

        val properties = Properties()
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_CONFIG_BYTES + 1) }
        require(bytes.size <= MAX_CONFIG_BYTES) { "Configuration file is larger than $MAX_CONFIG_BYTES bytes: $path" }
        val document = IniParser.parse(bytes.toString(StandardCharsets.UTF_8), path.fileName.toString())
        // The old DTO remains an adapter. New section-based files are read through
        // feature definitions, so this API does not own a second set of mappings.
        if (document.sections().any { it in setOf("serial", "modem", "ppp", "dns", "proxy", "transformers", "logging") }) {
            val store = lazyApplicationConfigStore(path)
            return cliConfiguration(store).copy(
                pppHttpCompatibilityConfig = PppHttpCompatibilityConfig.fromConfigStore(store),
            )
        }
        val httpDefaults = defaults.pppHttpCompatibilityConfig
        val elementSimplifier = ElementSimplifierConfigurationStore.alongside(path).load(httpDefaults.elementSimplifier)
        val loadedDefaults = defaults.copy(
            pppHttpCompatibilityConfig = httpDefaults.copy(elementSimplifier = elementSimplifier),
        )
        document.properties("").forEach { (key, value) -> properties.setProperty(key, value) }
        if (properties.containsKey("login.password")) {
            restrictPermissions(path)
        }

        properties.getProperty("version")?.let { version ->
            require(version == CONFIG_VERSION) {
                "Unsupported yame.ini version '$version'"
            }
        }

        val modemDefaults = loadedDefaults.modemConfig
        val modemConfig = HayesModemConfig(
            pickupTime = duration(properties, "modem.pickup-time-nanos", modemDefaults.pickupTime),
            dialToneTime = duration(properties, "modem.dial-tone-time-nanos", modemDefaults.dialToneTime),
            handshakeProfile = properties.getProperty("modem.handshake-profile")
                ?.let(::parseHandshakeProfile)
                ?: modemDefaults.handshakeProfile,
            toneSimulationEnabled = boolean(
                properties,
                "modem.tone-simulation-enabled",
                modemDefaults.toneSimulationEnabled,
            ),
            username = properties.getProperty("login.username", modemDefaults.username),
            password = properties.getProperty("login.password", modemDefaults.password),
        )

        val logLevels = YameLogModule.entries.associateWith { module ->
            properties.getProperty("loglevel.${module.fileName}")
                ?.let(YameLogLevel::parse)
                ?: defaults.logLevels[module]
                ?: YameLogLevel.INFO
        }

        val baudRate = properties.getProperty("serial.baud")
            ?.toIntOrNull()
            ?: if (properties.containsKey("serial.baud")) {
                error("Invalid serial.baud in $path; expected a positive integer")
            } else {
                defaults.baudRate
            }
        require(baudRate > 0) { "serial.baud in $path must be positive" }

        val captureLimits = PacketCaptureLimitKind.entries.fold(defaults.captureLimits) { limits, kind ->
            properties.getProperty(kind.configKey)?.let { kind.apply(limits, kind.parse(it)) } ?: limits
        }

        return YameConfiguration(
            portName = properties.getProperty("serial.port")
                ?.takeIf(String::isNotBlank)
                ?: defaults.portName,
            baudRate = baudRate,
            flowControl = properties.getProperty("serial.flow-control")
                ?.let(SerialFlowControl::parse)
                ?: defaults.flowControl,
            modemConfig = modemConfig,
            pppIpConfig = PppIpConfig(
                configuredSubnet = properties.getProperty("ppp.subnet")
                    ?.takeIf(String::isNotBlank)
                    ?.let(Ipv4Cidr::parse)
                    ?: loadedDefaults.pppIpConfig.configuredSubnet,
            ),
            pppDnsConfig = PppDnsConfig(
                upstreamServer = properties.getProperty("dns.upstream")
                    ?.let(Ipv4Address::parse)
                    ?: loadedDefaults.pppDnsConfig.upstreamServer,
            ),
            pppHttpCompatibilityConfig = loadedDefaults.pppHttpCompatibilityConfig.copy(
                enabled = boolean(
                    properties,
                    "http-https-proxy.enabled",
                    loadedDefaults.pppHttpCompatibilityConfig.enabled,
                ),
                htmlTransformers = HtmlTransformer.entries.fold(loadedDefaults.pppHttpCompatibilityConfig.htmlTransformers) { settings, transformer ->
                    settings.withEnabled(transformer, boolean(properties, "transformers.${transformer.command}.enabled", settings.enabled(transformer)))
                },
                pngCompatibilityMode = properties.getProperty("transformers.png-compatibility-mode")
                    ?.let(PngCompatibilityMode::parse)
                    ?: loadedDefaults.pppHttpCompatibilityConfig.pngCompatibilityMode,
            ),
            logLevels = logLevels,
            captureLimits = captureLimits,
        )
    }

    fun save(configuration: YameConfiguration) {
        val existing = if (Files.exists(path)) {
            val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_CONFIG_BYTES + 1) }
            require(bytes.size <= MAX_CONFIG_BYTES) { "Configuration file is larger than $MAX_CONFIG_BYTES bytes: $path" }
            IniParser.parse(bytes.toString(StandardCharsets.UTF_8), path.fileName.toString())
        } else IniParser.parse("", path.fileName.toString())
        // Adapt only fields represented by the legacy DTO. Feature definitions own
        // their property names, encodings, and removal of optional values.
        val logKeys = LogSettings.Levels.values.toSet()
        val includedLogKeys = configuration.logLevels.keys.mapNotNull { LogSettings.Levels[it] }.toSet()
        val supported = YameConfigurationBindings.all.filter { binding ->
            binding.key.group.configuration?.file == null &&
                (binding.key !in logKeys || binding.key in includedLogKeys)
        }
        val snapshot = ConfigChanges(supported.map { it.change(configuration).also { change -> change.validate() } })
        val context = IniWriteContext(snapshot, existing, snapshot.mapTo(mutableSetOf()) { it.key })
        supported.mapNotNull { it.key.group.configuration }.distinct().forEach { it.saveToIni(context) }
        existing.put("", "version", CONFIG_VERSION)
        val serialized = IniWriter.write(existing).toByteArray(StandardCharsets.UTF_8)
        if (serialized.size > MAX_CONFIG_BYTES) throw IOException("Serialized configuration exceeds $MAX_CONFIG_BYTES bytes")
        val destination = path.toAbsolutePath().normalize()
        val parent = requireNotNull(destination.parent) {
            "Configuration path must have a parent directory"
        }
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".yame-config-", ".tmp")
        try {
            restrictPermissions(temporary)
            Files.write(
                temporary,
                serialized,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            restrictPermissions(temporary)
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun duration(properties: Properties, key: String, default: kotlin.time.Duration) =
        properties.getProperty(key)?.toLongOrNull()?.nanoseconds
            ?: if (properties.containsKey(key)) {
                error("Invalid $key in $path; expected nanoseconds")
            } else {
                default
            }

    private fun boolean(properties: Properties, key: String, default: Boolean): Boolean =
        properties.getProperty(key)?.let { value ->
            when (value.lowercase()) {
                "true" -> true
                "false" -> false
                else -> error("Invalid $key in $path; expected true or false")
            }
        } ?: default

    private fun parseHandshakeProfile(value: String): HandshakeProfile =
        HandshakeProfile.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: error("Invalid modem.handshake-profile in $path")

    private fun restrictPermissions(file: Path) {
        try {
            Files.setPosixFilePermissions(
                file,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            // Keep the store usable on filesystems without POSIX permissions.
        }
    }

    private companion object {
        const val CONFIG_VERSION = "1"
    }
}

internal const val MAX_CONFIG_BYTES = 32 * 1024

internal fun serializePropertiesWithinLimit(properties: Properties): ByteArray {
    val bytes = ByteArrayOutputStream(MAX_CONFIG_BYTES)
    val boundedOutput = object : OutputStream() {
        private fun requireCapacity(additionalBytes: Int) {
            if (additionalBytes > MAX_CONFIG_BYTES - bytes.size()) {
                throw IOException("Serialized configuration exceeds $MAX_CONFIG_BYTES bytes")
            }
        }

        override fun write(value: Int) {
            requireCapacity(1)
            bytes.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            requireCapacity(length)
            bytes.write(buffer, offset, length)
        }
    }

    OutputStreamWriter(boundedOutput, StandardCharsets.UTF_8).use { writer ->
        properties.store(writer, "YAME configuration; credentials are stored in plaintext")
    }
    return bytes.toByteArray()
}
