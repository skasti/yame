package no.skasti.yame.serial

import no.skasti.yame.config.ConfigStore

/** Serial transport values, independent of Hayes emulation behavior. */
data class SerialConfig(
    val portName: String? = SerialSettings.Port.default(),
    val baudRate: Int = SerialSettings.Baud.default(),
    val flowControl: SerialFlowControl = SerialSettings.FlowControl.default(),
) {
    init {
        SerialSettings.Port.validate(portName)
        SerialSettings.Baud.validate(baudRate)
    }

    companion object {
        fun fromConfigStore(store: ConfigStore = ConfigStore.current) = SerialConfig(
            store[SerialSettings.Port], store[SerialSettings.Baud], store[SerialSettings.FlowControl],
        )
    }
}
