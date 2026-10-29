package no.skasti.yame.ui

import no.skasti.yame.ppp.session.TransferKind
import no.skasti.yame.ppp.session.TransferState

import no.skasti.yame.ppp.proxy.ResourceKind
import no.skasti.yame.ppp.proxy.ResourceState
import no.skasti.yame.ppp.proxy.transform.ResourceTransformationSummary
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YameDashboardRendererTest {
    @Test
    fun narrowDashboardShowsFullWidthLogAndOnlySelectedDetail() {
        val base = DashboardState(
            portName = "COM1",
            baud = 57600,
            connected = true,
            dnsUpstream = "8.8.8.8",
            httpProxyEnabled = true,
            logs = listOf("10:00:00  " + "full-width-log-message-".repeat(3)),
            dnsLookups = emptyList(),
            transfers = emptyList(),
            httpHosts = emptyList(),
            selectedHttpHostIndex = 0,
            commandPalette = null,
        )
        val proxy = YameDashboardRenderer.render(base, width = 100, height = 30)
        assertContains(proxy, "HTTP / HTTPS compatibility proxy")
        assertContains(proxy, "full-width-log-message-".repeat(3))
        assertFalse(proxy.contains("DNS lookups"))
        assertFalse(proxy.contains("─ Transfers "))
        assertContains(proxy, "[Tab]")
        assertTrue(proxy.lines().all { it.length == 100 }, proxy)

        val dns = YameDashboardRenderer.render(
            base.copy(activeDetailPanel = DashboardDetailPanel.DNS), width = 100, height = 30,
        )
        assertContains(dns, "DNS lookups")
        assertFalse(dns.contains("HTTP / HTTPS compatibility proxy"))

        val transfers = YameDashboardRenderer.render(
            base.copy(activeDetailPanel = DashboardDetailPanel.TRANSFERS), width = 100, height = 30,
        )
        assertContains(transfers, "Transfers")
        assertFalse(transfers.contains("HTTP / HTTPS compatibility proxy"))
    }

    @Test
    fun wideDashboardStartsAt150Columns() {
        val base = DashboardState(
            portName = null,
            baud = 115200,
            connected = false,
            dnsUpstream = "8.8.8.8",
            httpProxyEnabled = true,
            logs = emptyList(),
            dnsLookups = emptyList(),
            transfers = emptyList(),
            httpHosts = emptyList(),
            selectedHttpHostIndex = 0,
            commandPalette = null,
        )
        val narrow = YameDashboardRenderer.render(base, width = 149, height = 30)
        val wide = YameDashboardRenderer.render(base, width = 150, height = 30)
        assertFalse(narrow.contains("DNS lookups"))
        assertContains(wide, "DNS lookups")
        assertContains(wide, "Transfers")
        assertContains(wide, "HTTP / HTTPS compatibility proxy")
        assertEquals(DashboardDetailPanel.DNS, DashboardDetailPanel.PROXY.next())
        assertEquals(DashboardDetailPanel.TRANSFERS, DashboardDetailPanel.DNS.next())
    }

    @Test
    fun wideDashboardShowsLiveQueueCapacityBarsWithoutChangingFrameDimensions() {
        val state = DashboardState(
            portName = null, baud = 115200, connected = false,
            dnsUpstream = "8.8.8.8", httpProxyEnabled = true,
            logs = emptyList(), dnsLookups = emptyList(), transfers = emptyList(),
            httpHosts = emptyList(), selectedHttpHostIndex = 0, commandPalette = null,
            queueBars = QueueBars(
                broker = QueueOccupancy(2048, 4096),
                tui = QueueOccupancy(192, 256),
            ),
        )
        val wide = YameDashboardRenderer.render(state, width = 150, height = 30)
        val lines = wide.lines()
        assertEquals(30, lines.size)
        assertTrue(lines.all { it.length == 150 })
        // Exactly one bar row, with two meters and their labels *inside* the tracks.
        assertContains(lines[28], "Event broker 2048/4096 events (50%)")
        assertContains(lines[28], "TUI work 192/256 slots (75%)")
        assertEquals(4, lines[28].count { it == '[' || it == ']' })
        assertContains(lines[29], "[/] commands")
        assertEquals(1, wide.lines().count { it.contains("░") && it.contains("█") })
        // The same two lines work with the large layouts too.
        val large = YameDashboardRenderer.render(state, width = 200, height = 80)
        assertEquals(80, large.lines().size)
        assertTrue(large.lines().all { it.length == 200 })
        assertContains(large.lines()[78], "Event broker")
        assertContains(large.lines()[78], "TUI work")
        assertFalse(large.lines()[77].contains("Event broker"))
    }

    @Test
    fun queueBarsAreOmittedOnSmallTerminalsAndShortWideTerminals() {
        val state = DashboardState(
            portName = null, baud = 115200, connected = false,
            dnsUpstream = "8.8.8.8", httpProxyEnabled = true,
            logs = emptyList(), dnsLookups = emptyList(), transfers = emptyList(),
            httpHosts = emptyList(), selectedHttpHostIndex = 0, commandPalette = null,
            queueBars = QueueBars(QueueOccupancy(1, 4096), QueueOccupancy(256, 256)),
        )
        for ((width, height) in listOf(100 to 30, 149 to 40, 150 to 29, 72 to 22)) {
            val rendered = YameDashboardRenderer.render(state, width, height)
            assertFalse(rendered.contains("Event broker"), "${width}x$height")
            assertFalse(rendered.contains("TUI work"), "${width}x$height")
            assertEquals(height, rendered.lines().size)
            assertTrue(rendered.lines().all { it.length == width })
        }
        val wide = YameDashboardRenderer.render(state, 150, 30)
        assertContains(wide, "1/4096 events (0%)")
        assertContains(wide, "256/256 slots (100%)")
    }

    @Test
    fun queueBarsShowTemporaryOverCapacityAfterResizingWithoutCrashing() {
        val state = DashboardState(
            portName = null, baud = 115200, connected = false,
            dnsUpstream = "8.8.8.8", httpProxyEnabled = true,
            logs = emptyList(), dnsLookups = emptyList(), transfers = emptyList(),
            httpHosts = emptyList(), selectedHttpHostIndex = 0, commandPalette = null,
            queueBars = QueueBars(QueueOccupancy(3000, 1024), QueueOccupancy(200, 100)),
        )
        val output = YameDashboardRenderer.render(state, width = 150, height = 30)
        assertContains(output.lines()[28], "3000/1024 events (292%)")
        assertContains(output.lines()[28], "200/100 slots (200%)")
        assertEquals(30, output.lines().size)
        assertTrue(output.lines().all { it.length == 150 })
    }

    @Test
    fun rendersAllOperationalPanels() {
        val state = DashboardState(
            portName = "COM3",
            baud = 115200,
            connected = true,
            dnsUpstream = "8.8.8.8",
            httpProxyEnabled = true,
            logs = listOf("10:00:00  PPP data mode active"),
            dnsLookups = listOf(
                DashboardDnsLookup(
                    key = "udp:1:1024:7",
                    time = "10:00:01",
                    transport = "UDP",
                    name = "vg.no",
                    type = "A",
                    status = "NOERROR",
                    answers = 4,
                    bytes = 128,
                ),
            ),
            transfers = listOf(
                DashboardTransfer(
                    flowId = "flow",
                    destination = "93.184.216.34:80",
                    via = "93.184.216.34:80",
                    kind = TransferKind.TCP,
                    state = TransferState.OPEN,
                    toHostBytes = 200,
                    toPeerBytes = 2048,
                    startedNanos = 1,
                    updatedNanos = 1_000_000_001,
                    detail = null,
                ),
            ),
            httpHosts = listOf(
                DashboardHttpHost(
                    host = "example.test",
                    resources = listOf(
                        DashboardHttpResource(
                            url = "http://example.test/",
                            state = ResourceState.READY,
                            kind = ResourceKind.DOCUMENT,
                        ),
                        DashboardHttpResource(
                            url = "https://example.test/assets/hero.jpg",
                            state = ResourceState.READY,
                            kind = ResourceKind.IMAGE,
                            prefetched = true,
                            transformations =
                                listOf(
                                    ResourceTransformationSummary(
                                        transformerId = "legacy-jpeg-optimization",
                                        sourceBytes = 184 * 1024,
                                        outputBytes = 31 * 1024,
                                        detail = "1600x1000 -> 640x400",
                                    ),
                                ),
                        ),
                    ),
                    expanded = true,
                ),
            ),
            selectedHttpHostIndex = 0,
            selectedHttpResourceUrl = "https://example.test/assets/hero.jpg",
            commandPalette = null,
        )

        val rendered = YameDashboardRenderer.render(
            state = state,
            width = 260,
            height = 34,
        )

        assertContains(rendered, "Log")
        assertContains(rendered, "DNS lookups")
        assertContains(rendered, "Transfers")
        assertContains(rendered, "HTTP / HTTPS compatibility proxy")
        assertContains(rendered, "vg.no")
        assertContains(rendered, "93.184.216.34:80")
        assertContains(rendered, "example.test  (2)")
        assertContains(rendered, ">  *")
        assertContains(rendered, "/assets/hero.jpg")
        assertContains(rendered, "[P]")
        assertContains(rendered, "184K->31K -83%")
    }

    @Test
    fun proxyResourcesExposeLifecycleStates() {
        val rendered =
            YameDashboardRenderer.render(
                state =
                    DashboardState(
                        portName = "COM3",
                        baud = 115200,
                        connected = true,
                        dnsUpstream = "8.8.8.8",
                        httpProxyEnabled = true,
                        logs = emptyList(),
                        dnsLookups = emptyList(),
                        transfers = emptyList(),
                        httpHosts =
                            listOf(
                                DashboardHttpHost(
                                    host = "example.test",
                                    resources =
                                        listOf(
                                            DashboardHttpResource(
                                                "http://example.test/known.gif",
                                                state = ResourceState.DISCOVERED,
                                            ),
                                            DashboardHttpResource(
                                                "http://example.test/fetching.gif",
                                                state = ResourceState.FETCHING,
                                            ),
                                            DashboardHttpResource(
                                                "http://example.test/source-ready.gif",
                                                state = ResourceState.SOURCE_READY,
                                            ),
                                            DashboardHttpResource(
                                                "http://example.test/transforming.jpg",
                                                state = ResourceState.TRANSFORMING,
                                            ),
                                            DashboardHttpResource(
                                                "http://example.test/ready.jpg",
                                                state = ResourceState.READY,
                                            ),
                                            DashboardHttpResource(
                                                "http://example.test/failed.gif",
                                                state = ResourceState.FAILED,
                                            ),
                                        ),
                                    expanded = true,
                                ),
                            ),
                        selectedHttpHostIndex = 0,
                        commandPalette = null,
                    ),
                width = 160,
                height = 50,
            )

        assertContains(rendered, ". /known.gif")
        assertContains(rendered, "~ /fetching.gif")
        assertContains(rendered, "~ /source-ready.gif")
        assertContains(rendered, "~ /transforming.jpg")
        assertContains(rendered, "* /ready.jpg")
        assertContains(rendered, "! /failed.gif")
    }

    @Test
    fun longUnoptimizedResourcePathsAreMiddleTruncated() {
        val longPath = "/assets/" + "nested/".repeat(20) + "logo.gif"
        val rendered =
            YameDashboardRenderer.render(
                state =
                    DashboardState(
                        portName = "COM3",
                        baud = 115200,
                        connected = true,
                        dnsUpstream = "8.8.8.8",
                        httpProxyEnabled = true,
                        logs = emptyList(),
                        dnsLookups = emptyList(),
                        transfers = emptyList(),
                        httpHosts =
                            listOf(
                                DashboardHttpHost(
                                    host = "example.test",
                                    resources =
                                        listOf(
                                            DashboardHttpResource(
                                                "http://example.test$longPath",
                                                state = ResourceState.READY,
                                            ),
                                        ),
                                    expanded = true,
                                ),
                            ),
                        selectedHttpHostIndex = 0,
                        commandPalette = null,
                    ),
                width = 120,
                height = 34,
            )

        assertTrue(rendered.contains("…"), rendered)
        assertContains(rendered, "logo.gif")
    }

    @Test
    fun collapsedProxyHostHidesUrls() {
        val rendered = YameDashboardRenderer.render(
            state = DashboardState(
                portName = "COM3",
                baud = 115200,
                connected = true,
                dnsUpstream = "8.8.8.8",
                httpProxyEnabled = true,
                logs = emptyList(),
                dnsLookups = emptyList(),
                transfers = emptyList(),
                httpHosts = listOf(
                    DashboardHttpHost(
                        host = "example.test",
                        resources = listOf(DashboardHttpResource("https://example.test/path/page.html?x=1")),
                        expanded = false,
                    ),
                ),
                selectedHttpHostIndex = 0,
                commandPalette = null,
            ),
            width = 120,
            height = 34,
        )

        assertContains(rendered, "> example.test")
        assertFalse(rendered.contains("/path/page.html?x=1"))
    }

    @Test
    fun commandPaletteReplacesHttpPanel() {
        val rendered = YameDashboardRenderer.render(
            state = DashboardState(
                portName = null,
                baud = 115200,
                connected = false,
                dnsUpstream = "8.8.8.8",
                httpProxyEnabled = false,
                logs = emptyList(),
                dnsLookups = emptyList(),
                transfers = emptyList(),
                httpHosts = emptyList(),
                selectedHttpHostIndex = 0,
                commandPalette = TuiCommandPalette(
                    mode = TuiPaletteMode.COMMANDS,
                    input = "/",
                    title = "Commands",
                    options = listOf(
                        TuiCommandOption("/port", "Select serial port", "/port"),
                    ),
                    selectedIndex = 0,
                ),
            ),
            width = 100,
            height = 28,
        )

        assertContains(rendered, "Commands")
        assertContains(rendered, "/port")
        assertContains(rendered, "NOT CONNECTED")
    }

    @Test
    fun commandPaletteScrollsToSelectedOption() {
        val options = listOf(
            TuiCommandOption("/port", "", "/port"),
            TuiCommandOption("/baud", "", "/baud"),
            TuiCommandOption("/dns-upstream", "", "/dns-upstream"),
            TuiCommandOption("/http-proxy", "", "/http-proxy"),
            TuiCommandOption("/reconnect", "", "/reconnect"),
            TuiCommandOption("/disconnect", "", "/disconnect"),
            TuiCommandOption("/refresh-ports", "", "/refresh-ports"),
            TuiCommandOption("/clear-log", "", "/clear-log"),
            TuiCommandOption("/quit", "", "/quit"),
        )

        val rendered = YameDashboardRenderer.render(
            state = DashboardState(
                portName = null,
                baud = 115200,
                connected = false,
                dnsUpstream = "8.8.8.8",
                httpProxyEnabled = false,
                logs = emptyList(),
                dnsLookups = emptyList(),
                transfers = emptyList(),
                httpHosts = emptyList(),
                selectedHttpHostIndex = 0,
                commandPalette = TuiCommandPalette(
                    mode = TuiPaletteMode.COMMANDS,
                    input = "/",
                    title = "Commands",
                    options = options,
                    selectedIndex = options.lastIndex,
                ),
            ),
            width = 80,
            height = 24,
        )

        assertContains(rendered, "/quit")
    }

    @Test
    fun compactRendererShowsSelectedPaletteOption() {
        val rendered = YameDashboardRenderer.render(
            state = DashboardState(
                portName = "COM3",
                baud = 115200,
                connected = false,
                dnsUpstream = "8.8.8.8",
                httpProxyEnabled = false,
                logs = emptyList(),
                dnsLookups = emptyList(),
                transfers = emptyList(),
                httpHosts = emptyList(),
                selectedHttpHostIndex = 0,
                commandPalette = TuiCommandPalette(
                    mode = TuiPaletteMode.PORTS,
                    input = "/port",
                    title = "Select serial port",
                    options = listOf(
                        TuiCommandOption("COM3", "current", "COM3"),
                        TuiCommandOption("COM7", "USB Serial Port", "COM7"),
                    ),
                    selectedIndex = 1,
                ),
            ),
            width = 60,
            height = 10,
        )

        assertContains(rendered, "Palette: /port")
        assertContains(rendered, "COM7")
    }

    @Test
    fun customBaudRateIsPreservedInPaletteOptions() {
        val rates = baudPaletteRates(230_400)

        assertEquals(listOf(9_600, 19_200, 38_400, 57_600, 115_200, 230_400), rates)
        assertEquals(5, rates.indexOf(230_400))
    }

    @Test
    fun runtimeTextCannotInjectTerminalControlSequences() {
        val rendered = YameDashboardRenderer.render(
            state = DashboardState(
                portName = "COM3",
                baud = 115200,
                connected = true,
                dnsUpstream = "8.8.8.8",
                httpProxyEnabled = true,
                logs = listOf("10:00:00  AT\u001B[2JHELLO"),
                dnsLookups = listOf(
                    DashboardDnsLookup(
                        key = "udp:1:1024:7",
                        time = "10:00:01",
                        transport = "UDP",
                        name = "evil\u001B]0;owned\u0007.test",
                        type = "A",
                        status = "NOERROR",
                        answers = 1,
                        bytes = 64,
                    ),
                ),
                transfers = emptyList(),
                httpHosts = emptyList(),
                selectedHttpHostIndex = 0,
                commandPalette = null,
                activeDetailPanel = DashboardDetailPanel.DNS,
            ),
            width = 120,
            height = 34,
        )

        assertFalse(rendered.contains('\u001B'))
        assertFalse(rendered.contains('\u0007'))
        assertContains(rendered, "AT�[2JHELLO")
        assertContains(rendered, "evil�]0;owned�.test")
    }
}
