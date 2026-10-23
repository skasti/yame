package no.skasti.yame.serial

import no.skasti.yame.config.*

object SerialSettings : Settings("serial") {
    val Group = SettingGroup("serial", this, "")
    const val MAX_PORT_NAME_LENGTH = 256
    const val DEFAULT_BAUD_RATE = 38_400
    val Port = SettingKey<String?>("serial.port", Group, { value ->
        require(value == null || value.length <= MAX_PORT_NAME_LENGTH) {
            "portName must not exceed $MAX_PORT_NAME_LENGTH characters"
        }
    })
    val Baud = SettingKey<Int>("serial.baud", Group, { require(it > 0) { "Baud must be positive" } })
    val FlowControl = SettingKey<SerialFlowControl>("serial.flow-control", Group)

    override val bindings by lazy { listOf(
        IniBinding(Port, "serial.port", { null }, { it.takeIf(String::isNotBlank) }, { it }),
        IniBinding(Baud, "serial.baud", { DEFAULT_BAUD_RATE }, IniValues::positiveInt, Int::toString),
        IniBinding(FlowControl, "serial.flow-control", { SerialFlowControl.DISABLED }, SerialFlowControl::parse, { it.commandName }),
    ) }
}
