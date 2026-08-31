package no.skasti.serialmodem.ui

import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.serialmodem.modem.HayesModem
import no.skasti.serialmodem.modem.HayesModemConfig
import no.skasti.serialmodem.ppp.Ipv4Address
import no.skasti.serialmodem.serial.SerialConnection

class InteractiveYameApplication(
    initialPortName: String?,
    initialBaud: Int,
    initialModemConfig: HayesModemConfig,
    terminal: Terminal,
    private val portProvider: () -> List<no.skasti.serialmodem.serial.SerialPortDescriptor> =
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
    private var modemConfig: HayesModemConfig = initialModemConfig

    private var generation = 0
    private var activeConnection: SerialConnection? = null
    private var activeModem: HayesModem? = null
    private var monitorThread: Thread? = null

    private val observer = TuiYameObserver(
        initialPortName = initialPortName,
        initialBaud = initialBaud,
        initialDnsUpstream = initialModemConfig.pppDnsConfig.upstreamServer.toString(),
        initialHttpProxyEnabled = initialModemConfig.pppHttpCompatibilityConfig.enabled,
        onQuit = ::shutdown,
        onPortSelected = ::selectPort,
        onBaudSelected = ::selectBaud,
        onDnsUpstreamSelected = ::selectDnsUpstream,
        onHttpProxySelected = ::selectHttpProxy,
        onDisconnect = ::disconnect,
        onReconnect = ::reconnect,
        onRefreshPorts = ::refreshPorts,
        terminal = terminal,
    )

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
        updateObserverSettings()
        restartConnection()
    }

    private fun selectBaud(baud: Int) {
        selectedBaud = baud
        autoConnectEnabled = true
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
        observer.onLog(
            "HTTP/HTTPS compatibility proxy ${if (enabled) "enabled" else "disabled"}; reconnecting",
        )
        autoConnectEnabled = true
        updateObserverSettings()
        restartConnection()
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
        val config = modemConfig
        synchronized(lock) {
            if (activeConnection != null || activeModem != null) return
            generation++
            connectionGeneration = generation
        }

        val modem = HayesModem(
            baudRate = baud,
            config = config,
            logger = observer::onLog,
            eventSink = observer::onEvent,
        )
        val connection = SerialConnection(
            portName = portName,
            baudRate = baud,
            logger = observer::onLog,
        )

        try {
            connection.open()
            modem.attachOutput(connection.output)
            modem.attachCarrierPresent(connection::setCarrierPresent)

            synchronized(lock) {
                if (!running || generation != connectionGeneration) {
                    runCatching { modem.close() }
                    runCatching { connection.close() }
                    return
                }
                activeModem = modem
                activeConnection = connection
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
            synchronized(lock) {
                if (generation == connectionGeneration) {
                    activeModem = null
                    activeConnection = null
                }
            }
            observer.updateConnected(false)
            observer.onLog(
                "Could not open $portName: ${error.message ?: error.javaClass.simpleName}",
            )
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
            dnsUpstream = modemConfig.pppDnsConfig.upstreamServer.toString(),
            httpProxyEnabled = modemConfig.pppHttpCompatibilityConfig.enabled,
        )
    }

    private fun connectionIsActive(): Boolean =
        synchronized(lock) {
            activeConnection != null && activeModem != null
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
    }

    private companion object {
        const val APP_POLL_MILLIS = 100L
        const val PORT_SCAN_MILLIS = 2_000L
        const val THREAD_JOIN_MILLIS = 500L
    }
}
