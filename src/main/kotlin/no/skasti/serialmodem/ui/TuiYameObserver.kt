package no.skasti.serialmodem.ui

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
import no.skasti.serialmodem.BuildInfo
import no.skasti.serialmodem.logging.YameLogLevel
import no.skasti.serialmodem.logging.YameLogModule
import no.skasti.serialmodem.observer.HttpProxyActionKind
import no.skasti.serialmodem.observer.TransferDirection
import no.skasti.serialmodem.observer.TransferKind
import no.skasti.serialmodem.observer.TransferState
import no.skasti.serialmodem.observer.YameEvent
import no.skasti.serialmodem.ppp.dns.dnsResponseCodeName
import no.skasti.serialmodem.ppp.proxy.ResourceKind
import no.skasti.serialmodem.ppp.proxy.ResourceRegistryResource
import no.skasti.serialmodem.ppp.proxy.ResourceRegistryRoot
import no.skasti.serialmodem.ppp.proxy.ResourceState
import no.skasti.serialmodem.ppp.proxy.transform.ResourceTransformationSummary
import no.skasti.serialmodem.ppp.proxy.ResourceRegistryResource
import no.skasti.serialmodem.ppp.proxy.ResourceRegistryRoot
import no.skasti.serialmodem.serial.SerialPortDescriptor
import no.skasti.serialmodem.serial.SerialFlowControl
import java.net.URI
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class TuiYameObserver(
    initialPortName: String?,
    initialBaud: Int,
    initialFlowControl: SerialFlowControl,
    initialDnsUpstream: String,
    initialHttpProxyEnabled: Boolean,
    initialLogLevels: Map<YameLogModule, YameLogLevel> = emptyMap(),
    private val onQuit: () -> Unit = {},
    private val onPortSelected: (String) -> Unit = {},
    private val onBaudSelected: (Int) -> Unit = {},
    private val onFlowControlSelected: (SerialFlowControl) -> Unit = {},
    private val onDnsUpstreamSelected: (String) -> Unit = {},
    private val onHttpProxySelected: (Boolean) -> Unit = {},
    private val onLogLevelSelected: (YameLogModule, YameLogLevel) -> Unit = { _, _ -> },
    private val onDisconnect: () -> Unit = {},
    private val onReconnect: () -> Unit = {},
    private val onRefreshPorts: () -> Unit = {},
    private val terminal: Terminal = Terminal(interactive = true),
) : AutoCloseable {
    private var portName = initialPortName
    private var baud = initialBaud
    private var flowControl = initialFlowControl
    private var dnsUpstream = initialDnsUpstream
    private var httpProxyEnabled = initialHttpProxyEnabled
    private val logLevels = YameLogModule.entries.associateWith {
        initialLogLevels[it] ?: YameLogLevel.INFO
    }.toMutableMap()
    private var connected = false
    private var availablePorts: List<SerialPortDescriptor> = emptyList()

    private val logs = ArrayDeque<String>()
    private val dnsLookups = mutableListOf<DashboardDnsLookup>()
    private val transfers = linkedMapOf<String, DashboardTransfer>()
    private data class HttpGraphState(
        val rootLegacyUri: URI,
        val resources: LinkedHashMap<URI, DashboardHttpResource> = linkedMapOf(),
        var useCount: Long = 0,
        var lastUsedNanos: Long = 0,
    )

    private val httpGraphs = linkedMapOf<Long, HttpGraphState>()
    private val expandedHttpHosts = linkedSetOf<String>()
    private var selectedHttpHost: String? = null
    private var commandPalette: TuiCommandPalette? = null
    private var keyboardInput: JLineKeyboardInput? = null

    private val animation = terminal.textAnimation<String> { it }
    private var inputThread: Thread? = null
    private var screenStarted = false
    private var lastRenderedFrame: String? = null

    @Volatile
    private var closed = false

    @Volatile
    var stopRequested: Boolean = false
        private set

    @Synchronized
    fun start() {
        if (screenStarted) return
        screenStarted = true
        enterScreen()
        render()
        startInputLoop()
    }

    @Synchronized
    fun updateSettings(
        portName: String?,
        baud: Int,
        flowControl: SerialFlowControl,
        dnsUpstream: String,
        httpProxyEnabled: Boolean,
    ) {
        this.portName = portName
        this.baud = baud
        this.flowControl = flowControl
        this.dnsUpstream = dnsUpstream
        this.httpProxyEnabled = httpProxyEnabled
        render()
    }

    @Synchronized
    fun updateLogLevel(module: YameLogModule, level: YameLogLevel) {
        logLevels[module] = level
        render()
    }

    @Synchronized
    fun updateConnected(value: Boolean) {
        connected = value
        render()
    }

    @Synchronized
    fun closeActiveTransfers(detail: String? = null) {
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

    @Synchronized
    fun updateAvailablePorts(ports: List<SerialPortDescriptor>) {
        val previousPalette = commandPalette
        val selectedValue = previousPalette
            ?.takeIf { it.mode == TuiPaletteMode.PORTS }
            ?.options
            ?.getOrNull(previousPalette.selectedIndex)
            ?.value

        availablePorts = ports

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

    @Synchronized
    fun onLog(message: String) {
        val timestamp = LocalTime.now().format(TIME_FORMAT)
        logs.addLast("$timestamp  $message")
        while (logs.size > MAX_LOG_LINES) {
            logs.removeFirst()
        }
        render()
    }

    @Synchronized
    fun onEvent(event: YameEvent) {
        when (event) {
            is YameEvent.DnsQuery -> {
                val existing = dnsLookups.indexOfFirst { it.key == event.key }
                val lookup = DashboardDnsLookup(
                    key = event.key,
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

            is YameEvent.DnsResponse -> {
                val index = dnsLookups.indexOfFirst { it.key == event.key }
                val status = event.responseCode
                    ?.let(::dnsResponseCodeName)
                    ?: "REPLY"
                val suffix = if (event.truncated) " TC" else ""
                val updated = DashboardDnsLookup(
                    key = event.key,
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

            is YameEvent.DnsFailure -> {
                val index = dnsLookups.indexOfFirst { it.key == event.key }
                if (index >= 0) {
                    dnsLookups[index] = dnsLookups[index].copy(status = "FAILED")
                } else {
                    dnsLookups += DashboardDnsLookup(
                        key = event.key,
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

            is YameEvent.TransferStarted -> {
                transfers.remove(event.flowId)
                transfers[event.flowId] = DashboardTransfer(
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
            }

            is YameEvent.TransferStateChanged -> {
                val transfer = transfers[event.flowId] ?: return
                transfers[event.flowId] = transfer.copy(
                    state = event.state,
                    updatedNanos = System.nanoTime(),
                    detail = event.detail,
                )
                trimTransfers()
            }

            is YameEvent.TransferBytes -> {
                val transfer = transfers[event.flowId] ?: return
                transfers[event.flowId] = transfer.copy(
                    toHostBytes = transfer.toHostBytes +
                        if (event.direction == TransferDirection.TO_HOST) event.bytes else 0,
                    toPeerBytes = transfer.toPeerBytes +
                        if (event.direction == TransferDirection.TO_PEER) event.bytes else 0,
                    updatedNanos = System.nanoTime(),
                )
            }

            is YameEvent.HttpProxyAction -> return
        }
        render()
    }

    @Synchronized
    internal fun handleResourceRootAdded(root: ResourceRegistryRoot) {
        httpGraphs[root.graphId] =
            HttpGraphState(
                rootLegacyUri = root.rootLegacyUri,
                resources =
                    linkedMapOf(
                        root.rootLegacyUri to
                            DashboardHttpResource(
                                url = root.rootLegacyUri.toString(),
                                state = ResourceState.DISCOVERED,
                                kind = ResourceKind.DOCUMENT,
                            ),
                    ),
            )
        normalizeHttpHostSelection()
        render()
    }

    @Synchronized
    internal fun handleResourceRootRemoved(root: ResourceRegistryRoot) {
        if (httpGraphs.remove(root.graphId) == null) return
        normalizeHttpHostSelection()
        render()
    }

    @Synchronized
    internal fun handleResourceRootUsed(root: ResourceRegistryRoot) {
        if (!touchHttpGraph(root.graphId)) return
        normalizeHttpHostSelection()
        render()
    }

    @Synchronized
    internal fun handleResourceAdded(resource: ResourceRegistryResource) {
        val resources = httpGraphs[resource.graphId]?.resources ?: return
        val changed = resource.resourceLegacyUri !in resources
        resources[resource.resourceLegacyUri] = resource.toDashboardResource()
        if (!changed) return
        normalizeHttpHostSelection()
        render()
    }

    @Synchronized
    internal fun handleResourceUpdated(resource: ResourceRegistryResource) {
        val graph = httpGraphs[resource.graphId] ?: return
        graph.resources[resource.resourceLegacyUri] = resource.toDashboardResource()
        normalizeHttpHostSelection()
        render()
    }

    @Synchronized
    internal fun handleResourceRemoved(resource: ResourceRegistryResource) {
        val resources = httpGraphs[resource.graphId]?.resources ?: return
        val changed = resources.remove(resource.resourceLegacyUri) != null
        if (!changed) return
        normalizeHttpHostSelection()
        render()
    }

    private fun ResourceRegistryResource.toDashboardResource() =
        DashboardHttpResource(
            url = resourceLegacyUri.toString(),
            state = state,
            kind = kind,
            prefetched = prefetched,
            transformations = transformations,
        )

    private fun touchHttpGraph(graphId: Long): Boolean {
        val graph = httpGraphs[graphId] ?: return false
        graph.useCount++
        graph.lastUsedNanos = System.nanoTime()
        return true
    }

    private fun httpHostSnapshots(): List<DashboardHttpHost> {
        data class Aggregate(
            val resources: LinkedHashMap<String, DashboardHttpResource> = linkedMapOf(),
            var useCount: Long = 0,
            var lastUsedNanos: Long = 0,
        )

        val hosts = linkedMapOf<String, Aggregate>()
        httpGraphs.values.forEach { graph ->
            val graphHosts = linkedSetOf<String>()
            graph.resources.forEach { (uri, resource) ->
                val host = uri.host?.lowercase() ?: return@forEach
                val aggregate = hosts.getOrPut(host) { Aggregate() }
                aggregate.resources[uri.toString()] = resource
                aggregate.lastUsedNanos = maxOf(aggregate.lastUsedNanos, graph.lastUsedNanos)
                graphHosts += host
            }
            graphHosts.forEach { host ->
                hosts.getValue(host).useCount += graph.useCount
            }
        }

        return hosts
            .map { (host, aggregate) ->
                DashboardHttpHost(
                    host = host,
                    resources = aggregate.resources.values.toList(),
                    expanded = host in expandedHttpHosts,
                    useCount = aggregate.useCount,
                    lastUsedNanos = aggregate.lastUsedNanos,
                )
            }
            .sortedWith(
                compareByDescending<DashboardHttpHost> { it.lastUsedNanos }
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
    }

    @Synchronized
    private fun moveHttpHostSelection(delta: Int) {
        val hosts = httpHostSnapshots()
        if (hosts.isEmpty()) return
        val current = hosts.indexOfFirst { it.host == selectedHttpHost }
            .takeIf { it >= 0 }
            ?: 0
        val next = (current + delta).coerceIn(0, hosts.lastIndex)
        selectedHttpHost = hosts[next].host
        render()
    }

    @Synchronized
    private fun setSelectedHttpHostExpanded(expanded: Boolean) {
        val host = selectedHttpHost ?: return
        if (expanded) expandedHttpHosts += host else expandedHttpHosts -= host
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

        inputThread = Thread(
            {
                try {
                    JLineKeyboardInput.open().use { keyboard ->
                        synchronized(this) {
                            keyboardInput = keyboard
                        }
                        try {
                            while (!closed && !stopRequested) {
                                val key = keyboard.readKey(INPUT_POLL_MILLIS)
                                    ?: continue
                                val paletteOpen = synchronized(this) { commandPalette != null }
                                when {
                                    key == "Ctrl+C" -> requestQuit()
                                    paletteOpen -> handlePaletteKey(key)
                                    key == "/" -> openCommandPalette()
                                    key.equals("q", ignoreCase = true) -> requestQuit()
                                    key == "ArrowUp" -> moveHttpHostSelection(-1)
                                    key == "ArrowDown" -> moveHttpHostSelection(1)
                                    key == "ArrowRight" || key == "Enter" -> setSelectedHttpHostExpanded(true)
                                    key == "ArrowLeft" -> setSelectedHttpHostExpanded(false)
                                }
                            }
                        } finally {
                            synchronized(this) {
                                if (keyboardInput === keyboard) {
                                    keyboardInput = null
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (!closed && !stopRequested) {
                        onLog(
                            "Keyboard input unavailable: " +
                                (error.message ?: error.javaClass.simpleName),
                        )
                    }
                }
            },
            "yame-tui-input",
        ).apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    private fun openCommandPalette() {
        commandPalette = commandPaletteFor("/")
        render()
    }

    @Synchronized
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

    @Synchronized
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

    @Synchronized
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

    @Synchronized
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

    @Synchronized
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

    @Synchronized
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
        val action = synchronized(this) {
            val palette = commandPalette ?: return

            val pendingAction = when {
                key == "Escape" -> {
                    commandPalette = null
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
                            val next = palette.input.dropLast(1).ifEmpty { "/" }
                            commandPalette = commandPaletteFor(next)
                        }
                        TuiPaletteMode.DNS ->
                            commandPalette = palette.copy(input = palette.input.dropLast(1))
                        else -> Unit
                    }
                    null
                }
                palette.mode == TuiPaletteMode.COMMANDS &&
                    key.length == 1 &&
                    !key[0].isISOControl() -> {
                    commandPalette = commandPaletteFor(palette.input + key)
                    null
                }
                palette.mode == TuiPaletteMode.DNS &&
                    key.length == 1 &&
                    (key[0].isDigit() || key[0] == '.') -> {
                    commandPalette = palette.copy(input = palette.input + key)
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

        if (palette.mode == TuiPaletteMode.DNS) {
            if (palette.input.isBlank()) return null
            commandPalette = null
            val value = palette.input
            return { onDnsUpstreamSelected(value) }
        }

        val selected = palette.options.getOrNull(palette.selectedIndex) ?: return null
        return when (palette.mode) {
            TuiPaletteMode.COMMANDS -> when (selected.value) {
                "/port" -> {
                    { onRefreshPorts(); openPortPalette() }
                }
                "/baud" -> {
                    openBaudPalette()
                    null
                }
                "/flow-control" -> {
                    openFlowControlPalette()
                    null
                }
                "/dns-upstream" -> {
                    openDnsPalette()
                    null
                }
                "/http-proxy" -> {
                    openHttpPalette()
                    null
                }
                "/loglevel-modem" -> {
                    openLogLevelPalette(YameLogModule.MODEM)
                    null
                }
                "/loglevel-serial" -> {
                    openLogLevelPalette(YameLogModule.SERIAL)
                    null
                }
                "/loglevel-ppp" -> {
                    openLogLevelPalette(YameLogModule.PPP)
                    null
                }
                "/loglevel-dns" -> {
                    openLogLevelPalette(YameLogModule.DNS)
                    null
                }
                "/loglevel-proxy" -> {
                    openLogLevelPalette(YameLogModule.PROXY)
                    null
                }
                "/loglevel-transfers" -> {
                    openLogLevelPalette(YameLogModule.TRANSFERS)
                    null
                }
                "/reconnect" -> {
                    commandPalette = null
                    { onReconnect() }
                }
                "/disconnect" -> {
                    commandPalette = null
                    { onDisconnect() }
                }
                "/refresh-ports" -> {
                    commandPalette = null
                    { onRefreshPorts() }
                }
                "/clear-log" -> {
                    logs.clear()
                    commandPalette = null
                    null
                }
                "/quit" -> {
                    commandPalette = null
                    ::requestQuit
                }
                else -> null
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

            TuiPaletteMode.DNS -> null
        }
    }

    private fun commandPaletteFor(input: String): TuiCommandPalette {
        val normalized = if (input.startsWith("/")) input else "/$input"
        return TuiCommandPalette(
            mode = TuiPaletteMode.COMMANDS,
            input = normalized,
            title = "Commands",
            options = COMMAND_OPTIONS.filter {
                it.value.startsWith(normalized, ignoreCase = true)
            },
            selectedIndex = 0,
        )
    }

    private fun requestQuit() {
        val notify = synchronized(this) {
            if (stopRequested) {
                false
            } else {
                stopRequested = true
                true
            }
        }
        if (notify) onQuit()
    }

    @Synchronized
    private fun render() {
        if (closed || !screenStarted) return
        val frame = YameDashboardRenderer.render(
            state = httpHostSnapshots().let { httpHosts ->
                DashboardState(
                    portName = portName,
                    baud = baud,
                    flowControl = flowControl,
                    connected = connected,
                    dnsUpstream = dnsUpstream,
                    httpProxyEnabled = httpProxyEnabled,
                    logs = logs.toList(),
                    dnsLookups = dnsLookups.toList(),
                    transfers = transfers.values.toList(),
                    httpHosts = httpHosts,
                    selectedHttpHostIndex = httpHosts
                        .indexOfFirst { it.host == selectedHttpHost }
                        .coerceAtLeast(0),
                    commandPalette = commandPalette,
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

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        keyboardInput?.close()
        keyboardInput = null
        animation.stop()
        if (screenStarted) {
            leaveScreen()
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
        val COMMON_BAUD_RATES = listOf(9_600, 19_200, 38_400, 57_600, 115_200)
        val COMMAND_OPTIONS = listOf(
            TuiCommandOption("/port", "Select serial port", "/port"),
            TuiCommandOption("/baud", "Select baud rate", "/baud"),
            TuiCommandOption("/flow-control", "Select serial flow control", "/flow-control"),
            TuiCommandOption("/dns-upstream", "Change upstream resolver and reconnect", "/dns-upstream"),
            TuiCommandOption("/http-proxy", "Enable or disable HTTP/TLS compatibility", "/http-proxy"),
            TuiCommandOption("/loglevel-modem", "Set modem file log level", "/loglevel-modem"),
            TuiCommandOption("/loglevel-serial", "Set serial file log level", "/loglevel-serial"),
            TuiCommandOption("/loglevel-ppp", "Set ppp file log level", "/loglevel-ppp"),
            TuiCommandOption("/loglevel-dns", "Set dns file log level", "/loglevel-dns"),
            TuiCommandOption("/loglevel-proxy", "Set proxy file log level", "/loglevel-proxy"),
            TuiCommandOption("/loglevel-transfers", "Set transfers file log level", "/loglevel-transfers"),
            TuiCommandOption("/reconnect", "Open the selected serial port", "/reconnect"),
            TuiCommandOption("/disconnect", "Close the current serial port", "/disconnect"),
            TuiCommandOption("/refresh-ports", "Rescan serial ports", "/refresh-ports"),
            TuiCommandOption("/clear-log", "Clear dashboard log history", "/clear-log"),
            TuiCommandOption("/quit", "Quit YAME", "/quit"),
        )
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

internal data class DashboardHttpHost(
    val host: String,
    val resources: List<DashboardHttpResource>,
    val expanded: Boolean,
    val useCount: Long = 0,
    val lastUsedNanos: Long = 0,
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
    LOG_LEVEL,
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
)

internal data class DashboardState(
    val portName: String?,
    val baud: Int,
    val flowControl: SerialFlowControl = SerialFlowControl.DISABLED,
    val connected: Boolean,
    val dnsUpstream: String,
    val httpProxyEnabled: Boolean,
    val logs: List<String>,
    val dnsLookups: List<DashboardDnsLookup>,
    val transfers: List<DashboardTransfer>,
    val httpHosts: List<DashboardHttpHost>,
    val selectedHttpHostIndex: Int,
    val commandPalette: TuiCommandPalette?,
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
        val bodyHeight = renderHeight - headerHeight - footerHeight
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
                    lowerHeight - 2,
                    rightWidth - 4,
                ),
                styles = styles,
            )
        }

        val right = dns + transfer + lower
        val body = (0 until bodyHeight).joinToString("\n") { index ->
            left[index] + " ".repeat(gap) + right[index]
        }

        val serial = state.portName ?: "no port"
        val connection = if (state.connected) "CONNECTED" else "NOT CONNECTED"
        val proxy = if (state.httpProxyEnabled) "HTTP proxy ON" else "HTTP proxy OFF"
        val headerText =
            "[ YAME ${BuildInfo.display}  •  $serial @ ${state.baud}  •  " +
                "${state.flowControl.displayName}  •  $connection  •  DNS ${state.dnsUpstream}  •  $proxy ]"
        val header = styles.title(
            clip(headerText, renderWidth).padEnd(renderWidth),
        )

        val footerText = if (state.commandPalette == null) {
            "[/] commands   [q/Ctrl-C] quit"
        } else {
            "[↑/↓] select   [Enter] apply   [Esc] close palette"
        }
        val footer = styles.muted(
            clip(footerText, renderWidth).padEnd(renderWidth),
        )

        return "$header\n$body\n$footer"
    }

    private fun renderCompact(
        state: DashboardState,
        width: Int,
        height: Int,
        styles: DashboardStyles,
    ): String {
        val connection = if (state.connected) "CONNECTED" else "NOT CONNECTED"
        val lines = mutableListOf(
            DashboardLine(
                "YAME ${BuildInfo.display}  ${state.portName ?: "no port"} @ ${state.baud}  " +
                    "${state.flowControl.displayName}  $connection",
                DashboardTone.ACCENT,
            ),
            DashboardLine(
                "DNS ${state.dnsUpstream}  HTTP proxy ${if (state.httpProxyEnabled) "ON" else "OFF"}",
                DashboardTone.MUTED,
            ),
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
                    if (host.savedBytes > 0) " · saved ${formatBytes(host.savedBytes)}" else "",
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
                    "Palette: ${palette.input}$selection",
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
        visibleRows: Int,
        contentWidth: Int,
    ): List<DashboardLine> {
        if (hosts.isEmpty()) {
            return listOf(DashboardLine("(no compatibility proxy hosts yet)", DashboardTone.MUTED))
        }

        val selected = selectedHostIndex.coerceIn(0, hosts.lastIndex)
        val rows = visibleRows.coerceAtLeast(1)
        var selectedLine = 0
        for (index in 0 until selected) {
            selectedLine += 1 + if (hosts[index].expanded) hosts[index].resources.size else 0
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
                val selection = if (index == selected) ">" else " "
                yield(
                    DashboardLine(
                        clip(
                            "$selection $marker ${host.host}  (${host.resources.size})" +
                                if (host.savedBytes > 0) " · saved ${formatBytes(host.savedBytes)}" else "",
                            contentWidth,
                        ),
                        if (index == selected) DashboardTone.SELECTED else DashboardTone.ACCENT,
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
                                ResourceState.TRANSFORMING,
                                -> "~"
                                ResourceState.READY -> "*"
                                ResourceState.FAILED -> "!"
                            }
                        val tone =
                            when (resource.state) {
                                ResourceState.DISCOVERED -> DashboardTone.RESOURCE_KNOWN
                                ResourceState.FETCHING,
                                ResourceState.SOURCE_READY,
                                ResourceState.TRANSFORMING,
                                -> DashboardTone.RESOURCE_ACTIVE
                                ResourceState.READY -> DashboardTone.RESOURCE_READY
                                ResourceState.FAILED -> DashboardTone.RESOURCE_FAILED
                            }
                        yield(
                            DashboardLine(
                                clip("    $statusText $path$prefetchText$transformText", contentWidth),
                                tone,
                            ),
                        )
                    }
                }
            }
        }.drop(start).take(rows).toList()
    }
    private fun paletteLines(
        palette: TuiCommandPalette,
        visibleRows: Int,
        contentWidth: Int,
    ): List<DashboardLine> {
        val result = mutableListOf<DashboardLine>()
        result += DashboardLine(
            "> ${palette.input}",
            DashboardTone.ACCENT,
        )

        if (palette.mode == TuiPaletteMode.DNS) {
            result += DashboardLine("Enter an IPv4 address, then press Enter.", DashboardTone.MUTED)
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

    private fun httpTone(kind: HttpProxyActionKind): DashboardTone =
        when (kind) {
            HttpProxyActionKind.ROUTED -> DashboardTone.MUTED
            HttpProxyActionKind.REQUEST -> DashboardTone.ACCENT
            HttpProxyActionKind.REDIRECT -> DashboardTone.WARNING
            HttpProxyActionKind.RESPONSE -> DashboardTone.SUCCESS
            HttpProxyActionKind.ERROR -> DashboardTone.DANGER
        }

    private fun formatTransformationSummary(summary: ResourceTransformationSummary): String {
        val savedPercent =
            if (summary.sourceBytes > 0) {
                (summary.savedBytes * 100L / summary.sourceBytes).coerceIn(0, 100)
            } else {
                0
            }
        val detail = summary.detail?.let { " · $it" }.orEmpty()
        return "  ${formatCompactBytes(summary.sourceBytes.toLong())}→${formatCompactBytes(summary.outputBytes.toLong())} -$savedPercent%$detail"
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
