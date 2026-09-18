package no.skasti.yame.serial

import com.fazecast.jSerialComm.SerialPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SerialFlowControlTest {
    @Test
    fun parsesSupportedModesAndAlias() {
        assertEquals(SerialFlowControl.DISABLED, SerialFlowControl.parse("disabled"))
        assertEquals(SerialFlowControl.XON_XOFF, SerialFlowControl.parse("xon-xoff"))
        assertEquals(SerialFlowControl.XON_XOFF, SerialFlowControl.parse("Xon/Xoff"))
        assertEquals(SerialFlowControl.HARDWARE, SerialFlowControl.parse("HARDWARE"))
    }

    @Test
    fun mapsModesToJSerialCommFlags() {
        assertEquals(
            SerialPort.FLOW_CONTROL_DISABLED,
            SerialFlowControl.DISABLED.toJSerialCommValue(),
        )
        assertEquals(
            SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED or
                SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED,
            SerialFlowControl.XON_XOFF.toJSerialCommValue(),
        )
        assertEquals(
            SerialPort.FLOW_CONTROL_RTS_ENABLED or
                SerialPort.FLOW_CONTROL_CTS_ENABLED,
            SerialFlowControl.HARDWARE.toJSerialCommValue(),
        )
    }

    @Test
    fun rejectsUnknownMode() {
        assertFailsWith<IllegalStateException> {
            SerialFlowControl.parse("rts")
        }
    }
}
