package no.skasti.serialmodem.ui

import no.skasti.serialmodem.observer.HttpProxyActionKind
import no.skasti.serialmodem.observer.TransferKind
import no.skasti.serialmodem.observer.TransferState
import kotlin.test.Test
import kotlin.test.assertContains

class YameDashboardRendererTest {
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
            httpActivity = listOf(
                DashboardHttpActivity(
                    time = "10:00:02",
                    kind = HttpProxyActionKind.REDIRECT,
                    message = "301 http://example.test/ -> https://example.test/",
                ),
            ),
            commandPalette = null,
        )

        val rendered = YameDashboardRenderer.render(
            state = state,
            width = 120,
            height = 34,
        )

        assertContains(rendered, "Log")
        assertContains(rendered, "DNS lookups")
        assertContains(rendered, "Transfers")
        assertContains(rendered, "HTTP / HTTPS compatibility proxy")
        assertContains(rendered, "vg.no")
        assertContains(rendered, "93.184.216.34:80")
        assertContains(rendered, "https://example.test/")
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
                httpActivity = emptyList(),
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
}
