package no.skasti.yame.serial

import no.skasti.yame.config.ConfigurationBinding
import no.skasti.yame.config.SettingGroup
import no.skasti.yame.config.SettingKey

object SerialSettings {
    val Group = SettingGroup("serial")
    val Port = SettingKey<String?>("serial.port", Group)
    val Baud = SettingKey<Int>("serial.baud", Group, { require(it > 0) { "Baud must be positive" } })
    val FlowControl = SettingKey<SerialFlowControl>("serial.flow-control", Group)
    internal val bindings: List<ConfigurationBinding<*>> = listOf(
        ConfigurationBinding(Port,
            { c -> c.portName },
            { c, v -> c.copy(portName = v) }),
        ConfigurationBinding(Baud,
            { c -> c.baudRate },
            { c, v -> c.copy(baudRate = v) }),
        ConfigurationBinding(FlowControl,
            { c -> c.flowControl },
            { c, v -> c.copy(flowControl = v) }),
    )
}
