package no.skasti.yame.ui

import no.skasti.yame.ppp.session.TransferKind
import no.skasti.yame.ppp.session.TransferState

import no.skasti.yame.BuildInfo
import no.skasti.yame.ppp.proxy.ResourceKind
import no.skasti.yame.ppp.proxy.ResourceState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Visual fixtures use the production renderer; no serial device or network is opened. */
class DashboardScreenshotTest {
    @Test
    fun generateDashboardScreenshots() {
        val directory = File("build/reports/tui-frames").apply { mkdirs() }
        val startup = DashboardState(
            portName = "/dev/ttyUSB0", baud = 57600, connected = false,
            dnsUpstream = "8.8.8.8", httpProxyEnabled = true,
            logs = listOf("10:00:00  YAME started", "10:00:00  Serial port opened; waiting for client"),
            dnsLookups = emptyList(), transfers = emptyList(), httpHosts = emptyList(),
            selectedHttpHostIndex = 0, commandPalette = null,
            // Arbitrary fixture values stay fixed so screenshot diffs are reproducible.
            queueBars = QueueBars(
                broker = QueueOccupancy(1379, 4096),
                tui = QueueOccupancy(193, 256),
            ),
        )
        val active = startup.copy(
            connected = true,
            queueBars = QueueBars(
                broker = QueueOccupancy(3769, 4096),
                tui = QueueOccupancy(87, 256),
            ),
            logs = listOf(
                "10:00:01  PPP session established at 57600 baud",
                "10:00:02  DNS example.test A -> 93.184.216.34",
                "10:00:03  HTTP GET http://example.test/ through HTTPS compatibility proxy",
                "10:00:04  Upstream resource acquired; image converted for legacy browser",
                "10:00:05  Sending response to client over the serial PPP link",
            ),
            dnsLookups = listOf(DashboardDnsLookup(
                "dns-1", "10:00:02", "UDP", "example.test", "A", "NOERROR", 1, 64,
            )),
            transfers = listOf(DashboardTransfer(
                "flow-1", "93.184.216.34:80", "93.184.216.34:443", TransferKind.TCP,
                TransferState.OPEN, 256, 32768, 1, 10_000_000_001, "HTTP response",
            )),
            httpHosts = listOf(DashboardHttpHost("example.test", listOf(
                DashboardHttpResource("http://example.test/", ResourceState.READY, ResourceKind.DOCUMENT),
                DashboardHttpResource("http://example.test/assets/logo.gif", ResourceState.READY, ResourceKind.IMAGE),
                DashboardHttpResource("http://example.test/assets/banner.jpg", ResourceState.FETCHING, ResourceKind.IMAGE),
            ), expanded = true)),
        )
        for ((rows, columns) in listOf(30 to 100, 45 to 135, 50 to 150)) {
            writeScreenshot(startup, columns, rows, File(directory, "${rows}x${columns}.ansi"))
            for (panel in DashboardDetailPanel.entries) {
                if (columns > 149 && panel != DashboardDetailPanel.PROXY) continue
                writeScreenshot(active.copy(activeDetailPanel = panel), columns, rows,
                    File(directory, "${rows}x${columns}-${panel.name.lowercase()}.ansi"))
            }
        }
    }

    @Test
    fun previewRevisionIsStableWithoutAlteringLayoutOrStyling() {
        val first = "\u001b[94;1m[ YAME v0.1.0 (deadbeef) ]\u001b[39;22m" 
        val second = first.replace("deadbeef", "f00ba123")
        val expected = "\u001b[94;1m[ YAME v0.1.0 (00000000) ]\u001b[39;22m"
        assertEquals(expected, normalizeBuildRevision(first, "0.1.0", "deadbeef"))
        assertEquals(expected, normalizeBuildRevision(second, "0.1.0", "f00ba123"))
        assertEquals(first.length, expected.length)
        assertEquals("v0.1.0 (deadbeef)", normalizeBuildRevision("v0.1.0 (deadbeef)", "0.2.0", "deadbeef"))
    }

    private fun normalizeBuildRevision(frame: String, version: String, gitCommit: String): String =
        frame.replace("v$version ($gitCommit)", "v$version (${"0".repeat(gitCommit.length)})")

    private fun writeScreenshot(state: DashboardState, columns: Int, rows: Int, output: File) {
        val plain = YameDashboardRenderer.render(state, columns, rows)
        assertEquals(rows, plain.lines().size)
        plain.lines().forEach { assertEquals(columns, it.length) }
        val frame = YameDashboardRenderer.render(state, columns, rows, DashboardStyles.colorful)
        // Keep the real build revision in production, but normalize it in visual
        // fixtures. CI builds each PR using a fresh synthetic merge SHA, which
        // otherwise makes every screenshot appear changed even with identical TUI.
        // Preserve character count so layout and wrapping remain representative.
        val stableFrame = normalizeBuildRevision(frame, BuildInfo.version, BuildInfo.gitCommit)
        // Keep ANSI styles intact: xterm.js interprets the actual terminal cells.
        output.writeText(stableFrame, Charsets.UTF_8)
    }
}

