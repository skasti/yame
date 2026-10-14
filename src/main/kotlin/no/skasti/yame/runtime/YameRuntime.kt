package no.skasti.yame.runtime

import no.skasti.yame.config.*
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.serial.ConnectionFunctions
import no.skasti.yame.serial.SerialSettings
import no.skasti.yame.modem.ModemSettings
import no.skasti.yame.logging.LogSettings
import no.skasti.yame.ppp.PppSettings
import no.skasti.yame.ppp.dns.DnsSettings
import no.skasti.yame.ppp.proxy.ProxySettings
import no.skasti.yame.lifecycle.ApplicationFunctions
import no.skasti.yame.config.HtmlTransformerControl
import no.skasti.yame.logging.YameLogLevel
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.modem.HayesModem
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.ppp.RetroPppHandler
import no.skasti.yame.ppp.proxy.ResourceRegistryHooks
import no.skasti.yame.serial.SerialConnection
import no.skasti.yame.serial.SerialFlowControl

internal class YameRuntime(
    initialPortName: String?,
    initialBaud: Int,
    initialFlowControl: SerialFlowControl,
    initialModemConfig: HayesModemConfig,
    private val observer: RuntimeObserver,
    val configStore: ConfigStore,
    val functions: FunctionStore,
    private val resourceRegistryHooks: ResourceRegistryHooks = ResourceRegistryHooks(),
    private val logManager: YameLogManager,
    initialLogLevels: Map<YameLogModule, YameLogLevel> = YameLogManager.defaultLevels(),
    private val portProvider: () -> List<no.skasti.yame.serial.SerialPortDescriptor> =
        SerialConnection::availablePortDescriptors,
) : AutoCloseable {
    private val lock = Any()

    @Volatile
    private var running = true

    @Volatile
    private var started = false

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

    private val functionRegistrations = listOf(
        functions.register(ApplicationFunctions.Quit) { shutdown() },
        functions.register(ConnectionFunctions.Disconnect) { disconnect() },
        functions.register(ConnectionFunctions.Reconnect) { reconnect() },
        functions.register(ConnectionFunctions.RefreshPorts) { refreshPorts() },
    )
    private val transformerControl = HtmlTransformerControl(
        initialModemConfig.pppHttpCompatibilityConfig.htmlTransformers,
        configStore = configStore,
    )

    private var generation = 0
    private var connectionAttemptGeneration: Int? = null
    private var activeConnection: SerialConnection? = null
    private var activeModem: HayesModem? = null
    private var monitorThread: Thread? = null

    init {
        modemConfig = modemConfig.copy(pppHttpCompatibilityConfig = modemConfig.pppHttpCompatibilityConfig.copy(
            transformerControl = transformerControl,
        ))
    }

    fun run() {
        started = true
        refreshPorts()
        maybeAutoConnect()
        startMonitor()
        while (running) Thread.sleep(APP_POLL_MILLIS)
        monitorThread?.join(THREAD_JOIN_MILLIS)
    }

    private fun startMonitor() {
        if (monitorThread != null) return
        monitorThread = Thread(
            {
                while (running) {
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
            configStore.setTransient(SerialSettings.Port to candidate)
            observer.onLog("Auto-selected serial port $candidate")
        }

        startConnection(candidate)
    }

    private val configurationSubscription = configStore.onChange.subscribe(initial = true) { changes ->
        synchronized(lock) {
            changes[SerialSettings.Port]?.let { selectedPortName = it.value }
            changes[SerialSettings.Baud]?.let { selectedBaud = it.value }
            changes[SerialSettings.FlowControl]?.let { selectedFlowControl = it.value }
            val current = YameConfiguration(selectedPortName, selectedBaud, selectedFlowControl, modemConfig,
                logManager.captureLimits, initialLogLevels)
            modemConfig = applyConfigurationChanges(current, changes).modemConfig
            changes[ModemSettings.Tone]?.let { activeModem?.updateToneSimulationEnabled(it.value) }
            changes[ModemSettings.Login]?.let { login ->
                activeModem?.updateLoginCredentials(login.value.username, login.value.password)
            }
            LogSettings.Levels.forEach { (module, key) -> changes[key]?.let { logManager.setLevel(module, it.value) } }
            LogSettings.CaptureLimits.forEach { (kind, key) -> changes[key]?.let { logManager.setCaptureLimit(kind, it.value) } }
        }
        if (started && changes.any { it.key.group == SerialSettings.Group || it.key === DnsSettings.Upstream ||
                it.key === ProxySettings.Enabled || it.key === PppSettings.Subnet ||
                it.key === ModemSettings.PickupTime || it.key === ModemSettings.DialToneTime || it.key === ModemSettings.Handshake }) {
            autoConnectEnabled = true
            restartConnection()
        }
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
        val baud: Int
        val flowControl: SerialFlowControl
        val config: HayesModemConfig
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
            baud = selectedBaud
            flowControl = selectedFlowControl
            config = modemConfig
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
                    modem.updateLoginCredentials(modemConfig.username, modemConfig.password)
                    modem.updateToneSimulationEnabled(modemConfig.toneSimulationEnabled)
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
        configurationSubscription.close()
        functionRegistrations.forEach(AutoCloseable::close)
        transformerControl.close()
    }

    private companion object {
        const val APP_POLL_MILLIS = 100L
        const val PORT_SCAN_MILLIS = 2_000L
        const val THREAD_JOIN_MILLIS = 500L
    }
}
