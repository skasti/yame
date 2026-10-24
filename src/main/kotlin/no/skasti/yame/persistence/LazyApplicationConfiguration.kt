package no.skasti.yame.persistence

import no.skasti.yame.config.ConfigStore
import no.skasti.yame.logging.LogSettings
import no.skasti.yame.logging.PacketCaptureLimits
import no.skasti.yame.logging.PacketCaptureLimitKind
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.ppp.PppIpConfig
import no.skasti.yame.ppp.dns.PppDnsConfig
import no.skasti.yame.ppp.proxy.ProxySettings
import no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig
import no.skasti.yame.serial.SerialSettings
import no.skasti.yame.serial.SerialFlowControl
import java.nio.file.Path

/** The store has no feature list; the owner of a requested key loads on first access. */
fun lazyApplicationConfigStore(path: Path): ConfigStore =
    ConfigStore(emptyList(), repository = IniConfigurationRepository(path))

/** CLI defaults include only features used by this mode. Runtime owns HTTP transformation setup. */
internal fun cliConfiguration(store: ConfigStore, toneTest: Boolean = false): YameConfiguration {
    return YameConfiguration(
        portName = if (toneTest) null else store[SerialSettings.Port],
        baudRate = store[SerialSettings.Baud],
        flowControl = if (toneTest) SerialFlowControl.DISABLED else store[SerialSettings.FlowControl],
        modemConfig = HayesModemConfig.fromConfigStore(store),
        pppIpConfig = if (toneTest) PppIpConfig() else PppIpConfig.fromConfigStore(store),
        pppDnsConfig = if (toneTest) PppDnsConfig() else PppDnsConfig.fromConfigStore(store),
        pppHttpCompatibilityConfig = PppHttpCompatibilityConfig(
            enabled = if (toneTest) true else store[ProxySettings.Enabled],
        ),
        logLevels = YameLogModule.entries.associateWith { store[LogSettings.Levels.getValue(it)] },
        captureLimits = PacketCaptureLimits(
            store[LogSettings.CaptureLimits.getValue(PacketCaptureLimitKind.PER_TYPE)],
            store[LogSettings.CaptureLimits.getValue(PacketCaptureLimitKind.TOTAL)],
        ),
    )
}

internal fun applyCliOverrides(store: ConfigStore, saved: YameConfiguration, effective: YameConfiguration) {
    val changes = YameConfigurationBindings.all.mapNotNull { binding ->
        val before = binding.change(saved)
        val after = binding.change(effective)
        if (before.value == after.value) null else after
    }
    store.setTransient(*changes.toTypedArray())
}
