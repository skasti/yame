package no.skasti.yame.runtime

import no.skasti.yame.config.InstalledConfigTest
import no.skasti.yame.config.*
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.logging.*
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.serial.ConnectionFunctions
import java.nio.file.Files
import kotlin.test.*

class YameRuntimeStoreTest : InstalledConfigTest() {
    @Test fun `failed registration releases earlier operations and configuration subscription`() {
        val directory = Files.createTempDirectory("yame-runtime-startup")
        val store = ConfigStore()
        val functions = FunctionStore()
        try {
            functions.register(ConnectionFunctions.Disconnect) {}.use {
                YameLogManager(no.skasti.yame.logging.LoggingConfig(), logDirectory = directory, publishEntry = { }).use { logs ->
                    assertFailsWith<IllegalArgumentException> {
                        run { installConfig(store); YameRuntime(functions, logs, NavigationResourceRegistry(), { emptyList() }) }.run()
                    }
                    assertEquals(listOf(ConnectionFunctions.Disconnect), functions.keys())
                    val previousLevel = logs.level(YameLogModule.MODEM)
                    val nextLevel = if (previousLevel == YameLogLevel.DEBUG) YameLogLevel.ERROR else YameLogLevel.DEBUG
                    store.set(LogSettings.Levels.getValue(YameLogModule.MODEM) to nextLevel)
                    assertEquals(previousLevel, logs.level(YameLogModule.MODEM),
                        "failed startup must not leave a live settings subscription")
                }
            }
            assertTrue(functions.keys().isEmpty())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `headless runtime observes logging settings and releases functions and subscriptions on close`() {
        val directory = Files.createTempDirectory("yame-runtime-test")
        try {
            val store = ConfigStore()
            val functions = FunctionStore()
            YameLogManager(no.skasti.yame.logging.LoggingConfig(), logDirectory = directory, publishEntry = { }).use { logs ->
                val ports = RuntimeTestPorts()
                val runtime = run { installConfig(store); YameRuntime(functions, logManager = logs,
                    resourceGraphs = NavigationResourceRegistry(), portProvider = ports::scan,
                ) }
                store.set(LogSettings.Levels.getValue(YameLogModule.MODEM) to YameLogLevel.DEBUG,
                    LogSettings.CaptureLimits.getValue(PacketCaptureLimitKind.TOTAL) to 42)
                assertEquals(YameLogLevel.DEBUG, logs.level(YameLogModule.MODEM))
                assertEquals(42, logs.captureLimits.maxTotal)
                assertTrue(functions.keys().isEmpty(), "construction must not register functions")
                RuntimeTestRun(runtime, ports).use { functions.invoke(ConnectionFunctions.RefreshPorts) }
                assertTrue(functions.keys().isEmpty())
                store.set(LogSettings.Levels.getValue(YameLogModule.MODEM) to YameLogLevel.ERROR)
                assertEquals(YameLogLevel.DEBUG, logs.level(YameLogModule.MODEM))
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
