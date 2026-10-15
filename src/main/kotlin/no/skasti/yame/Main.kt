package no.skasti.yame

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.persistence.YameConfiguration
import no.skasti.yame.persistence.YameConfigurationStore
import no.skasti.yame.ppp.proxy.transform.ElementSimplifierConfigurationStore
import no.skasti.yame.logging.PacketCaptureLimits
import no.skasti.yame.logging.PacketCaptureLimitKind
import no.skasti.yame.logging.YameLogLevel
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.modem.HayesModem
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.ip.Ipv4Cidr
import no.skasti.yame.ppp.dns.PppDnsConfig
import no.skasti.yame.ppp.PppIpConfig
import no.skasti.yame.serial.SerialConnection
import no.skasti.yame.serial.SerialFlowControl
import no.skasti.yame.tone.DialString
import no.skasti.yame.tone.HandshakeProfile
import no.skasti.yame.ui.InteractiveYameApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    val configPath = configurationPath(args)
    val configStore = YameConfigurationStore(configPath)
    val informationalOnly = args.any { it == "--help" || it == "-h" || it == "--list" || it == "-l" }
    val savedConfiguration = if (informationalOnly) {
        YameConfiguration()
    } else {
        configStore.load()
    }
    val options = parseArgs(args, savedConfiguration, configPath)

    if (options.listPorts) {
        val ports = SerialConnection.availablePortDescriptors()
        if (ports.isEmpty()) {
            println("No serial ports found")
        } else {
            println("Available serial ports:")
            ports.forEach { port ->
                println("  ${port.systemPortName}\t${port.descriptivePortName}")
            }
        }
        return
    }

    val logManager = YameLogManager(options.logLevels, options.logDirectory, captureLimits = options.captureLimits)
    try {
        if (options.testNumber != null) {
            val modemFileLogger = logManager.logger(YameLogModule.MODEM)
            val modemConsoleLogger: (String) -> Unit = { message ->
                println(message)
                modemFileLogger(message)
            }
            val modem = HayesModem(
                baudRate = options.baudRate,
                config = configurationForToneTest(options.modemConfig),
                logger = modemConsoleLogger,
                pppLogger = logManager.logger(YameLogModule.PPP),
                dnsLogger = logManager.debugLogger(YameLogModule.DNS),
                transferLogger = logManager.debugLogger(YameLogModule.TRANSFERS),
                proxyLogger = logManager.debugLogger(YameLogModule.PROXY),
                eventSink = logManager::eventSink,
            )
            println(
                "Tone test. Dialing: ${options.testNumber} " +
                    "(pickup: ${options.modemConfig.pickupTime}, " +
                    "dial tone: ${options.modemConfig.dialToneTime}, " +
                    "handshake: ${options.modemConfig.handshakeProfile})...",
            )
            modem.use {
                it.dial(options.testNumber)
            }
            println("Tone test complete.")
            return
        }

        if (!Files.exists(options.configPath)) {
            runCatching { configStore.save(savedConfiguration) }
                .onFailure { error ->
                    System.err.println("YAME could not create ${options.configPath}: ${error.message}")
                }
        }
        runCatching { ElementSimplifierConfigurationStore.alongside(options.configPath).createDefaultIfMissing() }
            .onFailure { error ->
                System.err.println("YAME could not create element-simplifier.ini: ${error.message}")
            }

        val detectedTerminal = Terminal()
        val useTui = when (options.uiMode) {
            UiMode.AUTO ->
                detectedTerminal.terminalInfo.outputInteractive &&
                    detectedTerminal.terminalInfo.inputInteractive &&
                    detectedTerminal.terminalInfo.supportsAnsiCursor

            UiMode.TUI -> true
            UiMode.PLAIN -> false
        }

        val effectiveConfiguration = YameConfiguration(
            portName = options.portName, baudRate = options.baudRate, flowControl = options.flowControl,
            modemConfig = options.modemConfig, pppIpConfig = options.pppIpConfig,
            pppDnsConfig = options.pppDnsConfig, pppHttpCompatibilityConfig = options.httpConfig,
            captureLimits = options.captureLimits, logLevels = options.logLevels,
        )
        val runtimeConfigStore = no.skasti.yame.persistence.applicationConfigStore(
            savedConfiguration, effectiveConfiguration, configStore::save,
        )
        no.skasti.yame.config.ConfigStore.install(runtimeConfigStore).use {
            val functions = no.skasti.yame.function.FunctionStore()
            if (useTui) {
                val application = InteractiveYameApplication(
                    configStore = runtimeConfigStore,
                    functions = functions,
                    logManager = logManager,
                    terminal = if (options.uiMode == UiMode.AUTO) {
                        detectedTerminal
                    } else {
                        Terminal(interactive = true)
                    },
                )
                val shutdownHook = Thread(
                    application::close,
                    "yame-tui-shutdown",
                )
                Runtime.getRuntime().addShutdownHook(shutdownHook)
                try {
                    application.run()
                } finally {
                    application.close()
                    runCatching {
                        Runtime.getRuntime().removeShutdownHook(shutdownHook)
                    }
                }
                return
            }

            if (options.portName == null) {
                printUsage()
                return
            }

            val runtime = no.skasti.yame.runtime.YameRuntime(
                object : no.skasti.yame.runtime.RuntimeObserver {
                    override fun onLog(message: String) { println(message) }
                },
                runtimeConfigStore, functions,
                logManager = logManager, failFastStartup = true,
            )
            val hook = Thread(runtime::close, "yame-shutdown")
            Runtime.getRuntime().addShutdownHook(hook)
            try { runtime.run() } finally {
                runtime.close()
                runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            }
        }
    } finally {
        logManager.close()
    }
}

