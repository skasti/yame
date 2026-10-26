package no.skasti.yame.runtime

import no.skasti.yame.config.ConfigStore
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.serial.ConnectionFunctions
import no.skasti.yame.serial.SerialSettings
import no.skasti.yame.modem.ModemSettings
import no.skasti.yame.logging.LogSettings
import no.skasti.yame.ppp.PppSettings
import no.skasti.yame.ppp.dns.DnsSettings
import no.skasti.yame.ppp.proxy.ProxySettings
import no.skasti.yame.lifecycle.ApplicationFunctions
import no.skasti.yame.ppp.proxy.transform.HtmlTransformerSettings
import no.skasti.yame.ppp.PppIpConfig
import no.skasti.yame.ppp.dns.PppDnsConfig
import no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.serial.SerialTransport
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.modem.HayesModem
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.ppp.RetroPppHandler
import no.skasti.yame.ppp.proxy.YameRegistries
import no.skasti.yame.serial.SerialConnection
import no.skasti.yame.serial.SerialFlowControl
import no.skasti.yame.serial.SerialPortDescriptor
import no.skasti.yame.serial.AvailablePortsChanged
import no.skasti.yame.serial.ConnectionIds
import no.skasti.yame.serial.ConnectionState
import no.skasti.yame.serial.ConnectionStateChanged
import no.skasti.yame.observer.YameEvents
import no.skasti.yame.logging.DiagnosticMessage
import no.skasti.yame.logging.diagnosticLogger
import java.util.Collections

