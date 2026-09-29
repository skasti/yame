package no.skasti.yame.config

import no.skasti.yame.logging.YameLogLevel
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.serial.SerialFlowControl

data class YameConfiguration(
    val portName: String? = null,
    val baudRate: Int = 38_400,
    val flowControl: SerialFlowControl = SerialFlowControl.DISABLED,
    val modemConfig: HayesModemConfig = HayesModemConfig(),
    val logLevels: Map<YameLogModule, YameLogLevel> = YameLogManager.defaultLevels(),
) {
    init {
        require(portName == null || portName.length <= MAX_PORT_NAME_LENGTH) {
            "portName must not exceed $MAX_PORT_NAME_LENGTH characters"
        }
    }

    companion object {
        const val MAX_PORT_NAME_LENGTH = 256
    }
}
