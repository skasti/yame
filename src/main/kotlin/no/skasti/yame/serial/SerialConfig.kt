package no.skasti.yame.serial

import no.skasti.yame.config.ConfigStore

/** Immutable transport settings, independent of Hayes modem behavior. */
data class SerialConfig(
    val portName: String? = SerialSettings.Port.default(),
    val baudRate: Int = SerialSettings.Baud.default(),
    val flowControl: SerialFlowControl = SerialSettings.FlowControl.default(),
) {
    init {
        SerialSettings.Port.validate(portName)
        SerialSettings.Baud.validate(baudRate)
        SerialSettings.FlowControl.validate(flowControl)
    }
    companion object {
        fun fromConfigStore(store: ConfigStore = ConfigStore.current) = SerialConfig(
            store[SerialSettings.Port], store[SerialSettings.Baud], store[SerialSettings.FlowControl],
        )
    }
}
