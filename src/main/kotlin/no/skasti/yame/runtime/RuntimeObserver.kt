package no.skasti.yame.runtime

import no.skasti.yame.observer.YameEvent
import no.skasti.yame.serial.SerialPortDescriptor

/** Runtime observations; adapters own how these are presented. */
interface RuntimeObserver {
    fun onLog(message: String) {}
    fun onEvent(event: YameEvent) {}
    fun updateAvailablePorts(ports: List<SerialPortDescriptor>) {}
    fun updateConnected(connected: Boolean) {}
    fun closeActiveTransfers(detail: String? = null) {}
}
