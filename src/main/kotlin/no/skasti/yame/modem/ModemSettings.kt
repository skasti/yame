package no.skasti.yame.modem

import no.skasti.yame.config.SettingGroup
import no.skasti.yame.config.SettingKey

object ModemSettings {
    val Group = SettingGroup("modem")
    val PickupTime = SettingKey<kotlin.time.Duration>("modem.pickup-time-nanos", Group, { HayesModemConfig(pickupTime = it) })
    val DialToneTime = SettingKey<kotlin.time.Duration>("modem.dial-tone-time-nanos", Group, { HayesModemConfig(dialToneTime = it) })
    val Handshake = SettingKey<no.skasti.yame.tone.HandshakeProfile>("modem.handshake-profile", Group)
    val Tone = SettingKey<Boolean>("modem.tone-simulation-enabled", Group)
    val Login = SettingKey<LoginCredentials>("login.credentials", Group, sensitive = true)
}
