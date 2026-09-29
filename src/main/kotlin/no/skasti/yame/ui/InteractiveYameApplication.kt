package no.skasti.yame.ui

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.config.YameConfiguration
import no.skasti.yame.logging.YameLogLevel
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.modem.HayesModem
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.ppp.RetroPppHandler
import no.skasti.yame.ppp.ip.Ipv4Address
import no.skasti.yame.ppp.proxy.ResourceRegistryHooks
import no.skasti.yame.serial.SerialConnection
import no.skasti.yame.serial.SerialFlowControl
import java.util.concurrent.Executors

class InteractiveYameApplication(
    initialPortName: String?,
    initialBaud: Int,
    initialFlowControl: SerialFlowControl,
    initialModemConfig: HayesModemConfig,
    terminal: Terminal,
    private val logManager: YameLogManager,
    initialLogLevels: Map<YameLogModule, YameLogLevel> = YameLogManager.defaultLevels(),
    private val onConfigurationChanged: (YameConfiguration) -> Unit = {},
    private val portProvider: () -> List<no.skasti.yame.serial.SerialPortDescriptor> =
        SerialConnection::availablePortDescriptors,
) : AutoCloseable {
    private val lock = Any()

    @Volatile
    private var running = true

    @Volatile
    private var autoConnectEnabled = true

    @Volatile
    private var selectedPortName: String? = initialPortName

    @Volatile
    private var selectedBaud: Int = initialBaud

    @Volatile
    private var selectedFlowControl: SerialFlowControl = initialFlowControl

    @Volatile
    private var modemConfig: HayesModemConfig = initialModemConfig

    private var generation = 0
    private var connectionAttemptGeneration: Int? = null
    private var activeConnection: SerialConnection? = null
    private var activeModem: HayesModem? = null
    private var monitorThread: Thread? = null

    private val resourceUiExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "yame-resource-ui").apply { isDaemon = true }
    }
    private val resourceRegistryHooks = ResourceRegistryHooks { task ->
        resourceUiExecutor.execute(task)
    }
    private val observer = TuiYameObserver(
        initialPortName = initialPortName,
        initialBaud = initialBaud,
        initialFlowControl = initialFlowControl,
        initialDnsUpstream = initialModemConfig.pppDnsConfig.upstreamServer.toString(),
        initialHttpProxyEnabled = initialModemConfig.pppHttpCompatibilityConfig.enabled,
        onQuit = ::shutdown,
        onPortSelected = ::selectPort,
        onBaudSelected = ::selectBaud,
        onFlowControlSelected = ::selectFlowControl,
        onDnsUpstreamSelected = ::selectDnsUpstream,
        onHttpProxySelected = ::selectHttpProxy,
        initialLogLevels = initialLogLevels,
        onLogLevelSelected = ::selectLogLevel,
        onLoginAdded = ::addLoginCredentials,
        onDisconnect = ::disconnect,
        onReconnect = ::reconnect,
        onRefreshPorts = ::refreshPorts,
        terminal = terminal,
    )

    init {
        resourceRegistryHooks.onRootAdded += observer::handleResourceRootAdded
        resourceRegistryHooks.onRootRemoved += observer::handleResourceRootRemoved
        resourceRegistryHooks.onRootUsed += observer::handleResourceRootUsed
        resourceRegistryHooks.onResourceAdded += observer::handleResourceAdded
        resourceRegistryHooks.onResourceUpdated += observer::handleResourceUpdated
        resourceRegistryHooks.onResourceRemoved += observer::handleResourceRemoved
    }

    fun run() {
        observer.use {
            observer.start()
            refreshPorts()
            maybeAutoConnect()
            startMonitor()

            while (running && !observer.stopRequested) {
                Thread.sleep(APP_POLL_MILLIS)
            }

            shutdown()
            monitorThread?.join(THREAD_JOIN_MILLIS)
        }
    }

    private fun startMonitor() {
        if (monitorThread != null) return
        monitorThread = Thread(
            {
                while (running && !observer.stopRequested) {
                    try {
                        if (!connectionIsActive()) {
                            refreshPorts()
                            maybeAutoConnect()
                        }
                    } catch (_: Exception) {
                        // Serial enumeration is advisory. Keep the dashboard alive.
                    }
                    Thread.sleep(PORT_SCAN_MILLIS)
                }
            },
            "yame-port-monitor",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun refreshPorts() {
        observer.updateAvailablePorts(
            runCatching(portProvider).getOrDefault(emptyList()),
        )
    }

    private fun maybeAutoConnect() {
        if (!running || !autoConnectEnabled || connectionIsActive()) return

        val ports = runCatching(portProvider).getOrDefault(emptyList())
        observer.updateAvailablePorts(ports)

        val desired = selectedPortName
        val candidate = when {
            desired != null ->
                ports.firstOrNull {
                    it.systemPortName.equals(desired, ignoreCase = true)
                }?.systemPortName ?: desired

            ports.size == 1 -> ports.single().systemPortName
            else -> null
        }

        if (candidate == null) {
            observer.updateConnected(false)
            return
        }

        if (selectedPortName == null) {
            selectedPortName = candidate
            observer.onLog("Auto-selected serial port $candidate")
            updateObserverSettings()
        }

        startConnection(candidate)
    }

    private fun selectPort(portName: String) {
        selectedPortName = portName
        autoConnectEnabled = true
        persistConfiguration()
        updateObserverSettings()
        restartConnection()
    }

    private fun selectBaud(baud: Int) {
        selectedBaud = baud
        autoConnectEnabled = true
        persistConfiguration()
        updateObserverSettings()
        restartConnection()
    }

    private fun selectFlowControl(flowControl: SerialFlowControl) {
        selectedFlowControl = flowControl
        autoConnectEnabled = true
        persistConfiguration()
        updateObserverSettings()
        restartConnection()
    }

    private fun selectDnsUpstream(value: String) {
        val address = runCatching { Ipv4Address.parse(value) }
            .getOrElse {
                observer.onLog("DNS upstream rejected: '$value' is not a valid IPv4 address")
                return
            }

        modemConfig = modemConfig.copy(
            pppDnsConfig = modemConfig.pppDnsConfig.copy(
                upstreamServer = address,
            ),
        )
        persistConfiguration()
        observer.onLog("DNS upstream changed to $address; reconnecting")
        autoConnectEnabled = true
        updateObserverSettings()
        restartConnection()
    }

    private fun selectHttpProxy(enabled: Boolean) {
        modemConfig = modemConfig.copy(
            pppHttpCompatibilityConfig = modemConfig.pppHttpCompatibilityConfig.copy(
                enabled = enabled,
            ),
        )
        persistConfiguration()
        observer.onLog(
            "HTTP/HTTPS compatibility proxy ${if (enabled) "enabled" else "disabled"}; reconnecting",
        )
        autoConnectEnabled = true
        updateObserverSettings()
        restartConnection()
    }

    private fun selectLogLevel(module: YameLogModule, level: YameLogLevel) {
        logManager.setLevel(module, level)
        observer.updateLogLevel(module, level)
        persistConfiguration()
        observer.onLog("Log level for ${module.fileName} changed to ${level.name.lowercase()}")
    }

    private fun addLoginCredentials(username: String, password: String) {
        val updatedConfig = runCatching {
            modemConfig.copy(username = username, password = password)
        }.getOrElse { error ->
            observer.onLog("Terminal login rejected: ${error.message}")
            return
        }

        modemConfig = updatedConfig
        val modem = synchronized(lock) { activeModem }
        modem?.updateLoginCredentials(username, password)
        persistConfiguration()
        observer.onLog(
            "Terminal login configured for '$username'; applies to the next modem call",
        )
    }

    private fun reconnect() {
        autoConnectEnabled = true
        restartConnection()
    }

    private fun restartConnection() {
        stopActiveConnection()
        maybeAutoConnect()
    }

    private fun disconnect() {
        autoConnectEnabled = false
        stopActiveConnection()
        observer.updateConnected(false)
        observer.onLog("Serial connection closed")
    }

    private fun startConnection(portName: String) {
        if (!running || !autoConnectEnabled) return

        val connectionGeneration: Int
        val baud = selectedBaud
        val flowControl = selectedFlowControl
        val config = modemConfig
        synchronized(lock) {
            if (
                activeConnection != null ||
                activeModem != null ||
                connectionAttemptGeneration != null
            ) {
                return
            }
            generation++
            connectionGeneration = generation
            connectionAttemptGeneration = connectionGeneration
        }

        val modemFileLogger = logManager.logger(YameLogModule.MODEM)
        val pppFileLogger = logManager.logger(YameLogModule.PPP)
        val dnsFileLogger = logManager.debugLogger(YameLogModule.DNS)
        val transferFileLogger = logManager.debugLogger(YameLogModule.TRANSFERS)
        val proxyFileLogger = logManager.debugLogger(YameLogModule.PROXY)
        val serialFileLogger = logManager.logger(YameLogModule.SERIAL)
        val modemLogger: (String) -> Unit = { message ->
            observer.onLog(message)
            modemFileLogger(message)
        }
        val pppLogger: (String) -> Unit = { message ->
            observer.onLog(message)
            pppFileLogger(message)
        }
        val dnsLogger: (String) -> Unit = { message ->
            observer.onLog(message)
            dnsFileLogger(message)
        }
        val transferLogger: (String) -> Unit = { message ->
            observer.onLog(message)
            transferFileLogger(message)
        }
        val eventSink: (no.skasti.yame.observer.YameEvent) -> Unit = { event ->
            logManager.eventSink(event)
            observer.onEvent(event)
        }
        val pppHandler = RetroPppHandler(
            logger = pppLogger,
            dnsLogger = dnsLogger,
            transferLogger = transferLogger,
            proxyLogger = proxyFileLogger,
            eventSink = eventSink,
            resourceRegistryHooks = resourceRegistryHooks,
            ipConfig = config.pppIpConfig,
            dnsConfig = config.pppDnsConfig,
            httpCompatibilityConfig = config.pppHttpCompatibilityConfig,
        )
        val modem = HayesModem(
            baudRate = baud,
            config = config,
            logger = modemLogger,
            pppLogger = pppLogger,
            dnsLogger = dnsLogger,
            transferLogger = transferLogger,
            proxyLogger = proxyFileLogger,
            eventSink = eventSink,
            pppHandler = pppHandler,
        )
        val connection = SerialConnection(
            portName = portName,
            baudRate = baud,
            flowControl = flowControl,
            logger = { message ->
                observer.onLog(message)
                serialFileLogger(message)
            },
        )

        try {
            connection.open()
            modem.attachOutput(connection.output)
            modem.attachCarrierPresent(connection::setCarrierPresent)

            val current = synchronized(lock) {
                val isCurrent =
                    running &&
                        generation == connectionGeneration &&
                        connectionAttemptGeneration == connectionGeneration

                if (isCurrent) {
                    activeModem = modem
                    activeConnection = connection
                    connectionAttemptGeneration = null
                }
                isCurrent
            }

            if (!current) {
                runCatching { modem.close() }
                runCatching { connection.close() }
                synchronized(lock) {
                    if (connectionAttemptGeneration == connectionGeneration) {
                        connectionAttemptGeneration = null
                    }
                }
                return
            }

            observer.updateConnected(true)
            observer.onLog("YAME ready on $portName at $baud baud")

            connection.startReading(
                onBytes = modem::receive,
                onStopped = { error ->
                    serialReaderStopped(
                        connectionGeneration = connectionGeneration,
                        connection = connection,
                        modem = modem,
                        error = error,
                    )
                },
            )
        } catch (error: Exception) {
            runCatching { modem.close() }
            runCatching { connection.close() }

            val current = synchronized(lock) {
                val ownsAttempt =
                    connectionAttemptGeneration == connectionGeneration
                val ownsActiveConnection =
                    activeConnection === connection &&
                        activeModem === modem
                val isCurrent =
                    generation == connectionGeneration &&
                        (ownsAttempt || ownsActiveConnection)

                if (ownsAttempt) {
                    connectionAttemptGeneration = null
                }
                if (ownsActiveConnection) {
                    activeModem = null
                    activeConnection = null
                }
                isCurrent
            }

            if (current) {
                observer.updateConnected(false)
                observer.onLog(
                    "Could not open $portName: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    private fun serialReaderStopped(
        connectionGeneration: Int,
        connection: SerialConnection,
        modem: HayesModem,
        error: Throwable?,
    ) {
        val current = synchronized(lock) {
            if (
                generation != connectionGeneration ||
                activeConnection !== connection ||
                activeModem !== modem
            ) {
                false
            } else {
                activeConnection = null
                activeModem = null
                true
            }
        }
        if (!current) return

        runCatching { modem.close() }
        runCatching { connection.close() }
        observer.closeActiveTransfers("serial session stopped")
        observer.updateConnected(false)
        if (error != null) {
            observer.onLog(
                "Serial connection lost: ${error.message ?: error.javaClass.simpleName}",
            )
        } else if (running && autoConnectEnabled) {
            observer.onLog("Serial connection stopped")
        }
    }

    private fun stopActiveConnection() {
        val connection: SerialConnection?
        val modem: HayesModem?
        synchronized(lock) {
            generation++
            connection = activeConnection
            modem = activeModem
            activeConnection = null
            activeModem = null
        }

        runCatching { modem?.close() }
        runCatching { connection?.close() }
        observer.closeActiveTransfers("serial session stopped")
        observer.updateConnected(false)
    }

    private fun updateObserverSettings() {
        observer.updateSettings(
            portName = selectedPortName,
            baud = selectedBaud,
            flowControl = selectedFlowControl,
            dnsUpstream = modemConfig.pppDnsConfig.upstreamServer.toString(),
            httpProxyEnabled = modemConfig.pppHttpCompatibilityConfig.enabled,
        )
    }

    private fun persistConfiguration() {
        val configuration = YameConfiguration(
            portName = selectedPortName,
            baudRate = selectedBaud,
            flowControl = selectedFlowControl,
            modemConfig = modemConfig,
            logLevels = YameLogModule.entries.associateWith(logManager::level),
        )
        runCatching { onConfigurationChanged(configuration) }
            .onFailure { error ->
                observer.onLog("Could not save yame.ini: ${error.message ?: error.javaClass.simpleName}")
            }
    }

    private fun connectionIsActive(): Boolean =
        synchronized(lock) {
            connectionAttemptGeneration != null ||
                (activeConnection != null && activeModem != null)
        }

    private fun shutdown() {
        if (!running) return
        running = false
        autoConnectEnabled = false
        stopActiveConnection()
    }

    override fun close() {
        shutdown()
        observer.close()
        resourceUiExecutor.shutdownNow()
    }

    private companion object {
        const val APP_POLL_MILLIS = 100L
        const val PORT_SCAN_MILLIS = 2_000L
        const val THREAD_JOIN_MILLIS = 500L
    }
}
