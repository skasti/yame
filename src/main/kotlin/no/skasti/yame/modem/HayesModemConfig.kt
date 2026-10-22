package no.skasti.yame.modem

import no.skasti.yame.config.ConfigChanges
import no.skasti.yame.config.ConfigStore
import no.skasti.yame.tone.HandshakeProfile
import no.skasti.yame.tone.TonePlayer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class HayesModemConfig(
    val pickupTime: Duration = 2.seconds,
    val dialToneTime: Duration = 500.milliseconds,
    val handshakeProfile: HandshakeProfile = HandshakeProfile.V34,
    val toneSimulationEnabled: Boolean = true,
    val username: String? = null,
    val password: String? = null,
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
        LoginCredentials(username, password)
    }

    companion object {
        fun fromConfigStore(store: ConfigStore = ConfigStore.current): HayesModemConfig {
            store.ensureLoaded(ModemSettings);
            return fromSnapshot(store.snapshot())
        }

        internal fun fromSnapshot(snapshot: ConfigChanges): HayesModemConfig {
            val login = requireNotNull(snapshot[ModemSettings.Login]).value
            return HayesModemConfig(
                pickupTime = requireNotNull(snapshot[ModemSettings.PickupTime]).value,
                dialToneTime = requireNotNull(snapshot[ModemSettings.DialToneTime]).value,
                handshakeProfile = requireNotNull(snapshot[ModemSettings.Handshake]).value,
                toneSimulationEnabled = requireNotNull(snapshot[ModemSettings.Tone]).value,
                username = login.username, password = login.password,
            )
        }

        const val MAX_LOGIN_USERNAME_LENGTH = 64
        const val MAX_LOGIN_PASSWORD_LENGTH = 64
    }
}