internal fun configurationForToneTest(configuration: HayesModemConfig): HayesModemConfig =
    configuration.copy(toneSimulationEnabled = true)

private enum class UiMode {
    AUTO,
    TUI,
    PLAIN,
}

private data class Options(
    val portName: String?,
    val baudRate: Int,
    val flowControl: SerialFlowControl,
    val listPorts: Boolean,
    val testNumber: String?,
    val uiMode: UiMode,
    val modemConfig: HayesModemConfig,
    val pppIpConfig: PppIpConfig,
    val pppDnsConfig: PppDnsConfig,
    val httpConfig: no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig,
    val logLevels: Map<YameLogModule, YameLogLevel>,
    val configPath: Path,
    val logDirectory: Path,
    val captureLimits: PacketCaptureLimits,
)

internal fun resolveLoginCredentials(
    savedUsername: String?,
    savedPassword: String?,
    usernameOverride: String?,
    passwordOverride: String?,
): Pair<String?, String?> {
    require((usernameOverride == null) == (passwordOverride == null)) {
        "Both --username and --password must be provided together"
    }
    return if (usernameOverride == null) {
        savedUsername to savedPassword
    } else {
        usernameOverride to passwordOverride
    }
}

private fun parseArgs(
    args: Array<String>,
    savedConfiguration: YameConfiguration,
    configPath: Path,
): Options {
    val defaults = savedConfiguration.modemConfig
    var port: String? = savedConfiguration.portName
    var baud = savedConfiguration.baudRate
    var flowControl = savedConfiguration.flowControl
    var list = false
    var testNumber: String? = null
    var pickupTime = defaults.pickupTime
    var dialToneTime = defaults.dialToneTime
    var handshakeProfile = defaults.handshakeProfile
    val toneSimulationEnabled = defaults.toneSimulationEnabled
    var usernameOverride: String? = null
    var passwordOverride: String? = null
    var pppSubnet: Ipv4Cidr? = savedConfiguration.pppIpConfig.configuredSubnet
    var dnsUpstream = savedConfiguration.pppDnsConfig.upstreamServer
    var httpCompatibilityEnabled = savedConfiguration.pppHttpCompatibilityConfig.enabled
    val logLevels = savedConfiguration.logLevels.toMutableMap()
    var uiMode = UiMode.AUTO
    var logDirectory = Path.of("logs")
    var captureLimits = savedConfiguration.captureLimits

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--port", "-p" -> {
                require(i + 1 < args.size) { "$arg requires a port name" }
                port = args[++i]
            }
            "--baud", "-b" -> {
                require(i + 1 < args.size) { "$arg requires a baud rate" }
                baud = args[++i].toInt()
            }
            "--flow-control" -> {
                require(i + 1 < args.size) {
                    "$arg requires disabled, xon-xoff, or hardware"
                }
                flowControl = SerialFlowControl.parse(args[++i])
            }
            "--list", "-l" -> list = true
            "--test-tone", "-t" -> {
                require(i + 1 < args.size) { "$arg requires a number to dial" }
                val value = args[++i]
                require(value.isNotBlank()) { "$arg requires a non-empty number to dial" }
                require(DialString.normalize(value).isNotEmpty()) {
                    "$arg requires a number containing at least one DTMF digit"
                }
                testNumber = value
            }
            "--pickup-time" -> {
                require(i + 1 < args.size) { "$arg requires a duration, e.g. 2s or 500ms" }
                pickupTime = parseDuration(args[++i], arg)
            }
            "--dial-tone-time" -> {
                require(i + 1 < args.size) { "$arg requires a duration, e.g. 500ms or 1s" }
                dialToneTime = parseDuration(args[++i], arg)
            }
            "--handshake-profile" -> {
                require(i + 1 < args.size) { "$arg requires a profile (${handshakeProfileNames()})" }
                handshakeProfile = parseHandshakeProfile(args[++i])
            }
            "--username" -> {
                require(i + 1 < args.size) { "$arg requires a username" }
                usernameOverride = args[++i]
            }
            "--password" -> {
                require(i + 1 < args.size) { "$arg requires a password" }
                passwordOverride = args[++i]
            }
            "--subnet" -> {
                require(i + 1 < args.size) { "$arg requires an IPv4 CIDR, e.g. 10.0.0.0/30" }
                pppSubnet = Ipv4Cidr.parse(args[++i])
            }
            "--dns-upstream" -> {
                require(i + 1 < args.size) { "$arg requires an IPv4 address, e.g. 8.8.8.8" }
                dnsUpstream = Ipv4Address.parse(args[++i])
            }
            "--http-https-proxy" -> httpCompatibilityEnabled = true
            "--no-http-https-proxy" -> httpCompatibilityEnabled = false
            "--loglevel-modem",
            "--loglevel-serial",
            "--loglevel-ppp",
            "--loglevel-dns",
            "--loglevel-proxy",
            "--loglevel-transfers",
            "--loglevel-unhandled" -> {
                require(i + 1 < args.size) { "$arg requires error, warn, info, or debug" }
                val module = logModuleForArgument(arg)
                logLevels[module] = YameLogLevel.parse(args[++i])
            }
            "--capture-limit-per-type", "--capture-limit-total" -> {
                val kind = PacketCaptureLimitKind.entries.single { "--${it.commandName}" == arg }
                require(i + 1 < args.size) { "$arg requires a positive integer" }
                captureLimits = kind.apply(captureLimits, kind.parse(args[++i]))
            }
            "--log-dir" -> {
                require(i + 1 < args.size) { "$arg requires a directory" }
                val value = args[++i]
                require(value.isNotBlank()) { "$arg requires a non-empty directory" }
                logDirectory = Path.of(value)
            }
            "--ui" -> {
                require(i + 1 < args.size) { "$arg requires auto, tui, or plain" }
                uiMode = parseUiMode(args[++i])
            }
            "--config" -> {
                require(i + 1 < args.size) { "$arg requires a path" }
                i++
            }
            "--help", "-h" -> {
                printUsage()
                kotlin.system.exitProcess(0)
            }
            else -> error("Unknown argument: $arg")
        }
        i++
    }

    val (username, password) = resolveLoginCredentials(
        savedUsername = defaults.username,
        savedPassword = defaults.password,
        usernameOverride = usernameOverride,
        passwordOverride = passwordOverride,
    )

    return Options(
        portName = port,
        baudRate = baud,
        flowControl = flowControl,
        listPorts = list,
        testNumber = testNumber,
        uiMode = uiMode,
        modemConfig = HayesModemConfig(
            pickupTime = pickupTime,
            dialToneTime = dialToneTime,
            handshakeProfile = handshakeProfile,
            toneSimulationEnabled = toneSimulationEnabled,
            username = username,
            password = password,
        ),
        pppIpConfig = PppIpConfig(configuredSubnet = pppSubnet),
        pppDnsConfig = PppDnsConfig(upstreamServer = dnsUpstream),
        httpConfig = savedConfiguration.pppHttpCompatibilityConfig.copy(enabled = httpCompatibilityEnabled),
        logLevels = logLevels.toMap(),
        configPath = configPath,
        logDirectory = logDirectory,
        captureLimits = captureLimits,
    )
}

