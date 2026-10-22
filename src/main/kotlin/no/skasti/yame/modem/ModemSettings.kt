package no.skasti.yame.modem

import no.skasti.yame.config.*
import kotlin.time.Duration.Companion.nanoseconds

object ModemSettings : ConfigBase("modem") {
    val Group = SettingGroup("modem", this, "")
    val PickupTime = SettingKey<kotlin.time.Duration>("modem.pickup-time-nanos", Group, { HayesModemConfig(pickupTime = it) })
    val DialToneTime = SettingKey<kotlin.time.Duration>("modem.dial-tone-time-nanos", Group, { HayesModemConfig(dialToneTime = it) })
    val Handshake = SettingKey<no.skasti.yame.tone.HandshakeProfile>("modem.handshake-profile", Group)
    val Tone = SettingKey<Boolean>("modem.tone-simulation-enabled", Group)
    val Login = SettingKey<LoginCredentials>("login.credentials", Group, sensitive = true)

    override val bindings by lazy { listOf(
        IniBinding(PickupTime, "modem.pickup-time-nanos", { HayesModemConfig().pickupTime }, { it.toLong().nanoseconds }, { it.inWholeNanoseconds.toString() }),
        IniBinding(DialToneTime, "modem.dial-tone-time-nanos", { HayesModemConfig().dialToneTime }, { it.toLong().nanoseconds }, { it.inWholeNanoseconds.toString() }),
        IniBinding(Handshake, "modem.handshake-profile", { HayesModemConfig().handshakeProfile },
            { raw -> no.skasti.yame.tone.HandshakeProfile.entries.firstOrNull { it.name.equals(raw, true) } ?: error("unknown handshake profile '$raw'") }, { it.name }),
        IniBinding(Tone, "modem.tone-simulation-enabled", { HayesModemConfig().toneSimulationEnabled }, IniValues::boolean, Boolean::toString),
    ) }
    override fun loadFromIni(context: IniReadContext): List<ConfigChange<*>> {
        val static = super.loadFromIni(context)
        val username = context.read("", "login.username")
        val password = context.read("", "login.password")
        return static + (Login to LoginCredentials(username, password))
    }
    override fun saveToIni(context: IniWriteContext) {
        super.saveToIni(context)
        if (Login !in context.changedKeys) return
        val login = requireNotNull(context.snapshot[Login]).value
        if (login.username == null) context.remove("", "login.username") else context.write("", "login.username", login.username)
        if (login.password == null) context.remove("", "login.password") else context.write("", "login.password", login.password)
    }
}
