package no.skasti.yame.modem

import no.skasti.yame.config.*

object ModemSettings : Settings("modem") {
    val Group = SettingGroup(this, description = "Settings for the modem component.")
    val PickupTime = SettingKey<kotlin.time.Duration>("pickup-time", Group,
        { HayesModemConfig(pickupTime = it) }, description = "Ringback duration before the modem answers a dial command.", default = { HayesModemConfig().pickupTime })
    val DialToneTime = SettingKey<kotlin.time.Duration>("dial-tone-time", Group,
        { HayesModemConfig(dialToneTime = it) }, description = "Duration of the simulated dial tone before dialing.", default = { HayesModemConfig().dialToneTime })
    val Handshake = SettingKey<no.skasti.yame.tone.HandshakeProfile>("handshake-profile", Group,
        description = "Modem handshake sound profile used during connection setup.", default = { HayesModemConfig().handshakeProfile })
    val Tone = SettingKey<Boolean>("tone-simulation-enabled", Group,
        description = "Play simulated modem tones during dialing and handshaking.", default = { HayesModemConfig().toneSimulationEnabled })
    val Login = SettingKey<LoginCredentials>("login", Group, sensitive = true,
        description = "Terminal login credentials. Set both username and password, or omit both.\nCredentials are stored in plaintext; protect this file.", default = { LoginCredentials(null, null) })
}
