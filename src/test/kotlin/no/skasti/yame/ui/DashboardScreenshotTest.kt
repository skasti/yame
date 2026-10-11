package no.skasti.yame.ui

import no.skasti.yame.observer.TransferKind
import no.skasti.yame.observer.TransferState
import no.skasti.yame.ppp.proxy.ResourceKind
import no.skasti.yame.ppp.proxy.ResourceState
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** Visual fixtures use the production renderer; no serial device or network is opened. */
class DashboardScreenshotTest {
    @Test
    fun generateDashboardScreenshots() {
        val directory = File("build/reports/tui-screenshots").apply { mkdirs() }
        val startup = DashboardState(
            portName = "/dev/ttyUSB0", baud = 57600, connected = false,
            dnsUpstream = "8.8.8.8", httpProxyEnabled = true,
            logs = listOf("10:00:00  YAME started", "10:00:00  Serial port opened; waiting for client"),
            dnsLookups = emptyList(), transfers = emptyList(), httpHosts = emptyList(),
            selectedHttpHostIndex = 0, commandPalette = null,
        )
        val active = startup.copy(
            connected = true,
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
        for ((rows, columns) in listOf(30 to 100, 80 to 200, 100 to 300)) {
            writeScreenshot(startup, columns, rows, File(directory, "${rows}x${columns}.png"))
            for (panel in DashboardDetailPanel.entries) {
                if (columns > 250 && panel != DashboardDetailPanel.PROXY) continue
                writeScreenshot(active.copy(activeDetailPanel = panel), columns, rows,
                    File(directory, "${rows}x${columns}-${panel.name.lowercase()}.png"))
            }
        }
    }

    private fun writeScreenshot(state: DashboardState, columns: Int, rows: Int, output: File) {
        val plain = YameDashboardRenderer.render(state, columns, rows)
        assertEquals(rows, plain.lines().size)
        plain.lines().forEach { assertEquals(columns, it.length) }
        val frame = YameDashboardRenderer.render(state, columns, rows, DashboardStyles.colorful)
        TerminalFramePng.write(frame, columns, rows, output)
        val decoded = ImageIO.read(output)
        assertEquals(columns * TerminalFramePng.CELL_WIDTH, decoded.width)
        assertEquals(rows * TerminalFramePng.CELL_HEIGHT, decoded.height)
    }
}

/** Fixed terminal cells, including the SGR colors emitted by Mordant. */
private object TerminalFramePng {
    const val CELL_WIDTH = 9
    const val CELL_HEIGHT = 18
    private val background = Color(16, 19, 24)
    private val foreground = Color(220, 224, 230)
    private val palette = listOf(
        0x000000, 0xaa0000, 0x00aa00, 0xaa5500, 0x0000aa, 0xaa00aa, 0x00aaaa, 0xaaaaaa,
        0x555555, 0xff5555, 0x55ff55, 0xffff55, 0x5555ff, 0xff55ff, 0x55ffff, 0xffffff,
    ).map(::Color)
    private val sgr = Regex("\\u001B\\[([0-9;]*)m")

    fun write(frame: String, columns: Int, rows: Int, output: File) {
        val image = BufferedImage(columns * CELL_WIDTH, rows * CELL_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = background
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            var color = foreground
            var bold = false
            var inverse = false
            frame.lines().forEachIndexed { row, line ->
                var index = 0
                var column = 0
                while (index < line.length) {
                    val match = sgr.find(line, index)?.takeIf { it.range.first == index }
                    if (match != null) {
                        val codes = match.groupValues[1].ifEmpty { "0" }.split(';').map(String::toInt)
                        var i = 0
                        while (i < codes.size) {
                            when (val code = codes[i]) {
                                0 -> { color = foreground; bold = false; inverse = false }
                                1 -> bold = true
                                22 -> bold = false
                                7 -> inverse = true
                                27 -> inverse = false
                                39 -> color = foreground
                                in 30..37 -> color = palette[code - 30]
                                in 90..97 -> color = palette[code - 90 + 8]
                                38 -> when (codes.getOrNull(i + 1)) {
                                    2 -> { color = Color(codes[i + 2], codes[i + 3], codes[i + 4]); i += 4 }
                                    5 -> { color = indexedColor(codes[i + 2]); i += 2 }
                                }
                                else -> error("Unsupported terminal SGR: $code")
                            }
                            i++
                        }
                        index = match.range.last + 1
                    } else {
                        check(line[index] != '\u001B') { "Unsupported terminal escape" }
                        val x = column * CELL_WIDTH
                        val y = row * CELL_HEIGHT
                        graphics.color = if (inverse) color else background
                        graphics.fillRect(x, y, CELL_WIDTH, CELL_HEIGHT)
                        graphics.color = if (inverse) background else color
                        graphics.font = Font("DejaVu Sans Mono", if (bold) Font.BOLD else Font.PLAIN, 14)
                        graphics.drawString(line[index].toString(), x, y + 14)
                        column++
                        index++
                    }
                }
                check(column == columns)
            }
            check(ImageIO.write(image, "png", output))
        } finally {
            graphics.dispose()
        }
    }

    private fun indexedColor(index: Int): Color = when {
        index < 16 -> palette[index]
        index >= 232 -> Color(8 + (index - 232) * 10, 8 + (index - 232) * 10, 8 + (index - 232) * 10)
        else -> {
            val n = index - 16
            val levels = listOf(0, 95, 135, 175, 215, 255)
            Color(levels[n / 36], levels[n / 6 % 6], levels[n % 6])
        }
    }
}
