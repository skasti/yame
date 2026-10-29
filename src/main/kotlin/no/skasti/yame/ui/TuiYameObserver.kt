package no.skasti.yame.ui

import no.skasti.yame.ppp.session.PppObservation
import no.skasti.yame.ppp.session.PppSessionEnded
import no.skasti.yame.serial.AvailablePortsChanged
import no.skasti.yame.serial.ConnectionStateChanged
import no.skasti.yame.serial.ConnectionState
import no.skasti.yame.logging.LogEntry
import no.skasti.yame.ppp.session.UnhandledPacket
import no.skasti.yame.ppp.session.DnsQuery
import no.skasti.yame.ppp.session.DnsResponse
import no.skasti.yame.ppp.session.DnsFailure
import no.skasti.yame.ppp.session.TransferStarted
import no.skasti.yame.ppp.session.TransferStateChanged
import no.skasti.yame.ppp.session.TransferBytes
import no.skasti.yame.ppp.session.TransferDirection
import no.skasti.yame.ppp.session.TransferKind
import no.skasti.yame.ppp.session.TransferState
import no.skasti.yame.ppp.proxy.HttpProxyAction
import no.skasti.yame.ppp.proxy.ResourceGraphLifecycleEvent
import no.skasti.yame.ppp.proxy.ResourceLifecycleEvent
import no.skasti.yame.ppp.proxy.ResourceEdgeSnapshot

