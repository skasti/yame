package no.skasti.yame.serial

/** Detect falling modem-control inputs, ignoring unavailable signals that stay low. */
internal class PeerDtrMonitor {
    private var previousDsr: Boolean? = null
    private var previousDcd: Boolean? = null

    fun observe(dsr: Boolean, dcd: Boolean): List<String> {
        val dropped = buildList {
            if (previousDsr == true && !dsr) add("DSR")
            if (previousDcd == true && !dcd) add("DCD")
        }
        previousDsr = dsr
        previousDcd = dcd
        return dropped
    }
}
