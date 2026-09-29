package no.skasti.yame.config

import no.skasti.yame.logging.YameLogLevel
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.ppp.PppIpConfig
import no.skasti.yame.ppp.dns.PppDnsConfig
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.ip.Ipv4Cidr
import no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig
import no.skasti.yame.serial.SerialFlowControl
import no.skasti.yame.tone.HandshakeProfile
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import kotlin.time.Duration.Companion.nanoseconds

class YameConfigurationStore(private val path: Path) {
    fun load(defaults: YameConfiguration = YameConfiguration()): YameConfiguration {
        if (!Files.exists(path)) return defaults
        require(Files.size(path) <= MAX_CONFIG_BYTES) {
            "Configuration file is larger than $MAX_CONFIG_BYTES bytes: $path"
        }

        val properties = Properties()
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            properties.load(reader)
        }
        if (properties.containsKey("login.password")) {
            restrictPermissions(path)
        }

        properties.getProperty("version")?.let { version ->
            require(version == CONFIG_VERSION) {
                "Unsupported yame.ini version '$version'"
            }
        }

        val modemDefaults = defaults.modemConfig
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
            pppIpConfig = PppIpConfig(
                configuredSubnet = properties.getProperty("ppp.subnet")
                    ?.takeIf(String::isNotBlank)
                    ?.let(Ipv4Cidr::parse)
                    ?: modemDefaults.pppIpConfig.configuredSubnet,
            ),
            pppDnsConfig = PppDnsConfig(
                upstreamServer = properties.getProperty("dns.upstream")
                    ?.let(Ipv4Address::parse)
                    ?: modemDefaults.pppDnsConfig.upstreamServer,
            ),
            pppHttpCompatibilityConfig = PppHttpCompatibilityConfig(
                enabled = boolean(
                    properties,
                    "http-https-proxy.enabled",
                    modemDefaults.pppHttpCompatibilityConfig.enabled,
                ),
            ),
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

        return YameConfiguration(
            portName = properties.getProperty("serial.port")
                ?.takeIf(String::isNotBlank)
                ?: defaults.portName,
            baudRate = baudRate,
            flowControl = properties.getProperty("serial.flow-control")
                ?.let(SerialFlowControl::parse)
                ?: defaults.flowControl,
            modemConfig = modemConfig,
            logLevels = logLevels,
        )
    }

    fun save(configuration: YameConfiguration) {
        val properties = Properties().apply {
            setProperty("version", CONFIG_VERSION)
            configuration.portName?.let { setProperty("serial.port", it) }
            setProperty("serial.baud", configuration.baudRate.toString())
            setProperty("serial.flow-control", configuration.flowControl.commandName)
            setProperty("modem.pickup-time-nanos", configuration.modemConfig.pickupTime.inWholeNanoseconds.toString())
            setProperty("modem.dial-tone-time-nanos", configuration.modemConfig.dialToneTime.inWholeNanoseconds.toString())
            setProperty("modem.handshake-profile", configuration.modemConfig.handshakeProfile.name)
            setProperty(
                "modem.tone-simulation-enabled",
                configuration.modemConfig.toneSimulationEnabled.toString(),
            )
            configuration.modemConfig.username?.let { setProperty("login.username", it) }
            configuration.modemConfig.password?.let { setProperty("login.password", it) }
            configuration.modemConfig.pppIpConfig.configuredSubnet?.let {
                setProperty("ppp.subnet", it.toString())
            }
            setProperty(
                "dns.upstream",
                configuration.modemConfig.pppDnsConfig.upstreamServer.toString(),
            )
            setProperty(
                "http-https-proxy.enabled",
                configuration.modemConfig.pppHttpCompatibilityConfig.enabled.toString(),
            )
            configuration.logLevels.forEach { (module, level) ->
                setProperty("loglevel.${module.fileName}", level.name.lowercase())
            }
        }

        val destination = path.toAbsolutePath().normalize()
        val parent = requireNotNull(destination.parent) {
            "Configuration path must have a parent directory"
        }
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".yame-config-", ".tmp")
        try {
            restrictPermissions(temporary)
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
                properties.store(writer, "YAME configuration; credentials are stored in plaintext")
            }
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
            restrictPermissions(destination)
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
        const val MAX_CONFIG_BYTES = 32 * 1024L
    }
}
