package no.skasti.yame.modem

import no.skasti.yame.config.ConfigurationBinding
import no.skasti.yame.config.SettingGroup
import no.skasti.yame.config.SettingKey

data class LoginCredentials(val username: String?, val password: String?) {
    override fun toString(): String = "<credentials>"
}

object ModemSettings {
    val Group = SettingGroup("modem")
    val PickupTime = SettingKey<kotlin.time.Duration>("modem.pickup-time-nanos", Group)
    val DialToneTime = SettingKey<kotlin.time.Duration>("modem.dial-tone-time-nanos", Group)
    val Handshake = SettingKey<no.skasti.yame.tone.HandshakeProfile>("modem.handshake-profile", Group)
    val Tone = SettingKey<Boolean>("modem.tone-simulation-enabled", Group)
    val Login = SettingKey<LoginCredentials>("login.credentials", Group, sensitive = true)
    internal val bindings: List<ConfigurationBinding<*>> = listOf(
        ConfigurationBinding(PickupTime,
            { c -> c.modemConfig.pickupTime },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(pickupTime = v)) }),
        ConfigurationBinding(DialToneTime,
            { c -> c.modemConfig.dialToneTime },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(dialToneTime = v)) }),
        ConfigurationBinding(Handshake,
            { c -> c.modemConfig.handshakeProfile },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(handshakeProfile = v)) }),
        ConfigurationBinding(Tone,
            { c -> c.modemConfig.toneSimulationEnabled },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(toneSimulationEnabled = v)) }),
        ConfigurationBinding(Login,
            { c -> LoginCredentials(c.modemConfig.username, c.modemConfig.password) },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(username = v.username, password = v.password)) }),
    )
}
