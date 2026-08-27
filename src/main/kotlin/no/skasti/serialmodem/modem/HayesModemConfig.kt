package no.skasti.serialmodem.modem

import no.skasti.serialmodem.tone.HandshakeProfile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class HayesModemConfig(
    val pickupTime: Duration = 2.seconds,
    val dialToneTime: Duration = 500.milliseconds,
    val handshakeProfile: HandshakeProfile = HandshakeProfile.V34,
) {
    init {
        require(!pickupTime.isNegative()) { "pickupTime must not be negative" }
        require(!dialToneTime.isNegative()) { "dialToneTime must not be negative" }
    }
}