private fun configurationPath(args: Array<String>): Path {
    var configuredPath: Path? = null
    var index = 0
    while (index < args.size) {
        if (args[index] == "--config") {
            require(index + 1 < args.size) { "--config requires a path" }
            configuredPath = Path.of(args[index + 1])
            index++
        }
        index++
    }

    val path = configuredPath ?: Path.of("yame.ini")
    return if (path.isAbsolute) path.normalize() else {
        Path.of(System.getProperty("user.dir")).resolve(path).normalize()
    }
}

private fun logModuleForArgument(argument: String): YameLogModule =
    when (argument) {
        "--loglevel-modem" -> YameLogModule.MODEM
        "--loglevel-serial" -> YameLogModule.SERIAL
        "--loglevel-ppp" -> YameLogModule.PPP
        "--loglevel-dns" -> YameLogModule.DNS
        "--loglevel-proxy" -> YameLogModule.PROXY
        "--loglevel-transfers" -> YameLogModule.TRANSFERS
        "--loglevel-unhandled" -> YameLogModule.UNHANDLED
        else -> error("Unknown log level argument: $argument")
    }

private fun parseUiMode(value: String): UiMode =
    when (value.lowercase()) {
        "auto" -> UiMode.AUTO
        "tui" -> UiMode.TUI
        "plain" -> UiMode.PLAIN
        else -> error("--ui must be auto, tui, or plain, got '$value'")
    }

