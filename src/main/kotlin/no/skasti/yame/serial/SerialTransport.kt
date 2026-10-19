package no.skasti.yame.serial

import java.io.Closeable
import java.io.OutputStream

/** Runtime-owned serial IO; implementations own the port and reader lifecycle. */
interface SerialTransport : Closeable {
    val output: OutputStream
    fun open()
    fun startReading(onBytes: (ByteArray) -> Unit, onStopped: (Throwable?) -> Unit = {})
    fun setCarrierPresent(carrierPresent: Boolean)
}
