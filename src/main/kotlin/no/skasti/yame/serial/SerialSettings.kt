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
    }, description = "Serial device name, for example COM1 or /dev/ttyUSB0. Omit to select a port at runtime.", default = { null })
    val Baud = SettingKey<Int>("baud", Group, { require(it > 0) { "Baud must be positive" } }, description = "Serial port baud rate in bits per second.", default = { DEFAULT_BAUD_RATE })
    val FlowControl = SettingKey<SerialFlowControl>("flow-control", Group, description = "Serial flow control: DISABLED, XON_XOFF or HARDWARE.", default = { SerialFlowControl.DISABLED })
}