private fun parseDuration(value: String, argument: String): Duration {
    val match = DURATION_PATTERN.matchEntire(value.lowercase())
        ?: error("$argument expects a duration such as 500ms, 2s, or 1.5s")

    val amount = match.groupValues[1].toDouble()
    require(amount.isFinite()) { "$argument must be finite" }
    require(amount >= 0.0) { "$argument must not be negative" }

    return when (match.groupValues[2]) {
        "ms" -> amount.milliseconds
        "s" -> amount.seconds
        else -> error("Unsupported duration unit")
    }
}

private fun parseHandshakeProfile(value: String): HandshakeProfile =
    HandshakeProfile.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: error("Unknown handshake profile '$value'. Available profiles: ${handshakeProfileNames()}")

private fun handshakeProfileNames(): String =
    HandshakeProfile.entries.joinToString(", ") { it.name.lowercase() }

private val DURATION_PATTERN = Regex("""(\d+(?:\.\d+)?)(ms|s)""")

private fun printUsage() {
    val defaults = HayesModemConfig()
    println(
        """
        YAME - Yet Another Modem Emulator

        Usage:
          yame --list
          yame --test-tone NUMBER [modem options]
          yame [--port PORT] [--baud 38400] [--ui auto|tui|plain] [modem options]

        Options:
          -l, --list                  List available serial ports
          -t, --test-tone NUM         Run the modem dialing sequence without a serial port
          -p, --port PORT             Serial port, e.g. COM3 or /dev/ttyUSB0
          -b, --baud RATE             Baud rate (default: 38400)
              --flow-control MODE    Serial flow control: disabled, xon-xoff, or hardware (default: disabled)
              --pickup-time DURATION  Ringback time before pickup (default: ${defaults.pickupTime})
              --dial-tone-time DUR    Dial-tone duration (default: ${defaults.dialToneTime})
              --handshake-profile P   Handshake profile: ${handshakeProfileNames()} (default: ${defaults.handshakeProfile.name.lowercase()})
              --username USER         Enable terminal login with this username
              --password PASS         Terminal login password (requires --username)
              --config FILE           Config file (default: yame.ini in the working directory)
              --subnet CIDR           PPP address pool, e.g. 10.0.0.0/30 (default: automatic)
              --dns-upstream IP       DNS server used by YAME's local DNS proxy (default: ${PppDnsConfig.DEFAULT_UPSTREAM_SERVER})
              --http-https-proxy      Enable HTTP/HTTPS compatibility proxy (default)
              --no-http-https-proxy   Disable compatibility proxy and use normal TCP forwarding
              --capture-limit-per-type N Maximum captures per packet type (default: 10)
              --capture-limit-total N Maximum captures across all types (default: 1000)
              --log-dir DIRECTORY     Log root (default: logs in the working directory)
              --loglevel-unhandled L  unhandled.log level: error|warn|info|debug (default: info)
              --loglevel-modem LEVEL  modem.log level: error|warn|info|debug (default: info)
              --loglevel-serial LEVEL serial.log level: error|warn|info|debug (default: info)
              --loglevel-ppp LEVEL    ppp.log level: error|warn|info|debug (default: info)
              --loglevel-dns LEVEL    dns.log level: error|warn|info|debug (default: info)
              --loglevel-proxy LEVEL  proxy.log level: error|warn|info|debug (default: info)
              --loglevel-transfers L  transfers.log level: error|warn|info|debug (default: info)
              --ui MODE               UI mode: auto, tui, or plain (default: auto)
          -h, --help                  Show this help

        The first usable address in --subnet is assigned to YAME and the second to the PPP client.
        Explicit PPP subnets must not overlap an active local IPv4 interface subnet.
        On an interactive terminal, --ui auto starts the YAME dashboard. It can start without
        --port and lets you select the serial port, baud rate, DNS upstream, and HTTP proxy
        through the / command palette. Use /login username:password there to configure
        terminal login credentials at runtime. Plain mode keeps the traditional line-oriented output.
        YAME advertises its local PPP address as DNS and forwards DNS queries to --dns-upstream.
        HTTP/HTTPS compatibility mode is enabled by default. TCP/80 requests are handled by YAME as
        HTTP: redirects are followed on the host, modern HTTPS/TLS is terminated there, and the legacy
        peer receives plain HTTP. Absolute HTTPS references in compatible text responses are rewritten
        to HTTP so subsequent navigation stays on the compatibility path. Use --no-http-https-proxy
        for transparent TCP/80 forwarding instead.
        Runtime logs are always written to separate files under logs/: modem.log, serial.log, ppp.log,
        dns.log, proxy.log, and transfers.log. Existing active logs are archived at startup using their
        original creation timestamp. Use the --loglevel-* options to tune each module independently.
        Durations accept milliseconds or seconds, e.g. 500ms, 2s, or 1.5s, up to 10s.
        Terminal login can be configured at startup with both --username and --password, or
        at runtime in the TUI with /login username:password.
        Tone progress is always logged while a dialing sequence is played.
        """.trimIndent(),
    )
}
