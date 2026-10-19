package no.skasti.yame.persistence

import no.skasti.yame.logging.PacketCaptureLimits
import no.skasti.yame.logging.YameLogLevel
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.serial.SerialFlowControl
import no.skasti.yame.ppp.PppIpConfig
import no.skasti.yame.ppp.dns.PppDnsConfig
import no.skasti.yame.ppp.proxy.PppHttpCompatibilityConfig

/** Legacy file representation. Runtime components receive their own configuration snapshots. */
data class YameConfiguration(
    val portName: String? = null,
    val baudRate: Int = 38_400,
    val flowControl: SerialFlowControl = SerialFlowControl.DISABLED,
    val modemConfig: HayesModemConfig = HayesModemConfig(),
    val pppIpConfig: PppIpConfig = PppIpConfig(),
    val pppDnsConfig: PppDnsConfig = PppDnsConfig(),
    val pppHttpCompatibilityConfig: PppHttpCompatibilityConfig = PppHttpCompatibilityConfig(),
    val captureLimits: PacketCaptureLimits = PacketCaptureLimits(),
    val logLevels: Map<YameLogModule, YameLogLevel> = YameLogManager.defaultLevels(),
) {
    init { no.skasti.yame.serial.SerialSettings.Port.validate(portName) }
}
