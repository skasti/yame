package no.skasti.yame.modem

import no.skasti.yame.config.*

object ModemSettings : Settings("modem") {
    val Group = SettingGroup(this)
    val PickupTime = SettingKey<kotlin.time.Duration>("pickup-time", Group,
        { HayesModemConfig(pickupTime = it) }, default = { HayesModemConfig().pickupTime })
    val DialToneTime = SettingKey<kotlin.time.Duration>("dial-tone-time", Group,
        { HayesModemConfig(dialToneTime = it) }, default = { HayesModemConfig().dialToneTime })
    val Handshake = SettingKey<no.skasti.yame.tone.HandshakeProfile>("handshake-profile", Group,
        default = { HayesModemConfig().handshakeProfile })
    val Tone = SettingKey<Boolean>("tone-simulation-enabled", Group,
        default = { HayesModemConfig().toneSimulationEnabled })
    val Login = SettingKey<LoginCredentials>("login", Group, sensitive = true,
        default = { LoginCredentials(null, null) })
}
