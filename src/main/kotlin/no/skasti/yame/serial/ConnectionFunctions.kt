package no.skasti.yame.serial

import no.skasti.yame.function.FunctionKey

object ConnectionFunctions {
    val Disconnect = FunctionKey<Unit, Unit>("serial.disconnect")
    val Reconnect = FunctionKey<Unit, Unit>("serial.reconnect")
    val RefreshPorts = FunctionKey<Unit, Unit>("serial.refresh-ports")
}
