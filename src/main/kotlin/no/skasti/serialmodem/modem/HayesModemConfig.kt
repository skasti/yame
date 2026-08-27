package no.skasti.serialmodem.modem

import no.skasti.serialmodem.tone.HandshakeProfile
import no.skasti.serialmodem.tone.TonePlayer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class HayesModemConfig(
    val pickupTime: Duration = 2.seconds,
    val dialToneTime: Duration = 500.milliseconds,
    val handshakeProfile: HandshakeProfile = HandshakeProfile.V34,
) {
    init {
        require(pickupTime.isFinite()) { "pickupTime must be finite" }
        require(dialToneTime.isFinite()) { "dialToneTime must be finite" }
        require(!pickupTime.isNegative()) { "pickupTime must not be negative" }
        require(!dialToneTime.isNegative()) { "dialToneTime must not be negative" }
        require(pickupTime <= TonePlayer.MAX_DURATION) {
            "pickupTime must not exceed ${TonePlayer.MAX_DURATION}"
        }
        require(dialToneTime <= TonePlayer.MAX_DURATION) {
            "dialToneTime must not exceed ${TonePlayer.MAX_DURATION}"
        }
    }

}
