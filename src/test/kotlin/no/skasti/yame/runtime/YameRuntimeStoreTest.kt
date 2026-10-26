package no.skasti.yame.runtime

import no.skasti.yame.persistence.*
import no.skasti.yame.config.*
import no.skasti.yame.function.FunctionStore
import no.skasti.yame.logging.*
import no.skasti.yame.ppp.proxy.NavigationResourceRegistry
import no.skasti.yame.serial.ConnectionFunctions
import java.nio.file.Files
import kotlin.test.*

class YameRuntimeStoreTest {
    @Test fun `failed registration releases earlier operations and configuration subscription`() {
        val directory = Files.createTempDirectory("yame-runtime-constructor")
        val store = applicationConfigStore(YameConfiguration())
        val functions = FunctionStore()
        try {
            functions.register(ConnectionFunctions.Disconnect) {}.use {
                YameLogManager(YameLogManager.defaultLevels(), logDirectory = directory).use { logs ->
                    assertFailsWith<IllegalArgumentException> {
                        YameRuntime(store, functions, logs, NavigationResourceRegistry(), { emptyList() })
                    }
                    assertEquals(listOf(ConnectionFunctions.Disconnect), functions.keys())
                    val previousLevel = logs.level(YameLogModule.MODEM)
                    val nextLevel = if (previousLevel == YameLogLevel.DEBUG) YameLogLevel.ERROR else YameLogLevel.DEBUG
                    store.set(LogSettings.Levels.getValue(YameLogModule.MODEM) to nextLevel)
                    assertEquals(previousLevel, logs.level(YameLogModule.MODEM),
                        "a failed constructor must not leave a live settings subscription")
                }
            }
            assertTrue(functions.keys().isEmpty())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `headless runtime observes logging settings and releases functions and subscriptions on close`() {
        val directory = Files.createTempDirectory("yame-runtime-test")
        try {
            val configuration = YameConfiguration()
            val store = applicationConfigStore(configuration)
            val functions = FunctionStore()
            YameLogManager(YameLogManager.defaultLevels(), logDirectory = directory).use { logs ->
                val runtime = YameRuntime(
                    store, functions, logManager = logs,
                    resourceGraphs = NavigationResourceRegistry(), portProvider = { emptyList() },
                )
                store.set(LogSettings.Levels.getValue(YameLogModule.MODEM) to YameLogLevel.DEBUG,
                    LogSettings.CaptureLimits.getValue(PacketCaptureLimitKind.TOTAL) to 42)
                assertEquals(YameLogLevel.DEBUG, logs.level(YameLogModule.MODEM))
                assertEquals(42, logs.captureLimits.maxTotal)
                functions.invoke(ConnectionFunctions.RefreshPorts)
                runtime.close()
                assertTrue(functions.keys().isEmpty())
                store.set(LogSettings.Levels.getValue(YameLogModule.MODEM) to YameLogLevel.ERROR)
                assertEquals(YameLogLevel.DEBUG, logs.level(YameLogModule.MODEM))
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
