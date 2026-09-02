package no.skasti.serialmodem.modem

import no.skasti.serialmodem.ppp.dns.PppDnsConfig
import no.skasti.serialmodem.ppp.proxy.PppHttpCompatibilityConfig
import no.skasti.serialmodem.ppp.PppIpConfig
import no.skasti.serialmodem.tone.HandshakeProfile
import no.skasti.serialmodem.tone.TonePlayer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class HayesModemConfig(
    val pickupTime: Duration = 2.seconds,
    val dialToneTime: Duration = 500.milliseconds,
    val handshakeProfile: HandshakeProfile = HandshakeProfile.V34,
    val username: String? = null,
    val password: String? = null,
    val pppIpConfig: PppIpConfig = PppIpConfig(),
    val pppDnsConfig: PppDnsConfig = PppDnsConfig(),
    val pppHttpCompatibilityConfig: PppHttpCompatibilityConfig = PppHttpCompatibilityConfig(),
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
        require((username == null) == (password == null)) {
            "username and password must either both be set or both be omitted"
        }
        require(username == null || username.isNotBlank()) {
            "username must not be blank"
        }
        require(password == null || password.isNotEmpty()) {
            "password must not be empty"
        }
        require(username == null || username.all { it.code in 0x20..0x7e }) {
            "username must contain only printable ASCII characters"
        }
        require(password == null || password.all { it.code in 0x20..0x7e }) {
            "password must contain only printable ASCII characters"
        }
    }
}
