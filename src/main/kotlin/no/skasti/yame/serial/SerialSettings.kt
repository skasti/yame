package no.skasti.yame.serial

import no.skasti.yame.config.SettingGroup
import no.skasti.yame.config.SettingKey

object SerialSettings {
    val Group = SettingGroup("serial")
    const val MAX_PORT_NAME_LENGTH = 256
    val Port = SettingKey<String?>("serial.port", Group, { value ->
        require(value == null || value.length <= MAX_PORT_NAME_LENGTH) {
            "portName must not exceed $MAX_PORT_NAME_LENGTH characters"
        }
    })
    val Baud = SettingKey<Int>("serial.baud", Group, { require(it > 0) { "Baud must be positive" } })
    val FlowControl = SettingKey<SerialFlowControl>("serial.flow-control", Group)
}
