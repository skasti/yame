package no.skasti.yame.serial

import no.skasti.yame.config.*

object SerialSettings : Settings("serial") {
    val Group = SettingGroup(this)
    const val MAX_PORT_NAME_LENGTH = 256
    const val DEFAULT_BAUD_RATE = 38_400
    val Port = SettingKey<String?>("port", Group, { value ->
        require(value == null || value.length <= MAX_PORT_NAME_LENGTH) {
            "portName must not exceed $MAX_PORT_NAME_LENGTH characters"
        }
    }, default = { null })
    val Baud = SettingKey<Int>("baud", Group, { require(it > 0) { "Baud must be positive" } }, default = { DEFAULT_BAUD_RATE })
    val FlowControl = SettingKey<SerialFlowControl>("flow-control", Group, default = { SerialFlowControl.DISABLED })
}