internal class YameRuntime(
    val configStore: ConfigStore = ConfigStore.current,
    val functions: FunctionStore = FunctionStore.current,
    private val logManager: YameLogManager,
    private val resourceGraphs: NavigationResourceRegistry =
        YameRegistries.navigationResources(
            PppHttpCompatibilityConfig.fromConfigStore(configStore),
        ),
    private val portProvider: () -> List<no.skasti.yame.serial.SerialPortDescriptor> =
        SerialConnection::availablePortDescriptors,
    private val failFastStartup: Boolean = false,
    private val connectionFactory: (String, Int, SerialFlowControl, (String) -> Unit) -> SerialTransport = ::SerialConnection,
) : AutoCloseable {
    private val lock = java.lang.Object()
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private var pendingTransitions = 0

    @Volatile
    private var running = true

    @Volatile
    private var started = false

    @Volatile
    private var autoConnectEnabled = true

    // Initial logging subscription must include its definition even for a fresh lazy store.
    init { configStore[LogSettings.Levels.getValue(YameLogModule.MODEM)] }

    private val selectedPortName: String? get() = configStore[SerialSettings.Port]

    private var generation = 0
    private var connectionId = 0L
    private var lastPorts: List<SerialPortDescriptor>? = null
    private var connectionAttemptGeneration: Int? = null
    private var activeConnection: SerialTransport? = null
    private var activeModem: HayesModem? = null
    private var monitorThread: Thread? = null

    fun run() {
        if (closed.get()) return
        started = true
        refreshPorts()
        maybeAutoConnect(failFast = failFastStartup)
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
                    try { Thread.sleep(PORT_SCAN_MILLIS) } catch (_: InterruptedException) { return@Thread }
                }
            },
            "yame-port-monitor",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun refreshPorts(): List<SerialPortDescriptor> = synchronized(lock) {
        if (closed.get()) return@synchronized lastPorts ?: emptyList()
        val ports = Collections.unmodifiableList(ArrayList(runCatching(portProvider).getOrDefault(emptyList())))
        if (ports != lastPorts) {
            lastPorts = ports
            YameEvents.publish(AvailablePortsChanged(ports))
        }
        ports
    }

    private fun maybeAutoConnect(failFast: Boolean = false) {
        if (!running || !autoConnectEnabled || connectionIsActive()) return

        val ports = refreshPorts()

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
            observeConnection(ConnectionState.DISCONNECTED)
            return
        }

        if (selectedPortName == null) {
            configStore.setTransient(SerialSettings.Port to candidate)
            observeLog("Auto-selected serial port $candidate")
        }

        startConnection(candidate, failFast)
    }

    private val configurationSubscription = configStore.onChange.subscribe(initial = true) { changes ->
        runtimeOperation {
            synchronized(lock) {
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
    }

    private val functionRegistrations = mutableListOf<AutoCloseable>()

    init {
        // Input is already active. Initialize all runtime state and listeners
        // before publishing callbacks that another thread can invoke at once.
        try {
            functionRegistrations += functions.register(ApplicationFunctions.Quit) { running = false; autoConnectEnabled = false }
            functionRegistrations += functions.register(ConnectionFunctions.Disconnect) { runtimeOperation { disconnect() } }
            functionRegistrations += functions.register(ConnectionFunctions.Reconnect) { runtimeOperation { reconnect() } }
            functionRegistrations += functions.register(ConnectionFunctions.RefreshPorts) { runtimeOperation { refreshPorts() } ?: emptyList() }
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    private fun reconnect() {
        if (closed.get()) return
        autoConnectEnabled = true
        restartConnection()
    }

    private fun restartConnection() {
        stopActiveConnection()
        maybeAutoConnect()
    }

    private fun disconnect() {
        if (closed.get()) return
        autoConnectEnabled = false
        stopActiveConnection()
        observeConnection(ConnectionState.DISCONNECTED)
        observeLog("Serial connection closed")
    }

    private fun startConnection(portName: String, failFast: Boolean) {
        if (!running || !autoConnectEnabled) return

        val observedGeneration = synchronized(lock) { generation }
        val baud = configStore[SerialSettings.Baud]
        val flowControl = configStore[SerialSettings.FlowControl]
        val config = HayesModemConfig.fromConfigStore(configStore)
        val ipConfig = PppIpConfig.fromConfigStore(configStore)
        val dnsConfig = PppDnsConfig.fromConfigStore(configStore)
        val httpConfig = PppHttpCompatibilityConfig.fromConfigStore(configStore)

        val connectionGeneration: Int
        val connectionIdentity: Long
        synchronized(lock) {
            if (
                !running || generation != observedGeneration ||
                activeConnection != null ||
                activeModem != null ||
                connectionAttemptGeneration != null
            ) {
                return
            }
            generation++
            connectionGeneration = generation
            connectionIdentity = ConnectionIds.next()
            connectionId = connectionIdentity
            YameEvents.publish(ConnectionStateChanged(connectionIdentity, ConnectionState.CONNECTING))
            connectionAttemptGeneration = connectionGeneration
            pendingTransitions++
        }

        try {
            val modemLogger = diagnosticLogger(logManager.logger(YameLogModule.MODEM))
            val pppLogger = diagnosticLogger(logManager.logger(YameLogModule.PPP))
            val dnsLogger = diagnosticLogger(logManager.debugLogger(YameLogModule.DNS))
            val transferLogger = diagnosticLogger(logManager.debugLogger(YameLogModule.TRANSFERS))
            val proxyLogger = diagnosticLogger(logManager.debugLogger(YameLogModule.PROXY))
            val serialLogger = diagnosticLogger(logManager.logger(YameLogModule.SERIAL))
            val pppHandler = RetroPppHandler(
                connectionId = connectionIdentity,
                logger = pppLogger,
                dnsLogger = dnsLogger,
                transferLogger = transferLogger,
                proxyLogger = proxyLogger,
                diagnosticSink = logManager::eventSink,
                resourceGraphs = resourceGraphs,
                ipConfig = ipConfig,
                dnsConfig = dnsConfig,
                httpCompatibilityConfig = httpConfig,
                transformerSettings = { HtmlTransformerSettings.fromConfigStore(configStore) },
            )
            val modem = HayesModem(
                baudRate = baud,
                config = config,
                logger = modemLogger,
                pppLogger = pppLogger,
                dnsLogger = dnsLogger,
                transferLogger = transferLogger,
                proxyLogger = proxyLogger,
                diagnosticSink = logManager::eventSink,
                pppHandler = pppHandler,
            )
            var connection: SerialTransport? = null
            try {
                val opened = connectionFactory(portName, baud, flowControl, serialLogger)
                connection = opened
                opened.open()
                modem.attachOutput(opened.output)
                modem.attachCarrierPresent(opened::setCarrierPresent)

                var connectedObservation: ConnectionStateChanged? = null
                val current = synchronized(lock) {
                    val isCurrent =
                        running &&
                            generation == connectionGeneration &&
                            connectionAttemptGeneration == connectionGeneration

                    if (isCurrent) {
                        val latestConfig = HayesModemConfig.fromConfigStore(configStore)
                        modem.updateLoginCredentials(latestConfig.username, latestConfig.password)
                        modem.updateToneSimulationEnabled(latestConfig.toneSimulationEnabled)
                        activeModem = modem
                        activeConnection = opened
                        connectionAttemptGeneration = null
                        connectedObservation = ConnectionStateChanged(connectionIdentity, ConnectionState.CONNECTED)
                    }
                    isCurrent
                }

                if (!current) {
                    runCatching { modem.close() }
                    runCatching { opened.close() }
                    synchronized(lock) {
                        if (connectionAttemptGeneration == connectionGeneration) {
                            connectionAttemptGeneration = null
                        }
                    }
                    return
                }

                YameEvents.publish(requireNotNull(connectedObservation))
                observeLog("YAME ready on $portName at $baud baud")

                opened.startReading(
                    onBytes = modem::receive,
                    onStopped = { error ->
                        serialReaderStopped(
                            connectionGeneration = connectionGeneration,
                            connectionIdentity = connectionIdentity,
                            connection = opened,
                            modem = modem,
                            error = error,
                        )
                    },
                )
            } catch (error: Exception) {
                runCatching { modem.close() }
                runCatching { connection?.close() }

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
                    YameEvents.publish(ConnectionStateChanged(connectionIdentity, ConnectionState.DISCONNECTED))
                    observeLog(
                        "Could not open $portName: ${error.message ?: error.javaClass.simpleName}",
                    )
                    if (failFast) throw error
                }
            }
        } finally {
            synchronized(lock) { pendingTransitions--; lock.notifyAll() }
        }
    }

    private fun serialReaderStopped(
        connectionGeneration: Int,
        connectionIdentity: Long,
        connection: SerialTransport,
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
                pendingTransitions++
                true
            }
        }
        if (!current) return

        try {
            runCatching { modem.close() }
            runCatching { connection.close() }
            YameEvents.publish(ConnectionStateChanged(connectionIdentity, ConnectionState.DISCONNECTED))
            if (error != null) {
                observeLog(
                    "Serial connection lost: ${error.message ?: error.javaClass.simpleName}",
                )
            } else if (running && autoConnectEnabled) {
                observeLog("Serial connection stopped")
            }
        } finally {
            synchronized(lock) { pendingTransitions--; lock.notifyAll() }
        }
    }

    private fun stopActiveConnection() {
        val connection: SerialTransport?
        val modem: HayesModem?
        val ended: ConnectionStateChanged
        synchronized(lock) {
            generation++
            ended = ConnectionStateChanged(connectionId, ConnectionState.DISCONNECTED)
            connection = activeConnection
            modem = activeModem
            activeConnection = null
            activeModem = null
            connectionAttemptGeneration = null
        }

        var cleanupFailure: Throwable? = null
        try { connection?.close() } catch (error: Throwable) { cleanupFailure = error }
        try { modem?.close() } catch (error: Throwable) { cleanupFailure = cleanupFailure ?: error }
        YameEvents.publish(ended)
        cleanupFailure?.let { throw it }
    }

    private fun connectionIsActive(): Boolean =
        synchronized(lock) {
            connectionAttemptGeneration != null ||
                (activeConnection != null && activeModem != null)
        }

    private fun <T> runtimeOperation(action: () -> T): T? {
        synchronized(lock) {
            if (closed.get()) return null
            pendingTransitions++
        }
        try { return action() } finally {
            synchronized(lock) { pendingTransitions--; lock.notifyAll() }
        }
    }

    private fun observeLog(message: String) { YameEvents.publish(DiagnosticMessage(message)) }

    private fun observeConnection(state: ConnectionState) = synchronized(lock) {
        YameEvents.publish(ConnectionStateChanged(connectionId, state))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running = false
        autoConnectEnabled = false
        configurationSubscription.close()
        functionRegistrations.forEach(AutoCloseable::close)
        monitorThread?.interrupt()
        if (Thread.currentThread() !== monitorThread) monitorThread?.join(THREAD_JOIN_MILLIS)
        try { stopActiveConnection() } finally {
            synchronized(lock) {
                while (pendingTransitions > 0) lock.wait()
            }
            if (Thread.currentThread() !== monitorThread) {
                monitorThread?.join(THREAD_JOIN_MILLIS)
                check(monitorThread?.isAlive != true) { "Port monitor did not stop" }
            }
        }
    }

    private companion object {
        const val APP_POLL_MILLIS = 100L
        const val PORT_SCAN_MILLIS = 2_000L
        const val THREAD_JOIN_MILLIS = 500L
    }
}
