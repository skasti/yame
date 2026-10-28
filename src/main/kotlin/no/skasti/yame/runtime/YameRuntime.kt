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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class YameRuntime(
    val functions: FunctionStore = FunctionStore.current,
    private val logManager: YameLogManager,
    private val resourceGraphs: NavigationResourceRegistry =
        YameRegistries.navigationResources(
            PppHttpCompatibilityConfig.fromConfigStore(),
        ),
    private val portProvider: () -> List<no.skasti.yame.serial.SerialPortDescriptor> =
        SerialConnection::availablePortDescriptors,
    private val failFastStartup: Boolean = false,
    private val connectionFactory: (String, Int, SerialFlowControl, (String) -> Unit) -> SerialTransport = ::SerialConnection,
) : AutoCloseable {
    private val lock = java.lang.Object()
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private val closeCompleted = CountDownLatch(1)
    private var closingThread: Thread? = null
    @Volatile private var closeFailure: Throwable? = null
    // Resource work only: port scans, connection initialization and detached cleanup.
    private var pendingTransitions = 0
    private val transitionDepth = ThreadLocal.withInitial { 0 }

    @Volatile
    private var running = false

    @Volatile
    private var started = false

    @Volatile
    private var autoConnectEnabled = true

    // Initial logging subscription must include its definition even for a fresh lazy store.
    init { ConfigStore[LogSettings.Levels.getValue(YameLogModule.MODEM)] }

    private val selectedPortName: String? get() = ConfigStore[SerialSettings.Port]

    private var generation = 0
    private var connectionId = 0L
    private var lastPorts: List<SerialPortDescriptor>? = null
    private var connectionAttemptGeneration: Int? = null
    private var activeConnection: SerialTransport? = null
    private var activeModem: HayesModem? = null
    private var monitorThread: Thread? = null
    private val functionRegistrations = mutableListOf<AutoCloseable>()

    fun run() {
        synchronized(lock) {
            check(!started && !closed.get()) { "Runtime has already started or closed" }
            started = true
            running = true
        }
        var failure: Throwable? = null
        try {
            synchronized(lock) {
                if (closed.get()) return
                try {
                    functionRegistrations += functions.register(ApplicationFunctions.Quit) { quit() }
                    functionRegistrations += functions.register(ConnectionFunctions.Disconnect) { disconnect() }
                    functionRegistrations += functions.register(ConnectionFunctions.Reconnect) { reconnect() }
                    functionRegistrations += functions.register(ConnectionFunctions.RefreshPorts) { refreshPorts() }
                } catch (error: Throwable) {
                    running = false
                    throw error
                }
            }
            scanPorts()
            maybeAutoConnect(failFast = failFastStartup)
            startMonitor()
            while (running) Thread.sleep(APP_POLL_MILLIS)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { close() } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }

    private fun startMonitor() = synchronized(lock) {
        if (!running || closed.get() || monitorThread != null) return@synchronized
        monitorThread = Thread(
            {
                while (running) {
                    try {
                        if (!connectionIsActive()) {
                            scanPorts()
                            maybeAutoConnect()
                        }
                    } catch (_: Exception) {
                        // Serial enumeration is advisory. Keep the dashboard alive.
                    }
                    if (!running) return@Thread
                    try { Thread.sleep(PORT_SCAN_MILLIS) } catch (_: InterruptedException) { return@Thread }
                }
            },
            "yame-port-monitor",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun refreshPorts(): List<SerialPortDescriptor> {
        synchronized(lock) { checkCommandState() }
        return checkNotNull(scanPorts()) { "Runtime stopped during port enumeration" }
    }

    /** Internal cancellation is ordinary shutdown; external refresh reports it as an error. */
    private fun scanPorts(): List<SerialPortDescriptor>? {
        synchronized(lock) {
            if (!running || closed.get()) return null
            beginTransition()
        }
        try {
            // Enumeration may perform OS IO or call an injected provider. Never hold the runtime lock.
            val ports = Collections.unmodifiableList(ArrayList(runCatching(portProvider).getOrDefault(emptyList())))
            return synchronized(lock) {
                if (!running || closed.get()) return@synchronized null
                if (ports != lastPorts) {
                    lastPorts = ports
                    YameEvents.publish(AvailablePortsChanged(ports))
                }
                ports
            }
        } finally { finishTransition() }
    }

    private fun checkCommandState() {
        check(started && running && !closed.get()) { "Runtime is not running" }
    }

    private fun quit() = synchronized(lock) {
        checkCommandState()
        running = false
        autoConnectEnabled = false
    }

    private fun reconnect() {
        val previous = synchronized(lock) {
            checkCommandState()
            autoConnectEnabled = true
            detachConnection()
        }
        releaseConnection(previous)
        maybeAutoConnect()
    }

    private fun maybeAutoConnect(failFast: Boolean = false) {
        if (!running || !autoConnectEnabled || connectionIsActive()) return

        val ports = scanPorts() ?: return

        val desired = synchronized(lock) {
            if (!running || closed.get()) return
            selectedPortName
        }
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

        startConnection(candidate, failFast)
    }

    private val configurationSubscription = ConfigStore.onChange.subscribe(initial = true) { changes ->
        val restart = synchronized(lock) {
            if (closed.get()) return@subscribe
            changes[ModemSettings.Tone]?.let { activeModem?.updateToneSimulationEnabled(it.value) }
            changes[ModemSettings.Login]?.let { login ->
                activeModem?.updateLoginCredentials(login.value.username, login.value.password)
            }
            LogSettings.Levels.forEach { (module, key) -> changes[key]?.let { logManager.setLevel(module, it.value) } }
            LogSettings.CaptureLimits.forEach { (kind, key) -> changes[key]?.let { logManager.setCaptureLimit(kind, it.value) } }
            val restart = started && running && changes.any { it.key.group == SerialSettings.Group || it.key === DnsSettings.Upstream ||
                    it.key === ProxySettings.Enabled || it.key === PppSettings.Subnet ||
                    it.key === ModemSettings.PickupTime || it.key === ModemSettings.DialToneTime || it.key === ModemSettings.Handshake }
            if (restart) autoConnectEnabled = true
            restart
        }
        if (restart) restartConnection()
    }

    private fun restartConnection() {
        val previous = synchronized(lock) {
            if (!running || closed.get()) return
            detachConnection()
        }
        releaseConnection(previous)
        maybeAutoConnect()
    }

    private fun disconnect() {
        val previous = synchronized(lock) {
            checkCommandState()
            autoConnectEnabled = false
            detachConnection()
        }
        releaseConnection(previous, "Serial connection closed")
    }

    private fun startConnection(portName: String, failFast: Boolean) {
        val observedGeneration = synchronized(lock) {
            if (!running || closed.get() || !autoConnectEnabled || activeConnection != null ||
                activeModem != null || connectionAttemptGeneration != null) return
            beginTransition()
            generation
        }

        var claimedGeneration: Int? = null
        var claimedIdentity: Long? = null
        var modem: HayesModem? = null
        var connection: SerialTransport? = null
        var handedOff = false
        try {
            // Store notifications can synchronously restart connection work.
            // Let that work win, then reject this attempt by its old generation.
            if (selectedPortName == null) {
                ConfigStore.setTransient(SerialSettings.Port to portName)
                observeLog("Auto-selected serial port $portName")
            }
            val attempt = synchronized(lock) {
                if (!running || closed.get() || !autoConnectEnabled || generation != observedGeneration ||
                    activeConnection != null || connectionAttemptGeneration != null) return
                generation++
                connectionId = ConnectionIds.next()
                connectionAttemptGeneration = generation
                claimedGeneration = generation
                claimedIdentity = connectionId
                YameEvents.publish(ConnectionStateChanged(connectionId, ConnectionState.CONNECTING))
                generation to connectionId
            }
            val connectionGeneration = attempt.first
            val connectionIdentity = attempt.second
            val baud = ConfigStore[SerialSettings.Baud]
            val flowControl = ConfigStore[SerialSettings.FlowControl]
            val config = HayesModemConfig.fromConfigStore()
            val ipConfig = PppIpConfig.fromConfigStore()
            val dnsConfig = PppDnsConfig.fromConfigStore()
            val httpConfig = PppHttpCompatibilityConfig.fromConfigStore()
            val modemLogger = diagnosticLogger(logManager.logger(YameLogModule.MODEM))
            val pppLogger = diagnosticLogger(logManager.logger(YameLogModule.PPP))
            val dnsLogger = diagnosticLogger(logManager.debugLogger(YameLogModule.DNS))
            val transferLogger = diagnosticLogger(logManager.debugLogger(YameLogModule.TRANSFERS))
            val proxyLogger = diagnosticLogger(logManager.debugLogger(YameLogModule.PROXY))
            val serialLogger = diagnosticLogger(logManager.logger(YameLogModule.SERIAL))
            val pppHandler = RetroPppHandler(
                connectionId = connectionIdentity,
                logger = pppLogger, dnsLogger = dnsLogger, transferLogger = transferLogger,
                proxyLogger = proxyLogger, diagnosticSink = logManager::eventSink,
                resourceGraphs = resourceGraphs, ipConfig = ipConfig, dnsConfig = dnsConfig,
                httpCompatibilityConfig = httpConfig,
                transformerSettings = { HtmlTransformerSettings.fromConfigStore() },
            )
            val createdModem = try {
                HayesModem(
                    baudRate = baud, config = config, logger = modemLogger, pppLogger = pppLogger,
                    dnsLogger = dnsLogger, transferLogger = transferLogger, proxyLogger = proxyLogger,
                    diagnosticSink = logManager::eventSink, pppHandler = pppHandler,
                )
            } catch (error: Throwable) {
                try { pppHandler.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
            modem = createdModem
            val opened = connectionFactory(portName, baud, flowControl, serialLogger)
            connection = opened
            opened.open()
            synchronized(lock) {
                if (!running || closed.get() || generation != connectionGeneration) return
            }
            createdModem.attachOutput(opened.output)
            createdModem.attachCarrierPresent(opened::setCarrierPresent)

            var initialized = false
            var stoppedDuringInitialization = false
            var initializationFailure: Throwable? = null
            opened.startReading(
                onBytes = createdModem::receive,
                onStopped = { error ->
                    val starting = synchronized(lock) {
                        if (!initialized) {
                            stoppedDuringInitialization = true
                            initializationFailure = error
                            true
                        } else false
                    }
                    if (!starting) serialReaderStopped(connectionGeneration, connectionIdentity, opened, createdModem, error)
                },
            )
            synchronized(lock) {
                initialized = true
                if (stoppedDuringInitialization) {
                    throw initializationFailure ?: IllegalStateException("Serial reader stopped during initialization")
                }
                if (!running || closed.get() || generation != connectionGeneration ||
                    connectionAttemptGeneration != connectionGeneration) return
                val latestConfig = HayesModemConfig.fromConfigStore()
                createdModem.updateLoginCredentials(latestConfig.username, latestConfig.password)
                createdModem.updateToneSimulationEnabled(latestConfig.toneSimulationEnabled)
                // No initializer uses the resources after this ownership transfer.
                activeConnection = opened
                activeModem = createdModem
                connectionAttemptGeneration = null
                handedOff = true
                YameEvents.publish(ConnectionStateChanged(connectionIdentity, ConnectionState.CONNECTED))
                observeLog("YAME ready on $portName at $baud baud")
            }
        } catch (error: Throwable) {
            val current = synchronized(lock) {
                val current = generation == (claimedGeneration ?: observedGeneration) && !closed.get()
                if (handedOff && activeConnection === connection && activeModem === modem) {
                    // Reclaim ownership if publication failed; other cleanup owners
                    // have already cleared these slots when they won the race.
                    activeConnection = null
                    activeModem = null
                    handedOff = false
                }
                if (connectionAttemptGeneration == claimedGeneration) connectionAttemptGeneration = null
                if (current) {
                    YameEvents.publish(ConnectionStateChanged(claimedIdentity ?: connectionId, ConnectionState.DISCONNECTED))
                    observeLog("Could not open $portName: ${error.message ?: error.javaClass.simpleName}")
                }
                current
            }
            if (error !is Exception || (current && failFast)) throw error
        } finally {
            try {
                if (!handedOff) closeConnection(connection, modem)
            } finally {
                synchronized(lock) {
                    if (connectionAttemptGeneration == claimedGeneration) connectionAttemptGeneration = null
                }
                finishTransition()
            }
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
                beginTransition()
                true
            }
        }
        if (!current) return

        val message = if (error != null) "Serial connection lost: ${error.message ?: error.javaClass.simpleName}"
            else if (running && autoConnectEnabled) "Serial connection stopped" else null
        releaseConnection(DetachedConnection(connection, modem,
            ConnectionStateChanged(connectionIdentity, ConnectionState.DISCONNECTED)), message)
    }

    private data class DetachedConnection(
        val connection: SerialTransport?, val modem: HayesModem?, val ended: ConnectionStateChanged?,
    )

    /** Called with lock held: exactly one thread takes ownership of detached IO. */
    private fun detachConnection(): DetachedConnection {
        generation++
        val previous = DetachedConnection(activeConnection, activeModem,
            if (activeConnection != null || activeModem != null || connectionAttemptGeneration != null)
                ConnectionStateChanged(connectionId, ConnectionState.DISCONNECTED) else null)
        activeConnection = null
        activeModem = null
        connectionAttemptGeneration = null
        beginTransition()
        return previous
    }

    private fun releaseConnection(previous: DetachedConnection, message: String? = null) {
        try {
            var failure: Throwable? = null
            try { closeConnection(previous.connection, previous.modem) } catch (error: Throwable) { failure = error }
            // Detachment is logical disconnection even when physical cleanup fails.
            try { previous.ended?.let { YameEvents.publish(it) } } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
            failure?.let { throw it }
            if (message != null) observeLog(message)
        } finally { finishTransition() }
    }

    private fun closeConnection(connection: SerialTransport?, modem: HayesModem?) {
        var failure: Throwable? = null
        // Stop/join the reader before releasing the modem it calls.
        try { connection?.close() } catch (error: Throwable) { failure = error }
        try { modem?.close() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    private fun connectionIsActive(): Boolean =
        synchronized(lock) {
            connectionAttemptGeneration != null ||
                (activeConnection != null && activeModem != null)
        }

    private fun beginTransition() {
        pendingTransitions++
        transitionDepth.set(transitionDepth.get() + 1)
    }

    private fun finishTransition() = synchronized(lock) {
        pendingTransitions--
        val depth = transitionDepth.get() - 1
        if (depth == 0) transitionDepth.remove() else transitionDepth.set(depth)
        lock.notifyAll()
    }

    private fun observeLog(message: String) { YameEvents.publish(DiagnosticMessage(message)) }

    private fun observeConnection(state: ConnectionState) = synchronized(lock) {
        if (running && !closed.get()) YameEvents.publish(ConnectionStateChanged(connectionId, state))
    }

    override fun close() {
        val first = synchronized(lock) {
            if (closed.get()) false else {
                check(transitionDepth.get() == 0) { "Close runtime outside its own IO callbacks" }
                closed.set(true)
                closingThread = Thread.currentThread()
                running = false
                autoConnectEnabled = false
                true
            }
        }
        if (!first) {
            // A cleanup callback must not wait for the owner that is waiting for it.
            if ((Thread.currentThread() === closingThread && closeCompleted.count > 0) || transitionDepth.get() > 0) return
            var interrupted = false
            while (true) {
                try { closeCompleted.await(); break } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
            closeFailure?.let { throw it }
            return
        }
        var failure: Throwable? = null
        var interrupted = false
        fun attempt(cleanup: () -> Unit) {
            try { cleanup() } catch (error: Throwable) {
                if (error is InterruptedException) {
                    interrupted = true
                    Thread.interrupted() // Finish quiescence before restoring the interrupt.
                }
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        try {
            attempt { configurationSubscription.close() }
            functionRegistrations.forEach { registration -> attempt { registration.close() } }
            attempt { monitorThread?.interrupt() }
            attempt {
                synchronized(lock) { if (started) detachConnection() else null }?.let { releaseConnection(it) }
            }
            synchronized(lock) {
                while (pendingTransitions > 0) attempt { lock.wait() }
            }
            monitorThread?.let { monitor ->
                if (Thread.currentThread() !== monitor) {
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(THREAD_JOIN_MILLIS)
                    while (monitor.isAlive) {
                        val remaining = deadline - System.nanoTime()
                        if (remaining <= 0) break
                        attempt { TimeUnit.NANOSECONDS.timedJoin(monitor, remaining) }
                    }
                    attempt { check(!monitor.isAlive) { "Port monitor did not stop" } }
                }
            }
        } finally {
            closeFailure = failure
            closeCompleted.countDown()
            if (interrupted) Thread.currentThread().interrupt()
        }
        failure?.let { throw it }
    }

    private companion object {
        const val APP_POLL_MILLIS = 100L
        const val PORT_SCAN_MILLIS = 2_000L
        const val THREAD_JOIN_MILLIS = 500L
    }
}
