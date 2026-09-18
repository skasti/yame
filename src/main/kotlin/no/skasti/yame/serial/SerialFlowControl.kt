package no.skasti.yame.serial

import com.fazecast.jSerialComm.SerialPort

enum class SerialFlowControl(
    val commandName: String,
    val displayName: String,
    private val jSerialCommValue: Int,
) {
    DISABLED(
        commandName = "disabled",
        displayName = "Disabled",
        jSerialCommValue = SerialPort.FLOW_CONTROL_DISABLED,
    ),
    XON_XOFF(
        commandName = "xon-xoff",
        displayName = "XON/XOFF",
        jSerialCommValue =
            SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED or
                SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED,
    ),
    HARDWARE(
        commandName = "hardware",
        displayName = "Hardware (RTS/CTS)",
        jSerialCommValue =
            SerialPort.FLOW_CONTROL_RTS_ENABLED or
                SerialPort.FLOW_CONTROL_CTS_ENABLED,
    ),
    ;

    fun toJSerialCommValue(): Int = jSerialCommValue

    companion object {
        fun parse(value: String): SerialFlowControl =
            entries.firstOrNull {
                it.commandName.equals(value, ignoreCase = true) ||
                    (it == XON_XOFF && value.equals("xon/xoff", ignoreCase = true))
            } ?: error(
                "Unknown flow control '$value'. Available options: " +
                    entries.joinToString(", ") { it.commandName },
            )
    }
}