import no.skasti.yame.config.ConfigStore
import no.skasti.yame.config.SettingKey
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.serial.SerialSettings
import no.skasti.yame.serial.ConnectionFunctions
import no.skasti.yame.modem.ModemSettings
import no.skasti.yame.modem.LoginCredentials
import no.skasti.yame.ppp.dns.DnsSettings
import no.skasti.yame.ppp.proxy.ProxySettings
import no.skasti.yame.logging.LogSettings
import no.skasti.yame.lifecycle.FatalFailureSource
import no.skasti.yame.lifecycle.DeadlineCloseable
import no.skasti.yame.lifecycle.ApplicationFunctions
import com.github.ajalt.mordant.animation.textAnimation
import com.github.ajalt.mordant.rendering.TextColors.Companion.gray
import com.github.ajalt.mordant.rendering.TextColors.brightBlue
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightRed
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.inverse
import com.github.ajalt.mordant.terminal.Terminal
import no.skasti.yame.logging.PacketCaptureLimits
import no.skasti.yame.logging.PacketCaptureLimitKind
import no.skasti.yame.logging.YameLogLevel
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.observer.YameEvent
import no.skasti.yame.ppp.dns.dnsResponseCodeName
import no.skasti.yame.ppp.proxy.ResourceKind
import no.skasti.yame.ppp.proxy.ResourceState
import no.skasti.yame.ppp.proxy.transform.ResourceTransformationSummary
import no.skasti.yame.serial.SerialPortDescriptor
import no.skasti.yame.serial.SerialFlowControl
import java.net.URI
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class TuiYameObserver(
    private val functions: FunctionStore = FunctionStore.current,
    private val terminal: Terminal = Terminal(interactive = true),
) : DeadlineCloseable, FatalFailureSource {
    private val paletteRegistry = CommandPalette()
    private val onPortSelected: (String) -> Unit = { value -> selectConnectionSetting(SerialSettings.Port, value) }
    private val onBaudSelected: (Int) -> Unit = { value -> selectConnectionSetting(SerialSettings.Baud, value) }
    private val onFlowControlSelected: (SerialFlowControl) -> Unit = { value -> selectConnectionSetting(SerialSettings.FlowControl, value) }
    private val onDnsUpstreamSelected: (String) -> Unit = { value -> ConfigStore.set(DnsSettings.Upstream to no.skasti.yame.ppp.ip.Ipv4Address.parse(value)) }
    private val onHttpProxySelected: (Boolean) -> Unit = { value -> ConfigStore.set(ProxySettings.Enabled to value) }
    private val onToneSimulationSelected: (Boolean) -> Unit = { value -> ConfigStore.set(ModemSettings.Tone to value) }
    private val onQuit: () -> Unit = { functions.invoke(ApplicationFunctions.Quit) }
    private val onDisconnect: () -> Unit = { functions.invoke(ConnectionFunctions.Disconnect) }
    private val onReconnect: () -> Unit = { functions.invoke(ConnectionFunctions.Reconnect) }
    private fun refreshAvailablePorts() = updateAvailablePorts(functions.invoke(ConnectionFunctions.RefreshPorts))
    private val onCaptureLimitSelected: (PacketCaptureLimitKind, Int) -> Unit = { kind, value -> ConfigStore.set(LogSettings.CaptureLimits.getValue(kind) to value) }
    private val onLogLevelSelected: (YameLogModule, YameLogLevel) -> Unit = { module, level -> ConfigStore.set(LogSettings.Levels.getValue(module) to level) }
    private val onLoginAdded: (String, String) -> Unit = { username, password -> ConfigStore.set(ModemSettings.Login to LoginCredentials(username, password)) }

    // Presentation state starts from typed reads; the initial subscription closes
    // the read/subscribe race and applies subsequent change values in order.
    private var captureLimits = PacketCaptureLimitKind.entries.fold(PacketCaptureLimits()) { limits, kind ->
        kind.apply(limits, ConfigStore[LogSettings.CaptureLimits.getValue(kind)])
    }
    private var portName = ConfigStore[SerialSettings.Port]
    private var baud = ConfigStore[SerialSettings.Baud]
    private var flowControl = ConfigStore[SerialSettings.FlowControl]
    private var dnsUpstream = ConfigStore[DnsSettings.Upstream].toString()
    private var httpProxyEnabled = ConfigStore[ProxySettings.Enabled]
    private var toneSimulationEnabled = ConfigStore[ModemSettings.Tone]
    private val logLevels = YameLogModule.entries.associateWith {
        ConfigStore[LogSettings.Levels.getValue(it)]
    }.toMutableMap()
    private var connected = false
    private var availablePorts: List<SerialPortDescriptor> = emptyList()

    private val logs = ArrayDeque<String>()
    private val dnsLookups = mutableListOf<DashboardDnsLookup>()
    private val transfers = linkedMapOf<String, DashboardTransfer>()
    private val sessionGate = SessionObservationGate()
    private val pendingTransfers = linkedMapOf<String, MutableList<YameEvent>>()
    private var pendingTransferCount = 0

    private data class HttpSelectionRow(
        val host: String,
        val resourceUrl: String?,
    )

    private val httpGraphs = HttpGraphProjection()
    private val expandedHttpHosts = linkedSetOf<String>()
    private var selectedHttpHost: String? = null
    private var selectedHttpResourceUrl: String? = null
    private var commandPalette: TuiCommandPalette? = null
    private var activeDashboardPanel = DashboardDetailPanel.PROXY
    private var keyboardInput: JLineKeyboardInput? = null

    private val animation = terminal.textAnimation<String> { it }
    private var inputThread: Thread? = null
    private val headerTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "yame-tui-header").apply { isDaemon = true }
    }
    private var headerStartedNanos = 0L
    private var screenStarted = false
    private var lastRenderedFrame: String? = null

    @Volatile
    private var closed = false

    @Volatile
    var stopRequested: Boolean = false
        private set

    private val commandRegistrations = registerCommands() + registerTransformerCommands(paletteRegistry) + registerPppCommands(paletteRegistry)
    private val work = TuiWorkQueue(teardown = ::teardown)
    // All state changes are still processed in FIFO order. Only screen painting is
    // coalesced, so a burst of resource discovery cannot force one frame per event.
    private val frames = TuiFrameLimiter(
        nowNanos = System::nanoTime,
        schedule = ::scheduleFrame,
        draw = ::renderNow,
    )
    override val fatalFailure: Throwable? get() = work.fatalFailure
    internal fun awaitIdle() = work.read { Unit }

    private val configurationSubscription = ConfigStore.onChange.subscribe(initial = true) { changes ->
        work.submitRequired(kind = "ConfigChanged") {
            changes[SerialSettings.Port]?.let { portName = it.value }
            changes[SerialSettings.Baud]?.let { baud = it.value }
            changes[SerialSettings.FlowControl]?.let { flowControl = it.value }
            changes[DnsSettings.Upstream]?.let { dnsUpstream = it.value.toString() }
            changes[ProxySettings.Enabled]?.let { httpProxyEnabled = it.value }
            changes[ModemSettings.Tone]?.let { toneSimulationEnabled = it.value }
            LogSettings.Levels.forEach { (module, key) -> changes[key]?.let { logLevels[module] = it.value } }
            LogSettings.CaptureLimits.forEach { (kind, key) -> changes[key]?.let { captureLimits = kind.apply(captureLimits, it.value) } }
            render()
        }
    }

    private fun <T> selectConnectionSetting(key: SettingKey<T>, value: T) {
        val unchanged = ConfigStore[key] == value
        ConfigStore.set(key to value)
        // A selection is also a connection intent. Keep that action separate from
        // configuration events, whose no-op semantics apply to all consumers.
        if (unchanged) functions.invoke(ConnectionFunctions.Reconnect)
    }

    internal fun paletteSnapshot(): TuiCommandPalette? = work.read {
        commandPalette?.let { it.copy(options = it.options.toList()) }
    }

    internal fun handleKey(key: String) { work.submitRequired(kind = "KeyboardInput") { applyKey(key) } }

    private fun applyKey(key: String) {
        try {
            val paletteOpen = commandPalette != null
            when {
                key == "Ctrl+C" -> requestQuit()
                paletteOpen -> handlePaletteKey(key)
                key == "Tab" -> cycleDashboardPanel()
                key == "/" -> openCommandPalette()
                key.equals("q", ignoreCase = true) -> requestQuit()
                key == "ArrowUp" -> moveHttpSelection(-1)
                key == "ArrowDown" -> moveHttpSelection(1)
                key == "ArrowRight" || key == "Enter" -> setSelectedHttpHostExpanded(true)
                key == "ArrowLeft" -> setSelectedHttpHostExpanded(false)
            }
        } catch (error: Exception) {
            if (work.fatalFailure != null) throw error
            onLog("Command failed: ${error.javaClass.simpleName}")
        }
    }

    fun start() { work.submitRequired(kind = "StartScreen") { startScreen() } }

    private fun startScreen() {
        if (closed || screenStarted) return
        screenStarted = true
        headerStartedNanos = System.nanoTime()
        headerTimer.scheduleAtFixedRate(
            { work.submitRequired(kind = "HeaderRefresh") { render() } }, 5, 5, java.util.concurrent.TimeUnit.SECONDS,
        )
        enterScreen()
        render()
        startInputLoop()
    }

    private fun updateConnected(value: Boolean) {
        connected = value
        render()
    }

    private fun closeActiveTransfers(detail: String?) {
        if (transfers.isEmpty()) return
        val now = System.nanoTime()
        transfers.replaceAll { _, transfer ->
            if (
                transfer.state == TransferState.OPEN ||
                transfer.state == TransferState.CONNECTING
            ) {
                transfer.copy(
                    state = TransferState.CLOSED,
                    updatedNanos = now,
                    detail = detail ?: transfer.detail,
                )
            } else {
                transfer
            }
        }
        render()
    }

    internal fun updateAvailablePorts(ports: List<SerialPortDescriptor>) {
        if (!work.isWorker) {
            val snapshot = ports.toList()
            work.submitRequired(kind = "PortUpdate") { updateAvailablePorts(snapshot) }
            return
        }
        val previousPalette = commandPalette
        val selectedValue = previousPalette
            ?.takeIf { it.mode == TuiPaletteMode.PORTS }
            ?.options
            ?.getOrNull(previousPalette.selectedIndex)
            ?.value

        availablePorts = ports.toList()

        if (previousPalette?.mode == TuiPaletteMode.PORTS) {
            val options = portPaletteOptions()
            val selectedIndex = selectedValue
                ?.let { value ->
                    options.indexOfFirst { it.value.equals(value, ignoreCase = true) }
                }
                ?.takeIf { it >= 0 }
                ?: options.indexOfFirst {
                    it.value.equals(portName, ignoreCase = true)
                }.takeIf { it >= 0 }
                ?: previousPalette.selectedIndex
                    .coerceIn(0, (options.size - 1).coerceAtLeast(0))

            commandPalette = previousPalette.copy(
                title = if (options.isEmpty()) "No serial ports found" else "Select serial port",
                options = options,
                selectedIndex = selectedIndex,
            )
        }
        render()
    }

    fun onLog(message: String) {
        if (!work.isWorker) { work.submitRequired(kind = "DiagnosticLog") { onLog(message) }; return }
        appendLog(message)
        render()
    }

    private fun appendLog(message: String) {
        val timestamp = LocalTime.now().format(TIME_FORMAT)
        logs.addLast("$timestamp  $message")
        while (logs.size > MAX_LOG_LINES) {
            logs.removeFirst()
        }
    }

    @Volatile internal var observationFailure: Throwable? = null
        private set

    fun onEvent(event: YameEvent) {
        // Production events already own immutable payloads at the publication boundary.
        work.submitObservation(kind = event.javaClass.simpleName) { processEvent(event) }
    }

    private fun processEvent(event: YameEvent) {
        try { applyObservation(event) } catch (error: Throwable) {
            observationFailure = error
            appendLog("Runtime observation failed; dashboard may be incomplete")
            runCatching { render() } // Keep the original projection failure if the terminal also fails.
            throw error
        }
    }

    private fun applyObservation(event: YameEvent) {
        if (closed) return
        val previousConnection = sessionGate.connectionId
        val previousSession = sessionGate.sessionId
        if (event is ConnectionStateChanged && !sessionGate.accept(event)) return
        if (event is PppObservation && !sessionGate.accept(event)) return
        if (previousConnection != sessionGate.connectionId || previousSession != sessionGate.sessionId) {
            closeActiveTransfers("session replaced")
            pendingTransfers.clear()
            pendingTransferCount = 0
        }
        when (event) {
            is LogEntry -> appendLog(event.message)
            is AvailablePortsChanged -> { updateAvailablePorts(event.ports); return }
            is ConnectionStateChanged -> {
                if (event.state == ConnectionState.DISCONNECTED) {
                    closeActiveTransfers("serial session stopped")
                    pendingTransfers.clear()
                    pendingTransferCount = 0
                }
                updateConnected(event.state == ConnectionState.CONNECTED)
                return
            }
            is PppSessionEnded -> {
                closeActiveTransfers("PPP session stopped")
                pendingTransfers.clear()
                pendingTransferCount = 0
            }
            is UnhandledPacket -> appendLog(event.reason)
            is DnsQuery -> {
                val key = observationKey(event, event.key)
                val existing = dnsLookups.indexOfFirst { it.key == key }
                val lookup = DashboardDnsLookup(
                    key = key,
                    time = LocalTime.now().format(TIME_FORMAT),
                    transport = event.transport.name,
                    name = event.name ?: "?",
                    type = event.type ?: "?",
                    status = "LOOKUP",
                    answers = null,
                    bytes = event.bytes,
                )
                if (existing >= 0) {
                    dnsLookups[existing] = lookup
                } else {
                    dnsLookups += lookup
                }
                trimDnsLookups()
            }

            is DnsResponse -> {
                val key = observationKey(event, event.key)
                val index = dnsLookups.indexOfFirst { it.key == key }
                val status = event.responseCode
                    ?.let(::dnsResponseCodeName)
                    ?: "REPLY"
                val suffix = if (event.truncated) " TC" else ""
                val updated = DashboardDnsLookup(
                    key = key,
                    time = dnsLookups.getOrNull(index)?.time
                        ?: LocalTime.now().format(TIME_FORMAT),
                    transport = event.transport.name,
                    name = event.name ?: dnsLookups.getOrNull(index)?.name ?: "?",
                    type = dnsLookups.getOrNull(index)?.type ?: "?",
                    status = status + suffix,
                    answers = event.answerCount,
                    bytes = event.bytes,
                )
                if (index >= 0) {
                    dnsLookups[index] = updated
                } else {
                    dnsLookups += updated
                }
                trimDnsLookups()
            }

            is DnsFailure -> {
                val key = observationKey(event, event.key)
                val index = dnsLookups.indexOfFirst { it.key == key }
                if (index >= 0) {
                    dnsLookups[index] = dnsLookups[index].copy(status = "FAILED")
                } else {
                    dnsLookups += DashboardDnsLookup(
                        key = key,
                        time = LocalTime.now().format(TIME_FORMAT),
                        transport = event.transport.name,
                        name = "?",
                        type = "?",
                        status = "FAILED",
                        answers = null,
                        bytes = 0,
                    )
                }
                trimDnsLookups()
            }

            is TransferStarted -> {
                val key = observationKey(event, event.flowId)
                transfers.remove(key)
                transfers[key] = DashboardTransfer(
                    flowId = event.flowId,
                    destination = event.destination,
                    via = event.via,
                    kind = event.kind,
                    state = TransferState.CONNECTING,
                    toHostBytes = 0,
                    toPeerBytes = 0,
                    startedNanos = System.nanoTime(),
                    updatedNanos = System.nanoTime(),
                    detail = null,
                )
                trimTransfers()
                pendingTransfers.remove(key)?.let { pending ->
                    pendingTransferCount -= pending.size
                    pending.forEach(::processEvent)
                }
            }

            is TransferStateChanged -> {
                val key = observationKey(event, event.flowId)
                val transfer = transfers[key] ?: return deferTransfer(key, event)
                if (transfer.state in setOf(TransferState.CLOSED, TransferState.FAILED)) return
                transfers[key] = transfer.copy(
                    state = event.state,
                    updatedNanos = System.nanoTime(),
                    detail = event.detail,
                )
                trimTransfers()
            }

            is TransferBytes -> {
                val key = observationKey(event, event.flowId)
                val transfer = transfers[key] ?: return deferTransfer(key, event)
                transfers[key] = transfer.copy(
                    toHostBytes = transfer.toHostBytes +
                        if (event.direction == TransferDirection.TO_HOST) event.bytes else 0,
                    toPeerBytes = transfer.toPeerBytes +
                        if (event.direction == TransferDirection.TO_PEER) event.bytes else 0,
                    updatedNanos = System.nanoTime(),
                )
            }

            is ResourceGraphLifecycleEvent, is ResourceLifecycleEvent -> {
                httpGraphs.accept(event)
                normalizeHttpHostSelection()
            }
            is HttpProxyAction -> return
            else -> return // Unknown observations do not change this projection.
        }
        render()
    }

    private fun observationKey(event: PppObservation, key: String): String =
        "${event.session.connectionId}:${event.session.id}:$key"

    private fun deferTransfer(flowId: String, event: YameEvent) {
        check(pendingTransferCount < 128) { "TUI transfer dependency buffer exhausted" }
        pendingTransfers.getOrPut(flowId) { mutableListOf() }.add(event)
        pendingTransferCount++
    }

    internal fun transferProjectionSnapshot(): List<DashboardTransfer> = work.read {
        transfers.values.toList()
    }

    internal fun connectedSnapshot(): Boolean = work.read {
        connected
    }

    internal fun resourceGraphProjectionSnapshot(): Map<Long, Map<URI, DashboardHttpResource>> = work.read {
        httpGraphs.graphSnapshot().mapValues { (_, graph) ->
            graph.resources.values.associate { it.resourceLegacyUri to it.dashboardResource() }
        }
    }

    internal fun resourceGraphMetadataSnapshot(): Map<Long, DashboardHttpGraphMetadata> = work.read {
        httpGraphs.graphSnapshot().mapValues { (_, graph) ->
            DashboardHttpGraphMetadata(graph.rootLegacyUri, graph.usageCount,
                graph.lastUsedAt, graph.associations.toSet())
        }
    }

    internal fun httpHostProjectionSnapshot(): List<DashboardHttpHost> = work.read {
        httpHostSnapshots()
    }

    internal fun proxyProjectionStatus(): String? = work.read {
        httpGraphs.status
    }

    internal fun httpSelectionSnapshot(): Pair<String?, String?> = work.read {
        selectedHttpHost to selectedHttpResourceUrl
    }

    private fun ResourceLifecycleEvent.dashboardResource() = DashboardHttpResource(
        url = resourceLegacyUri.toString(), state = state, kind = kind,
        prefetched = prefetched, transformations = transformations,
    )

    private fun httpHostSnapshots(): List<DashboardHttpHost> {
        data class Aggregate(
            val resources: LinkedHashMap<String, ResourceLifecycleEvent> = linkedMapOf(),
            var useCount: Long = 0,
            var lastUsedAt: Instant? = null,
        )

        val hosts = linkedMapOf<String, Aggregate>()
        // Graph IDs break equal-time cross-graph ties deterministically, independent of delivery.
        httpGraphs.graphSnapshot().toSortedMap().values.forEach { graph ->
            val graphHosts = linkedSetOf<String>()
            graph.resources.forEach { (key, event) ->
                val host = event.resourceLegacyUri.host?.lowercase() ?: return@forEach
                val aggregate = hosts.getOrPut(host) { Aggregate() }
                val existing = aggregate.resources[key]
                if (existing == null || event.occurredAt >= existing.occurredAt) aggregate.resources[key] = event
                aggregate.lastUsedAt = listOfNotNull(aggregate.lastUsedAt, graph.lastUsedAt).maxOrNull()
                graphHosts += host
            }
            graphHosts.forEach { host ->
                hosts.getValue(host).useCount += graph.usageCount
            }
        }

        return hosts
            .map { (host, aggregate) ->
                DashboardHttpHost(
                    host = host,
                    resources = aggregate.resources.values.map { it.dashboardResource() },
                    expanded = host in expandedHttpHosts,
                    useCount = aggregate.useCount,
                    lastUsedAt = aggregate.lastUsedAt,
                )
            }
            .sortedWith(
                compareByDescending<DashboardHttpHost> { it.lastUsedAt }
                    .thenByDescending { it.useCount }
                    .thenBy { it.host },
            )
    }

    private fun normalizeHttpHostSelection() {
        val hosts = httpHostSnapshots()
        expandedHttpHosts.retainAll(hosts.mapTo(mutableSetOf()) { it.host })
        selectedHttpHost = selectedHttpHost
            ?.takeIf { selected -> hosts.any { it.host == selected } }
            ?: hosts.firstOrNull()?.host
        selectedHttpResourceUrl =
            selectedHttpResourceUrl?.takeIf { resourceUrl ->
                (
                    hosts
                        .firstOrNull { it.host == selectedHttpHost }
                        ?.let { host -> host.expanded && host.resources.any { it.url == resourceUrl } }
                ) == true
            }
    }

    private fun moveHttpSelection(delta: Int) {
        val hosts = httpHostSnapshots()
        if (hosts.isEmpty()) return
        val rows =
            hosts.flatMap { host ->
                buildList {
                    add(HttpSelectionRow(host.host, null))
                    if (host.expanded) {
                        host.resources.forEach { resource ->
                            add(HttpSelectionRow(host.host, resource.url))
                        }
                    }
                }
            }
        val current =
            rows.indexOfFirst {
                it.host == selectedHttpHost && it.resourceUrl == selectedHttpResourceUrl
            }.takeIf { it >= 0 }
                ?: 0
        val next = (current + delta).coerceIn(0, rows.lastIndex)
        selectedHttpHost = rows[next].host
        selectedHttpResourceUrl = rows[next].resourceUrl
        render()
    }

    private fun setSelectedHttpHostExpanded(expanded: Boolean) {
        val host = selectedHttpHost ?: return
        if (expanded) expandedHttpHosts += host else expandedHttpHosts -= host
        if (!expanded) selectedHttpResourceUrl = null
        render()
    }

    private fun trimDnsLookups() {
        while (dnsLookups.size > MAX_DNS_LOOKUPS) {
            dnsLookups.removeAt(0)
        }
    }

    private fun trimTransfers() {
        if (transfers.size <= MAX_TRANSFERS) return
        val removable = transfers.values
            .filter { it.state == TransferState.CLOSED || it.state == TransferState.FAILED }
            .sortedBy { it.updatedNanos }
        for (transfer in removable) {
            if (transfers.size <= MAX_TRANSFERS) break
            transfers.remove(transfer.flowId)
        }
        while (transfers.size > MAX_TRANSFERS) {
            transfers.remove(transfers.keys.first())
        }
    }

    private fun enterScreen() {
        terminal.rawPrint(ENTER_ALTERNATE_SCREEN + HIDE_CURSOR)
    }

    private fun leaveScreen() {
        terminal.rawPrint(SHOW_CURSOR + LEAVE_ALTERNATE_SCREEN)
    }

    private fun startInputLoop() {
        if (!terminal.terminalInfo.inputInteractive || inputThread != null) return
        val keyboard = try { JLineKeyboardInput.open() } catch (error: Exception) {
            onLog("Keyboard input unavailable: ${error.message ?: error.javaClass.simpleName}")
            return
        }
        keyboardInput = keyboard
        inputThread = Thread({
            try {
                while (!closed && !stopRequested) {
                    val key = keyboard.readKey(INPUT_POLL_MILLIS) ?: continue
                    handleKey(key)
                }
            } catch (error: Exception) {
                if (!closed && !stopRequested) onLog("Keyboard input unavailable: ${error.message ?: error.javaClass.simpleName}")
            }
        }, "yame-tui-input").apply { isDaemon = true; start() }
    }

    private fun cycleDashboardPanel() {
        if (terminal.size.width > WIDE_DASHBOARD_THRESHOLD) return
        activeDashboardPanel = activeDashboardPanel.next()
        render()
    }

    private fun openCommandPalette() {
        commandPalette = commandPaletteFor("/")
        render()
    }

    private fun openPortPalette() {
        val options = portPaletteOptions()
        commandPalette = TuiCommandPalette(
            mode = TuiPaletteMode.PORTS,
            input = "/port",
            title = if (options.isEmpty()) "No serial ports found" else "Select serial port",
            options = options,
            selectedIndex = options
                .indexOfFirst { it.value.equals(portName, ignoreCase = true) }
                .coerceAtLeast(0),
        )
        render()
    }

    private fun portPaletteOptions(): List<TuiCommandOption> =
        availablePorts.map { port ->
            TuiCommandOption(
                label = port.systemPortName,
                description = port.descriptivePortName,
                value = port.systemPortName,
            )
        }

    private fun openBaudPalette() {
        val rates = baudPaletteRates(baud)
        commandPalette = TuiCommandPalette(
            mode = TuiPaletteMode.BAUD,
            input = "/baud",
            title = "Select baud rate",
            options = rates.map { rate ->
                TuiCommandOption(
                    label = rate.toString(),
                    description = if (rate == baud) "current" else "",
                    value = rate.toString(),
                )
            },
            selectedIndex = rates.indexOf(baud),
        )
        render()
    }

    private fun openDnsPalette() {
        commandPalette = TuiCommandPalette(
            mode = TuiPaletteMode.DNS,
            input = dnsUpstream,
            title = "DNS upstream IPv4",
            options = emptyList(),
            selectedIndex = 0,
        )
        render()
    }

    private fun openFlowControlPalette() {
        commandPalette = TuiCommandPalette(
            mode = TuiPaletteMode.FLOW_CONTROL,
            input = "/flow-control",
            title = "Serial flow control",
            options = SerialFlowControl.entries.map { option ->
                TuiCommandOption(
                    label = option.displayName,
                    description = if (option == flowControl) "current" else "",
                    value = option.commandName,
                )
            },
            selectedIndex = SerialFlowControl.entries.indexOf(flowControl),
        )
        render()
    }

    private fun openHttpPalette() {
        commandPalette = TuiCommandPalette(
            mode = TuiPaletteMode.HTTP,
            input = "/http-proxy",
            title = "HTTP/HTTPS compatibility proxy",
            options = listOf(
                TuiCommandOption("enabled", "Follow redirects and terminate TLS on host", "true"),
                TuiCommandOption("disabled", "Use normal TCP forwarding", "false"),
            ),
            selectedIndex = if (httpProxyEnabled) 0 else 1,
        )
        render()
    }

    private fun openTonePalette() {
        commandPalette = TuiCommandPalette(
            mode = TuiPaletteMode.TONE,
            input = "/tone",
            title = "Tone simulation",
            options = listOf(
                TuiCommandOption("on", if (toneSimulationEnabled) "current" else "", "true"),
                TuiCommandOption("off", if (toneSimulationEnabled) "" else "current", "false"),
            ),
            selectedIndex = if (toneSimulationEnabled) 0 else 1,
        )
        render()
    }

    private fun openLogLevelPalette(module: YameLogModule) {
        val current = logLevels[module] ?: YameLogLevel.INFO
        commandPalette = TuiCommandPalette(
            mode = TuiPaletteMode.LOG_LEVEL,
            input = "/loglevel-${module.fileName}",
            title = "Log level: ${module.fileName}",
            options = YameLogLevel.entries.map { level ->
                TuiCommandOption(
                    level.name.lowercase(),
                    if (level == current) "current" else "",
                    "${module.name}:${level.name}",
                )
            },
            selectedIndex = YameLogLevel.entries.indexOf(current),
        )
        render()
    }

    private fun handlePaletteKey(key: String) {
        val action = run {
            val palette = commandPalette ?: return

            val pendingAction = when {
                key == "Escape" -> {
                    commandPalette = if (palette.mode == TuiPaletteMode.COMMANDS && palette.palettePath != "/") {
                        commandPaletteFor("/", palette.palettePath.substringBeforeLast('/').ifEmpty { "/" })
                    } else null
                    null
                }
                key == "ArrowUp" && palette.mode != TuiPaletteMode.DNS -> {
                    movePaletteSelection(-1)
                    null
                }
                key == "ArrowDown" && palette.mode != TuiPaletteMode.DNS -> {
                    movePaletteSelection(1)
                    null
                }
                key == "Enter" -> executePaletteSelection()
                key == "Backspace" -> {
                    when (palette.mode) {
                        TuiPaletteMode.COMMANDS -> {
                            if (palette.input == "/" && palette.palettePath != "/") {
                                commandPalette = commandPaletteFor("/", palette.palettePath.substringBeforeLast('/').ifEmpty { "/" })
                            } else {
                                val next = palette.input.dropLast(1).ifEmpty { "/" }
                                commandPalette = commandPaletteFor(next, palette.palettePath)
                            }
                        }
                        TuiPaletteMode.CAPTURE_LIMIT -> {
                            val prefix = palette.input.substringBefore(' ') + " "
                            if (palette.input.length > prefix.length) {
                                commandPalette = palette.copy(input = palette.input.dropLast(1))
                            }
                        }
                        TuiPaletteMode.DNS ->
                            commandPalette = palette.copy(input = palette.input.dropLast(1))
                        TuiPaletteMode.LOGIN -> {
                            val next = palette.input.dropLast(1)
                            commandPalette = if (next.length < TUI_LOGIN_PREFIX.length) {
                                commandPaletteFor(next.ifEmpty { "/" })
                            } else {
                                palette.copy(input = next, title = TUI_LOGIN_TITLE)
                            }
                        }
                        else -> Unit
                    }
                    null
                }
                palette.mode == TuiPaletteMode.COMMANDS &&
                    key.length == 1 &&
                    !key[0].isISOControl() -> {
                    if (!(key == "/" && palette.input == "/") && palette.input.length < 256) {
                        commandPalette = commandPaletteFor(palette.input + key, palette.palettePath)
                    }
                    null
                }
                palette.mode == TuiPaletteMode.CAPTURE_LIMIT && key.length == 1 && key[0].isDigit() -> {
                    commandPalette = palette.copy(input = palette.input + key)
                    null
                }
                palette.mode == TuiPaletteMode.DNS &&
                    key.length == 1 &&
                    (key[0].isDigit() || key[0] == '.') -> {
                    commandPalette = palette.copy(input = palette.input + key)
                    null
                }
                palette.mode == TuiPaletteMode.LOGIN &&
                    key.length == 1 &&
                    !key[0].isISOControl() -> {
                    val next = palette.input + key
                    commandPalette = if (loginInputWithinLimits(next)) {
                        palette.copy(input = next, title = TUI_LOGIN_TITLE)
                    } else {
                        palette.copy(title = TUI_LOGIN_INVALID_TITLE)
                    }
                    null
                }
                else -> null
            }

            render()
            pendingAction
        }

        action?.invoke()
    }

    private fun movePaletteSelection(delta: Int) {
        val palette = commandPalette ?: return
        if (palette.options.isEmpty()) return
        commandPalette = palette.copy(
            selectedIndex = (palette.selectedIndex + delta)
                .coerceIn(0, palette.options.lastIndex),
        )
    }

    private fun executePaletteSelection(): (() -> Unit)? {
        val palette = commandPalette ?: return null

        if (palette.mode == TuiPaletteMode.CAPTURE_LIMIT) {
            val command = parseCaptureLimitInput(palette.input)
            if (command == null) {
                commandPalette = palette.copy(title = "Capture limit: enter a positive integer")
                return null
            }
            commandPalette = null
            return { onCaptureLimitSelected(command.first, command.second) }
        }

        if (palette.mode == TuiPaletteMode.COMMANDS && palette.palettePath == "/") {
            val kind = PacketCaptureLimitKind.entries.firstOrNull {
                palette.input.startsWith("/${it.commandName} ", ignoreCase = true)
            }
            if (kind != null) {
                val command = parseCaptureLimitInput(palette.input)
                if (command == null) {
                    commandPalette = palette.copy(title = "Capture limit: enter a positive integer")
                    return null
                }
                commandPalette = null
                return { onCaptureLimitSelected(command.first, command.second) }
            }
        }

        if (palette.mode == TuiPaletteMode.LOGIN) {
            val credentials = parseLoginInput(palette.input)
            if (credentials == null) {
                commandPalette = palette.copy(title = TUI_LOGIN_INVALID_TITLE)
                return null
            }
            commandPalette = null
            return { onLoginAdded(credentials.username, credentials.password) }
        }

        if (palette.mode == TuiPaletteMode.DNS) {
            if (palette.input.isBlank()) return null
            commandPalette = null
            val value = palette.input
            return { onDnsUpstreamSelected(value) }
        }

        if (palette.mode == TuiPaletteMode.COMMANDS && palette.palettePath == "/" &&
            palette.input.startsWith("/tone ", ignoreCase = true)
        ) {
            val enabled = parseToneInput(palette.input)
            if (enabled == null) {
                commandPalette = palette.copy(title = TUI_TONE_INVALID_TITLE)
                return null
            }
            commandPalette = null
            return { onToneSimulationSelected(enabled) }
        }

        val selected = palette.options.getOrNull(palette.selectedIndex) ?: return null
        return when (palette.mode) {
            TuiPaletteMode.COMMANDS -> {
                val item = paletteRegistry.find(palette.palettePath)?.item(selected.value) ?: return null
                when (item) {
                    is Palette -> {
                        val path = palette.palettePath.trimEnd('/') + "/" + paletteCommandKey(item.command)
                        commandPalette = commandPaletteFor("/", path)
                        null
                    }
                    is PaletteChoice -> {
                        commandPalette = null
                        item.select
                    }
                }
            }

            TuiPaletteMode.PORTS -> {
                commandPalette = null
                val value = selected.value
                val action: () -> Unit = { onPortSelected(value) }
                action
            }

            TuiPaletteMode.BAUD -> {
                commandPalette = null
                selected.value.toIntOrNull()?.let { value ->
                    { onBaudSelected(value) }
                }
            }

            TuiPaletteMode.FLOW_CONTROL -> {
                commandPalette = null
                runCatching { SerialFlowControl.parse(selected.value) }
                    .getOrNull()
                    ?.let { value -> { onFlowControlSelected(value) } }
            }

            TuiPaletteMode.HTTP -> {
                commandPalette = null
                val value = selected.value.toBoolean()
                val action: () -> Unit = { onHttpProxySelected(value) }
                action
            }

            TuiPaletteMode.TONE -> {
                commandPalette = null
                val value = selected.value.toBoolean()
                val action: () -> Unit = { onToneSimulationSelected(value) }
                action
            }

            TuiPaletteMode.LOG_LEVEL -> {
                commandPalette = null
                val parts = selected.value.split(':', limit = 2)
                val module = runCatching { YameLogModule.valueOf(parts[0]) }.getOrNull()
                val level = parts.getOrNull(1)?.let { runCatching { YameLogLevel.valueOf(it) }.getOrNull() }
                if (module != null && level != null) {
                    { onLogLevelSelected(module, level) }
                } else {
                    null
                }
            }

            TuiPaletteMode.DNS,
            TuiPaletteMode.CAPTURE_LIMIT,
            TuiPaletteMode.LOGIN -> null
        }
    }

    private fun openLoginAddPalette() {
        commandPalette = TuiCommandPalette(
            mode = TuiPaletteMode.LOGIN,
            input = TUI_LOGIN_PREFIX,
            title = TUI_LOGIN_TITLE,
            options = emptyList(),
            selectedIndex = 0,
        )
        render()
    }

    private fun commandPaletteFor(input: String, path: String = "/"): TuiCommandPalette {
        val normalized = if (input.startsWith("/")) input else "/$input"
        if (path == "/" && normalized.startsWith(TUI_LOGIN_PREFIX, ignoreCase = true)) {
            return TuiCommandPalette(
                mode = TuiPaletteMode.LOGIN,
                input = normalized,
                title = TUI_LOGIN_TITLE,
                options = emptyList(),
                selectedIndex = 0,
            )
        }
        return registeredCommandPalette(normalized, path, paletteRegistry)
    }

    private fun registerCommands(): List<AutoCloseable> {
        val handles = mutableListOf<AutoCloseable>()
        fun add(command: String, description: String, action: () -> Unit) {
            handles += paletteRegistry.add(PaletteChoice(command, { description }, action))
        }
        try {
            add("/port", "Select serial port") { refreshAvailablePorts(); openPortPalette() }
            add("/baud", "Select baud rate", ::openBaudPalette)
            add("/flow-control", "Select serial flow control", ::openFlowControlPalette)
            add("/dns-upstream", "Change upstream resolver and reconnect", ::openDnsPalette)
            add("/http-proxy", "Enable or disable HTTP/TLS compatibility", ::openHttpPalette)
            add(TUI_TONE_COMMAND, "Enable or disable modem tones", ::openTonePalette)
            add(TUI_LOGIN_COMMAND, "Set terminal login credentials", ::openLoginAddPalette)
            PacketCaptureLimitKind.entries.forEach { kind ->
                add("/${kind.commandName}", "Maximum packet captures") {
                    run {
                        commandPalette = TuiCommandPalette(
                            mode = TuiPaletteMode.CAPTURE_LIMIT,
                            input = "/${kind.commandName} ",
                            title = "Capture limit (current: ${kind.value(captureLimits)}): enter a positive integer",
                            options = emptyList(), selectedIndex = 0,
                        )
                        render()
                    }
                }
            }
            YameLogModule.entries.forEach { module ->
                add("/loglevel-${module.fileName}", "Set ${module.fileName} file log level") { openLogLevelPalette(module) }
            }
            add("/reconnect", "Open the selected serial port", onReconnect)
            add("/disconnect", "Close the current serial port", onDisconnect)
            add("/clear-log", "Clear dashboard log history") { run { logs.clear(); render() } }
            add("/quit", "Quit YAME", ::requestQuit)
            return handles
        } catch (error: Exception) {
            handles.forEach(AutoCloseable::close)
            throw error
        }
    }

    private fun requestQuit() {
        val notify = run {
            if (stopRequested) {
                false
            } else {
                stopRequested = true
                true
            }
        }
        if (notify) {
            try { onQuit() } catch (error: Exception) {
                // An unavailable or failed quit must leave input enabled so a
                // later user action can succeed once the runtime has registered.
                stopRequested = false
                throw error
            }
        }
    }

    private fun render() {
        if (!closed && screenStarted) frames.request()
    }

    /** Timer never touches presentation state and cannot fatally overflow the event FIFO. */
    private fun scheduleFrame(delayNanos: Long) {
        headerTimer.schedule({ enqueueFrame() }, delayNanos, java.util.concurrent.TimeUnit.NANOSECONDS)
    }

    private fun enqueueFrame() {
        if (closed || work.fatalFailure != null) return
        if (!work.offerRedraw { frames.onDeadline() }) {
            // A full FIFO is still processing observations. Retry the redraw,
            // rather than competing with required events for bounded admission.
            if (!closed && work.fatalFailure == null) {
                headerTimer.schedule({ enqueueFrame() }, 5, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun renderNow() {
        try { renderFrame() } catch (error: Throwable) {
            work.fail(error)
            throw error
        }
    }

    private fun renderFrame() {
        if (closed || !screenStarted) return
        // Read bounded queue depths only while painting the wide layout.
        // These are snapshots, not observation events added to either queue.
        val queueBars = if (terminal.size.width > WIDE_DASHBOARD_THRESHOLD &&
            terminal.size.height >= MIN_QUEUE_BAR_HEIGHT
        ) {
            val broker = no.skasti.yame.observer.YameEvents.current
            QueueBars(
                broker = QueueOccupancy(broker.status.queuedEvents, broker.config.capacity),
                tui = work.occupancy,
            )
        } else null
        val frame = YameDashboardRenderer.render(
            state = httpHostSnapshots().let { httpHosts ->
                DashboardState(
                    portName = portName,
                    baud = baud,
                    flowControl = flowControl,
                    connected = connected,
                    dnsUpstream = dnsUpstream,
                    httpProxyEnabled = httpProxyEnabled,
                    toneSimulationEnabled = toneSimulationEnabled,
                    logs = logs.toList(),
                    dnsLookups = dnsLookups.toList(),
                    transfers = transfers.values.toList(),
                    httpHosts = httpHosts,
                    proxyStatus = httpGraphs.status,
                    queueBars = queueBars,
                    selectedHttpHostIndex = httpHosts
                        .indexOfFirst { it.host == selectedHttpHost }
                        .coerceAtLeast(0),
                    selectedHttpResourceUrl = selectedHttpResourceUrl,
                    commandPalette = commandPalette,
                    activeDetailPanel = activeDashboardPanel,
                    headerElapsedNanos = System.nanoTime() - headerStartedNanos,
                )
            },
            width = terminal.size.width,
            height = terminal.size.height,
            styles = DashboardStyles.colorful,
        )
        if (frame == lastRenderedFrame) return
        lastRenderedFrame = frame
        animation.update(frame)
    }

    override fun close() {
        configurationSubscription.close()
        work.close()
    }

    override fun closeBefore(deadlineNanos: Long) {
        configurationSubscription.close()
        work.closeBefore(deadlineNanos)
    }

    private fun teardown() {
        // A pending scheduled redraw might not have entered the FIFO before
        // shutdown; paint all already-accepted observations one last time.
        try {
            if (work.fatalFailure == null && screenStarted) frames.flush()
        } finally {
            closed = true
            headerTimer.shutdownNow()
            commandRegistrations.forEach(AutoCloseable::close)
            keyboardInput?.close()
            keyboardInput = null
            animation.stop()
            if (screenStarted) leaveScreen()
        }
    }

    private companion object {
        const val MAX_LOG_LINES = 250
        const val MAX_DNS_LOOKUPS = 40
        const val MAX_TRANSFERS = 20
        const val INPUT_POLL_MILLIS = 200L
        const val ENTER_ALTERNATE_SCREEN = "\u001B[?1049h"
        const val LEAVE_ALTERNATE_SCREEN = "\u001B[?1049l"
        const val HIDE_CURSOR = "\u001B[?25l"
        const val SHOW_CURSOR = "\u001B[?25h"
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    }
}

internal fun baudPaletteRates(currentBaud: Int): List<Int> =
    (listOf(9_600, 19_200, 38_400, 57_600, 115_200) + currentBaud)
        .distinct()
        .sorted()

internal data class DashboardDnsLookup(
    val key: String,
    val time: String,
    val transport: String,
    val name: String,
    val type: String,
    val status: String,
    val answers: Int?,
    val bytes: Int,
)

internal data class DashboardTransfer(
    val flowId: String,
    val destination: String,
    val via: String,
    val kind: TransferKind,
    val state: TransferState,
    val toHostBytes: Long,
    val toPeerBytes: Long,
    val startedNanos: Long,
    val updatedNanos: Long,
    val detail: String?,
) {
    val bytesPerSecond: Double?
        get() {
            val elapsed = updatedNanos - startedNanos
            val bytes = toHostBytes + toPeerBytes
            if (elapsed <= 0 || bytes <= 0) return null
            return bytes.toDouble() * 1_000_000_000.0 / elapsed.toDouble()
        }
}

internal data class DashboardHttpResource(
    val url: String,
    val state: ResourceState = ResourceState.DISCOVERED,
    val kind: ResourceKind = ResourceKind.OTHER,
    val prefetched: Boolean = false,
    val transformations: List<ResourceTransformationSummary> = emptyList(),
)

internal data class DashboardHttpGraphMetadata(
    val rootUri: URI,
    val usageCount: Long,
    val lastUsedAt: Instant?,
    val associations: Set<ResourceEdgeSnapshot>,
)

internal data class DashboardHttpHost(
    val host: String,
    val resources: List<DashboardHttpResource>,
    val expanded: Boolean,
    val useCount: Long = 0,
    val lastUsedAt: Instant? = null,
    val savedBytes: Long =
        resources.sumOf { resource ->
            resource.transformations.sumOf { it.savedBytes.toLong() }
        },
)

internal enum class TuiPaletteMode {
    COMMANDS,
    PORTS,
    BAUD,
    FLOW_CONTROL,
    DNS,
    HTTP,
    TONE,
    LOG_LEVEL,
    CAPTURE_LIMIT,
    LOGIN,
}

private fun paletteDisplayInput(palette: TuiCommandPalette): String =
    if (palette.mode == TuiPaletteMode.LOGIN) {
        maskLoginPassword(palette.input)
    } else {
        palette.input
    }

internal data class TuiCommandOption(
    val label: String,
    val description: String,
    val value: String,
)

internal data class TuiCommandPalette(
    val mode: TuiPaletteMode,
    val input: String,
    val title: String,
    val options: List<TuiCommandOption>,
    val selectedIndex: Int,
    val palettePath: String = "/",
)

internal data class DashboardState(
    val portName: String?,
    val baud: Int,
    val flowControl: SerialFlowControl = SerialFlowControl.DISABLED,
    val connected: Boolean,
    val dnsUpstream: String,
    val httpProxyEnabled: Boolean,
    val toneSimulationEnabled: Boolean = true,
    val logs: List<String>,
    val dnsLookups: List<DashboardDnsLookup>,
    val transfers: List<DashboardTransfer>,
    val httpHosts: List<DashboardHttpHost>,
    val selectedHttpHostIndex: Int,
    val selectedHttpResourceUrl: String? = null,
    val commandPalette: TuiCommandPalette?,
    val activeDetailPanel: DashboardDetailPanel = DashboardDetailPanel.PROXY,
    val headerElapsedNanos: Long = 0,
    val proxyStatus: String? = null,
    val queueBars: QueueBars? = null,
)

internal enum class DashboardDetailPanel(val title: String) {
    DNS("DNS lookups"),
    TRANSFERS("Transfers"),
    PROXY("HTTP / HTTPS compatibility proxy");

    fun next(): DashboardDetailPanel = entries[(ordinal + 1) % entries.size]
}

private const val WIDE_DASHBOARD_THRESHOLD = 149
private const val MIN_QUEUE_BAR_HEIGHT = 30

/**
 * Queue occupancy excludes the currently executing event or work slot.
 * A live capacity reduction may temporarily leave queued > capacity;
 * retain the true count while the existing work drains.
 */
internal data class QueueOccupancy(val queued: Int, val capacity: Int) {
    init {
        require(capacity > 0)
        require(queued >= 0)
    }
}

internal data class QueueBars(
    val broker: QueueOccupancy,
    val tui: QueueOccupancy,
)

internal data class DashboardStyles(
    val border: (String) -> String = { it },
    val title: (String) -> String = { it },
    val accent: (String) -> String = { it },
    val success: (String) -> String = { it },
    val warning: (String) -> String = { it },
    val danger: (String) -> String = { it },
    val muted: (String) -> String = { it },
    val selected: (String) -> String = { it },
    val resourceKnown: (String) -> String = { it },
    val resourceActive: (String) -> String = { it },
    val resourceReady: (String) -> String = { it },
    val resourceFailed: (String) -> String = { it },
) {
    companion object {
        val colorful = DashboardStyles(
            border = { brightBlue(it) },
            title = { (brightBlue + bold)(it) },
            accent = { cyan(it) },
            success = { (brightGreen + bold)(it) },
            warning = { (yellow + bold)(it) },
            danger = { (brightRed + bold)(it) },
            muted = { gray(0.55)(it) },
            selected = { (brightGreen + bold + inverse)(it) },
            resourceKnown = { gray(0.72)(it) },
            resourceActive = { yellow(it) },
            resourceReady = { brightGreen(it) },
            resourceFailed = { brightRed(it) },
        )
    }
}

private enum class DashboardTone {
    NORMAL,
    ACCENT,
    SUCCESS,
    WARNING,
    DANGER,
    MUTED,
    SELECTED,
    RESOURCE_KNOWN,
    RESOURCE_ACTIVE,
    RESOURCE_READY,
    RESOURCE_FAILED,
}

private data class DashboardLine(
    val text: String,
    val tone: DashboardTone = DashboardTone.NORMAL,
)

internal object YameDashboardRenderer {
    private const val MIN_WIDTH = 72
    private const val MIN_HEIGHT = 22

    fun render(
        state: DashboardState,
        width: Int = 120,
        height: Int = 34,
        styles: DashboardStyles = DashboardStyles(),
    ): String {
        val renderWidth = width.coerceAtLeast(1)
        val renderHeight = height.coerceAtLeast(1)

        if (renderWidth < MIN_WIDTH || renderHeight < MIN_HEIGHT) {
            return renderCompact(state, renderWidth, renderHeight, styles)
        }

        val headerHeight = 1
        val footerHeight = 1
        if (renderWidth <= WIDE_DASHBOARD_THRESHOLD) {
            return renderStackedBody(state, renderWidth, renderHeight, styles)
        }
        val showQueueBars = state.queueBars != null && renderHeight >= MIN_QUEUE_BAR_HEIGHT
        val queueBarHeight = if (showQueueBars) 1 else 0
        val bodyHeight = renderHeight - headerHeight - footerHeight - queueBarHeight
        val gap = 1
        val leftWidth = (renderWidth * 55 / 100)
            .coerceIn(38, renderWidth - 32)
        val rightWidth = renderWidth - leftWidth - gap

        val dnsHeight = (bodyHeight * 35 / 100).coerceAtLeast(7)
        val transferHeight = (bodyHeight * 30 / 100).coerceAtLeast(6)
        val lowerHeight = bodyHeight - dnsHeight - transferHeight

        val left = panel(
            title = "Log",
            width = leftWidth,
            height = bodyHeight,
            lines = logLines(state.logs, bodyHeight - 2),
            styles = styles,
        )

        val dns = panel(
            title = "DNS lookups",
            width = rightWidth,
            height = dnsHeight,
            lines = dnsLines(
                state.dnsLookups,
                visibleRows = dnsHeight - 2,
                contentWidth = rightWidth - 4,
            ),
            styles = styles,
        )

        val transfer = panel(
            title = "Transfers",
            width = rightWidth,
            height = transferHeight,
            lines = transferLines(
                state.transfers,
                visibleRows = transferHeight - 2,
                contentWidth = rightWidth - 4,
            ),
            styles = styles,
        )

        val lower = if (state.commandPalette != null) {
            panel(
                title = state.commandPalette.title,
                width = rightWidth,
                height = lowerHeight,
                lines = paletteLines(
                    state.commandPalette,
                    visibleRows = lowerHeight - 2,
                    contentWidth = rightWidth - 4,
                ),
                styles = styles,
            )
        } else {
            panel(
                title = "HTTP / HTTPS compatibility proxy",
                width = rightWidth,
                height = lowerHeight,
                lines = httpHostLines(
                    state.httpHosts,
                    state.selectedHttpHostIndex,
                    state.selectedHttpResourceUrl,
                    lowerHeight - 2,
                    rightWidth - 4,
                    state.proxyStatus,
                ),
                styles = styles,
            )
        }

        val right = dns + transfer + lower
        val body = (0 until bodyHeight).joinToString("\n") { index ->
            left[index] + " ".repeat(gap) + right[index]
        }

        val header = styles.title(DashboardHeader.render(state, renderWidth))

        val footerText = if (state.commandPalette == null) {
            "[↑/↓] select   [Enter/Right] expand   [Left] collapse   [/] commands   [q/Ctrl-C] quit"
        } else {
            "[↑/↓] select   [Enter] apply   [Esc] " +
                if (state.commandPalette.palettePath == "/") "close palette" else "parent palette"
        }
        val footer = styles.muted(
            clip(footerText, renderWidth).padEnd(renderWidth),
        )

        val queueFooter = if (showQueueBars) {
            val queues = requireNotNull(state.queueBars)
            val space = "  "
            val firstWidth = (renderWidth - space.length) / 2
            val secondWidth = renderWidth - space.length - firstWidth
            "\n" + queueBar("Event broker", queues.broker, "events", firstWidth, styles) +
                space + queueBar("TUI work", queues.tui, "slots", secondWidth, styles)
        } else ""
        return "$header\n$body$queueFooter\n$footer"
    }

    /**
     * A compact single-row meter with its title, queue usage and percentage
     * centered *inside* the track. The underlying filled/unfilled cells are
     * preserved on either side of the caption.
     */
    private fun queueBar(
        label: String,
        usage: QueueOccupancy,
        unit: String,
        width: Int,
        styles: DashboardStyles,
    ): String {
        val percent = (usage.queued.toLong() * 100 / usage.capacity).toInt()
        val trackWidth = width - 2 // Opening and closing brackets.
        val filled = if (usage.queued == 0) 0 else
            ((usage.queued.toLong() * trackWidth + usage.capacity - 1) / usage.capacity)
                .toInt().coerceIn(1, trackWidth)
        val caption = "$label ${usage.queued}/${usage.capacity} $unit ($percent%)"
        require(caption.length <= trackWidth) { "Queue bar too narrow for its caption" }
        val captionStart = (trackWidth - caption.length) / 2
        val paint = when {
            percent >= 90 -> styles.danger
            percent >= 70 -> styles.warning
            else -> styles.success
        }
        fun trackSegment(from: Int, until: Int): String {
            val full = (filled - from).coerceIn(0, until - from)
            val empty = until - from - full
            return (if (full > 0) paint("█".repeat(full)) else "") +
                (if (empty > 0) styles.muted("░".repeat(empty)) else "")
        }
        return "[" + trackSegment(0, captionStart) + styles.accent(caption) +
            trackSegment(captionStart + caption.length, trackWidth) + "]"
    }

    private fun renderStackedBody(
        state: DashboardState,
        width: Int,
        height: Int,
        styles: DashboardStyles,
    ): String {
        val bodyHeight = height - 2
        val logHeight = (bodyHeight * 55 / 100).coerceIn(3, bodyHeight - 3)
        val detailHeight = bodyHeight - logHeight
        val log = panel(
            title = "Log",
            width = width,
            height = logHeight,
            lines = logLines(state.logs, logHeight - 2),
            styles = styles,
        )
        val detail = when (val palette = state.commandPalette) {
            null -> when (state.activeDetailPanel) {
                DashboardDetailPanel.DNS -> panel(
                    title = DashboardDetailPanel.DNS.title,
                    width = width,
                    height = detailHeight,
                    lines = dnsLines(state.dnsLookups, detailHeight - 2, width - 4),
                    styles = styles,
                )
                DashboardDetailPanel.TRANSFERS -> panel(
                    title = DashboardDetailPanel.TRANSFERS.title,
                    width = width,
                    height = detailHeight,
                    lines = transferLines(state.transfers, detailHeight - 2, width - 4),
                    styles = styles,
                )
                DashboardDetailPanel.PROXY -> panel(
                    title = DashboardDetailPanel.PROXY.title,
                    width = width,
                    height = detailHeight,
                    lines = httpHostLines(
                        state.httpHosts,
                        state.selectedHttpHostIndex,
                        state.selectedHttpResourceUrl,
                        detailHeight - 2,
                        width - 4,
                        state.proxyStatus,
                    ),
                    styles = styles,
                )
            }
            else -> panel(
                title = palette.title,
                width = width,
                height = detailHeight,
                lines = paletteLines(palette, detailHeight - 2, width - 4),
                styles = styles,
            )
        }
        val footerText = if (state.commandPalette == null) {
            "[Tab] DNS / Transfers / Proxy  [↑/↓] select  [Enter/→] expand  [←] collapse  [/] commands  [q] quit"
        } else {
            "[↑/↓] select   [Enter] apply   [Esc] " +
                if (state.commandPalette.palettePath == "/") "close palette" else "parent palette"
        }
        return buildString {
            append(styles.title(DashboardHeader.render(state, width)))
            append('\n')
            append((log + detail).joinToString("\n"))
            append('\n')
            append(styles.muted(clip(footerText, width).padEnd(width)))
        }
    }

    private fun renderCompact(
        state: DashboardState,
        width: Int,
        height: Int,
        styles: DashboardStyles,
    ): String {
        val lines = mutableListOf(
            DashboardLine(DashboardHeader.render(state, width), DashboardTone.ACCENT),
        )
        state.dnsLookups.lastOrNull()?.let {
            lines += DashboardLine(
                "DNS ${it.transport.take(1)} ${it.type} ${it.name}  ${it.status}",
                dnsTone(it.status),
            )
        }
        state.transfers
            .sortedWith(compareBy<DashboardTransfer> { transferSort(it.state) }.thenByDescending { it.updatedNanos })
            .firstOrNull()
            ?.let {
                lines += DashboardLine(compactTransfer(it), transferTone(it.state))
            }
        state.httpHosts.getOrNull(state.selectedHttpHostIndex)?.let { host ->
            val marker = if (host.expanded) "v" else ">"
            lines += DashboardLine(
                "HTTP $marker ${host.host} (${host.resources.size})" +
                    if (host.savedBytes > 0) " | saved ${formatBytes(host.savedBytes)}" else "",
                DashboardTone.ACCENT,
            )
        }
        state.logs.lastOrNull()?.let {
            lines += DashboardLine(it)
        }
        val palette = state.commandPalette
        if (palette == null) {
            lines += DashboardLine("[/] commands  [q] quit", DashboardTone.MUTED)
        } else {
            val selected = palette.options.getOrNull(
                palette.selectedIndex.coerceIn(
                    0,
                    (palette.options.size - 1).coerceAtLeast(0),
                ),
            )
            val selection = selected?.let { "  > ${it.label}" }.orEmpty()
            lines.add(
                1,
                DashboardLine(
                    "Palette: ${paletteDisplayInput(palette)}$selection",
                    if (selected == null) DashboardTone.ACCENT else DashboardTone.SELECTED,
                ),
            )
        }

        return (0 until height).joinToString("\n") { index ->
            val line = lines.getOrNull(index) ?: DashboardLine("")
            applyTone(
                clip(line.text, width).padEnd(width),
                line.tone,
                styles,
            )
        }
    }

    private fun logLines(
        logs: List<String>,
        visibleRows: Int,
    ): List<DashboardLine> =
        if (logs.isEmpty()) {
            listOf(DashboardLine("(waiting for modem activity)", DashboardTone.MUTED))
        } else {
            logs.takeLast(visibleRows.coerceAtLeast(1)).map { DashboardLine(it) }
        }

    private fun dnsLines(
        lookups: List<DashboardDnsLookup>,
        visibleRows: Int,
        contentWidth: Int,
    ): List<DashboardLine> {
        if (lookups.isEmpty()) {
            return listOf(DashboardLine("(no DNS lookups yet)", DashboardTone.MUTED))
        }

        return lookups
            .takeLast(visibleRows.coerceAtLeast(1))
            .reversed()
            .map { lookup ->
                val protocol = if (lookup.transport == "TCP") "T" else "U"
                val answers = lookup.answers?.let { " $it ans" } ?: ""
                DashboardLine(
                    clip(
                        "${lookup.time} $protocol ${lookup.type.padEnd(5)} ${lookup.name}  ${lookup.status}$answers",
                        contentWidth,
                    ),
                    dnsTone(lookup.status),
                )
            }
    }

    private fun transferLines(
        transfers: List<DashboardTransfer>,
        visibleRows: Int,
        contentWidth: Int,
    ): List<DashboardLine> {
        if (transfers.isEmpty()) {
            return listOf(DashboardLine("(no TCP transfers yet)", DashboardTone.MUTED))
        }

        return transfers
            .sortedWith(
                compareBy<DashboardTransfer> { transferSort(it.state) }
                    .thenByDescending { it.updatedNanos },
            )
            .take(visibleRows.coerceAtLeast(1))
            .map { transfer ->
                val kind = if (transfer.kind == TransferKind.DNS) "DNS" else "TCP"
                val symbol = when (transfer.state) {
                    TransferState.CONNECTING -> "◌"
                    TransferState.OPEN -> "●"
                    TransferState.CLOSED -> "✓"
                    TransferState.FAILED -> "✗"
                }
                val text =
                    "$symbol $kind ${transfer.destination}  " +
                        "↑${formatBytes(transfer.toHostBytes)} " +
                        "↓${formatBytes(transfer.toPeerBytes)} " +
                        formatRate(transfer.bytesPerSecond)
                DashboardLine(clip(text, contentWidth), transferTone(transfer.state))
            }
    }

    private fun httpHostLines(
        hosts: List<DashboardHttpHost>,
        selectedHostIndex: Int,
        selectedResourceUrl: String?,
        visibleRows: Int,
        contentWidth: Int,
        status: String? = null,
    ): List<DashboardLine> {
        if (status != null) return listOf(DashboardLine(clip(status, contentWidth), DashboardTone.WARNING)) +
            if (visibleRows > 1) httpHostLines(hosts, selectedHostIndex, selectedResourceUrl,
                visibleRows - 1, contentWidth) else emptyList()

        if (hosts.isEmpty()) {
            return listOf(DashboardLine("(no compatibility proxy hosts yet)", DashboardTone.MUTED))
        }

        val selected = selectedHostIndex.coerceIn(0, hosts.lastIndex)
        val rows = visibleRows.coerceAtLeast(1)
        val selectedResourceIndex =
            hosts[selected].resources.indexOfFirst { it.url == selectedResourceUrl }
                .takeIf { selectedResourceUrl != null && it >= 0 }
        val selectedLine =
            hosts.take(selected).sumOf { host ->
                1 + if (host.expanded) host.resources.size else 0
            } +
                if (selectedResourceIndex != null && hosts[selected].expanded) {
                    1 + selectedResourceIndex
                } else {
                    0
                }
        val itemCount = hosts.sumOf { host ->
            1 + if (host.expanded) host.resources.size else 0
        }
        val start = viewportStart(
            selectedIndex = selectedLine.coerceIn(0, (itemCount - 1).coerceAtLeast(0)),
            itemCount = itemCount,
            visibleRows = rows,
        )

        return sequence {
            hosts.forEachIndexed { index, host ->
                val marker = if (host.expanded) "v" else ">"
                val hostSelected = index == selected && selectedResourceIndex == null
                val selection = if (hostSelected) ">" else " "
                yield(
                    DashboardLine(
                        clip(
                            "$selection $marker ${host.host}  (${host.resources.size})" +
                                if (host.savedBytes > 0) " | saved ${formatBytes(host.savedBytes)}" else "",
                            contentWidth,
                        ),
                        if (hostSelected) DashboardTone.SELECTED else DashboardTone.ACCENT,
                    ),
                )
                if (host.expanded) {
                    host.resources.forEach { resource ->
                        val path = runCatching { URI(resource.url) }.getOrNull()?.let { uri ->
                            buildString {
                                append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                                uri.rawQuery?.let { append('?').append(it) }
                            }
                        } ?: resource.url
                        val summary = resource.transformations.lastOrNull()
                        val transformText = summary?.let(::formatTransformationSummary).orEmpty()
                        val prefetchText = if (resource.prefetched) " [P]" else ""
                        val statusText =
                            when (resource.state) {
                                ResourceState.DISCOVERED -> "."
                                ResourceState.FETCHING,
                                ResourceState.SOURCE_READY,
                                ResourceState.TRANSFORMING -> "~"
                                ResourceState.READY -> "*"
                                ResourceState.FAILED -> "!"
                            }
                        val tone =
                            when (resource.state) {
                                ResourceState.DISCOVERED -> DashboardTone.RESOURCE_KNOWN
                                ResourceState.FETCHING,
                                ResourceState.SOURCE_READY,
                                ResourceState.TRANSFORMING -> DashboardTone.RESOURCE_ACTIVE
                                ResourceState.READY -> DashboardTone.RESOURCE_READY
                                ResourceState.FAILED -> DashboardTone.RESOURCE_FAILED
                            }
                        val resourceSelected =
                            index == selected &&
                                resource.url == selectedResourceUrl
                        yield(
                            DashboardLine(
                                formatHttpResourceLine(
                                    selection = if (resourceSelected) ">" else " ",
                                    statusText = statusText,
                                    path = path,
                                    suffix = "$prefetchText$transformText",
                                    contentWidth = contentWidth,
                                ),
                                if (resourceSelected) DashboardTone.SELECTED else tone,
                            ),
                        )
                    }
                }
            }
        }.drop(start).take(rows).toList()
    }

    private fun formatHttpResourceLine(
        selection: String,
        statusText: String,
        path: String,
        suffix: String,
        contentWidth: Int,
    ): String {
        val prefix = "$selection  $statusText "
        val minimumPathWidth = 12
        val suffixWidth = (contentWidth - prefix.length - minimumPathWidth).coerceAtLeast(0)
        val visibleSuffix = clip(suffix, suffixWidth)
        val pathWidth = (contentWidth - prefix.length - visibleSuffix.length).coerceAtLeast(0)
        return clip(prefix + middleClip(path, pathWidth) + visibleSuffix, contentWidth)
    }

    private fun middleClip(value: String, maxLength: Int): String {
        val safeValue = sanitizeTerminalText(value)
        if (maxLength <= 0) return ""
        if (safeValue.length <= maxLength) return safeValue
        if (maxLength == 1) return "…"
        val tailLength = ((maxLength - 1) * 3 / 4).coerceAtLeast(1)
        val headLength = maxLength - 1 - tailLength
        return safeValue.take(headLength) + "…" + safeValue.takeLast(tailLength)
    }
    private fun paletteLines(
        palette: TuiCommandPalette,
        visibleRows: Int,
        contentWidth: Int,
    ): List<DashboardLine> {
        val result = mutableListOf<DashboardLine>()
        result += DashboardLine(
            "> ${paletteDisplayInput(palette)}",
            DashboardTone.ACCENT,
        )

        if (palette.mode == TuiPaletteMode.DNS) {
            result += DashboardLine("Enter an IPv4 address, then press Enter.", DashboardTone.MUTED)
            return result.take(visibleRows)
        }

        if (palette.mode == TuiPaletteMode.CAPTURE_LIMIT) {
            result += DashboardLine("Enter a positive integer, then press Enter.", DashboardTone.MUTED)
            return result.take(visibleRows)
        }

        if (palette.mode == TuiPaletteMode.LOGIN) {
            result += DashboardLine("Enter username:password, then press Enter.", DashboardTone.MUTED)
            return result.take(visibleRows)
        }

        if (palette.options.isEmpty()) {
            result += DashboardLine("(no matches)", DashboardTone.MUTED)
            return result.take(visibleRows)
        }

        val optionRows = (visibleRows - 1).coerceAtLeast(0)
        if (optionRows == 0) return result.take(visibleRows)

        val selected = palette.selectedIndex.coerceIn(0, palette.options.lastIndex)
        val start = viewportStart(
            selectedIndex = selected,
            itemCount = palette.options.size,
            visibleRows = optionRows,
        )

        palette.options
            .drop(start)
            .take(optionRows)
            .forEachIndexed { offset, option ->
                val index = start + offset
                val marker = if (index == selected) ">" else " "
                val description = option.description
                    .takeIf { it.isNotBlank() }
                    ?.let { "  $it" }
                    .orEmpty()
                result += DashboardLine(
                    clip("$marker ${option.label}$description", contentWidth),
                    if (index == selected) DashboardTone.SELECTED else DashboardTone.NORMAL,
                )
            }
        return result
    }

    private fun panel(
        title: String,
        width: Int,
        height: Int,
        lines: List<DashboardLine>,
        styles: DashboardStyles,
    ): List<String> {
        val safeWidth = width.coerceAtLeast(8)
        val safeHeight = height.coerceAtLeast(3)
        val titleText = "─ ${clip(title, safeWidth - 6)} "
        val topFill = "─".repeat((safeWidth - titleText.length - 2).coerceAtLeast(0))
        val result = mutableListOf<String>()
        result +=
            styles.border("┌") +
                styles.border(titleText) +
                styles.border(topFill) +
                styles.border("┐")

        val contentWidth = safeWidth - 4
        repeat(safeHeight - 2) { index ->
            val line = lines.getOrNull(index) ?: DashboardLine("")
            result +=
                styles.border("│") +
                    " " +
                    applyTone(
                        clip(line.text, contentWidth).padEnd(contentWidth),
                        line.tone,
                        styles,
                    ) +
                    " " +
                    styles.border("│")
        }

        result +=
            styles.border("└") +
                styles.border("─".repeat(safeWidth - 2)) +
                styles.border("┘")
        return result
    }

    private fun viewportStart(
        selectedIndex: Int,
        itemCount: Int,
        visibleRows: Int,
    ): Int {
        if (itemCount <= visibleRows) return 0
        val half = visibleRows / 2
        return (selectedIndex - half)
            .coerceIn(0, itemCount - visibleRows)
    }

    private fun transferSort(state: TransferState): Int =
        when (state) {
            TransferState.OPEN -> 0
            TransferState.CONNECTING -> 1
            TransferState.FAILED -> 2
            TransferState.CLOSED -> 3
        }

    private fun transferTone(state: TransferState): DashboardTone =
        when (state) {
            TransferState.OPEN -> DashboardTone.WARNING
            TransferState.CONNECTING -> DashboardTone.ACCENT
            TransferState.CLOSED -> DashboardTone.SUCCESS
            TransferState.FAILED -> DashboardTone.DANGER
        }

    private fun dnsTone(status: String): DashboardTone =
        when {
            status == "LOOKUP" -> DashboardTone.WARNING
            status == "FAILED" -> DashboardTone.DANGER
            status.startsWith("NOERROR") -> DashboardTone.SUCCESS
            else -> DashboardTone.ACCENT
        }

    private fun formatTransformationSummary(summary: ResourceTransformationSummary): String {
        val savedPercent =
            if (summary.sourceBytes > 0) {
                (summary.savedBytes * 100L / summary.sourceBytes).coerceIn(0, 100)
            } else {
                0
            }
        val detail = summary.detail?.let { " | $it" }.orEmpty()
        return "  ${formatCompactBytes(summary.sourceBytes.toLong())}->${formatCompactBytes(summary.outputBytes.toLong())} -$savedPercent%$detail"
    }

    private fun formatCompactBytes(bytes: Long): String =
        when {
            bytes >= 1024L * 1024L ->
                "${(bytes.toDouble() / (1024.0 * 1024.0)).let { if (it >= 10) "%.0f".format(it) else "%.1f".format(it) }}M"
            bytes >= 1024L ->
                "${(bytes.toDouble() / 1024.0).let { if (it >= 10) "%.0f".format(it) else "%.1f".format(it) }}K"
            else -> "$bytes"
        }

    private fun compactTransfer(transfer: DashboardTransfer): String =
        "${transfer.kind} ${transfer.destination} ↑${formatBytes(transfer.toHostBytes)} ↓${formatBytes(transfer.toPeerBytes)}"

    private fun formatRate(bytesPerSecond: Double?): String =
        when {
            bytesPerSecond == null || !bytesPerSecond.isFinite() -> "—"
            bytesPerSecond >= 1024.0 * 1024.0 ->
                "%.2f MiB/s".format(bytesPerSecond / (1024.0 * 1024.0))
            bytesPerSecond >= 1024.0 ->
                "%.1f KiB/s".format(bytesPerSecond / 1024.0)
            else -> "%.0f B/s".format(bytesPerSecond)
        }

    private fun formatBytes(bytes: Long): String =
        when {
            bytes >= 1024L * 1024L ->
                "%.2fM".format(bytes.toDouble() / (1024.0 * 1024.0))
            bytes >= 1024L ->
                "%.1fK".format(bytes.toDouble() / 1024.0)
            else -> "$bytes"
        }

    private fun applyTone(
        text: String,
        tone: DashboardTone,
        styles: DashboardStyles,
    ): String =
        when (tone) {
            DashboardTone.NORMAL -> text
            DashboardTone.ACCENT -> styles.accent(text)
            DashboardTone.SUCCESS -> styles.success(text)
            DashboardTone.WARNING -> styles.warning(text)
            DashboardTone.DANGER -> styles.danger(text)
            DashboardTone.MUTED -> styles.muted(text)
            DashboardTone.SELECTED -> styles.selected(text)
            DashboardTone.RESOURCE_KNOWN -> styles.resourceKnown(text)
            DashboardTone.RESOURCE_ACTIVE -> styles.resourceActive(text)
            DashboardTone.RESOURCE_READY -> styles.resourceReady(text)
            DashboardTone.RESOURCE_FAILED -> styles.resourceFailed(text)
        }

    private fun clip(
        value: String,
        maxLength: Int,
    ): String {
        if (maxLength <= 0) return ""
        val safeValue = sanitizeTerminalText(value)
        if (safeValue.length <= maxLength) return safeValue
        if (maxLength == 1) return safeValue.take(1)
        return safeValue.take(maxLength - 1) + "…"
    }

    private fun sanitizeTerminalText(value: String): String =
        buildString(value.length) {
            value.forEach { character ->
                append(if (character.isISOControl()) '�' else character)
            }
        }
}
