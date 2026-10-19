package no.skasti.yame.persistence

import no.skasti.yame.config.*
import no.skasti.yame.serial.SerialSettings
import no.skasti.yame.modem.ModemSettings
import no.skasti.yame.modem.LoginCredentials
import no.skasti.yame.ppp.PppSettings
import no.skasti.yame.ppp.dns.DnsSettings
import no.skasti.yame.ppp.proxy.ProxySettings
import no.skasti.yame.ppp.proxy.transform.TransformerSettings
import no.skasti.yame.logging.LogSettings

/** The only adapter from feature-owned keys to the legacy yame.ini representation. */
internal object YameConfigurationBindings {
    val serialSettings: List<ConfigurationBinding<*>> = listOf(
        ConfigurationBinding(SerialSettings.Port,
            { c -> c.portName },
            { c, v -> c.copy(portName = v) }),
        ConfigurationBinding(SerialSettings.Baud,
            { c -> c.baudRate },
            { c, v -> c.copy(baudRate = v) }),
        ConfigurationBinding(SerialSettings.FlowControl,
            { c -> c.flowControl },
            { c, v -> c.copy(flowControl = v) }),
    )
    val modemSettings: List<ConfigurationBinding<*>> = listOf(
        ConfigurationBinding(ModemSettings.PickupTime,
            { c -> c.modemConfig.pickupTime },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(pickupTime = v)) }),
        ConfigurationBinding(ModemSettings.DialToneTime,
            { c -> c.modemConfig.dialToneTime },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(dialToneTime = v)) }),
        ConfigurationBinding(ModemSettings.Handshake,
            { c -> c.modemConfig.handshakeProfile },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(handshakeProfile = v)) }),
        ConfigurationBinding(ModemSettings.Tone,
            { c -> c.modemConfig.toneSimulationEnabled },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(toneSimulationEnabled = v)) }),
        ConfigurationBinding(ModemSettings.Login,
            { c -> LoginCredentials(c.modemConfig.username, c.modemConfig.password) },
            { c, v -> c.copy(modemConfig = c.modemConfig.copy(username = v.username, password = v.password)) }),
    )
    val pppSettings: List<ConfigurationBinding<*>> = listOf(
        ConfigurationBinding(PppSettings.Subnet,
            { c -> c.pppIpConfig.configuredSubnet },
            { c, v -> c.copy(pppIpConfig = c.pppIpConfig.copy(configuredSubnet = v)) }),
    )
    val dnsSettings: List<ConfigurationBinding<*>> = listOf(
        ConfigurationBinding(DnsSettings.Upstream,
            { c -> c.pppDnsConfig.upstreamServer },
            { c, v -> c.copy(pppDnsConfig = c.pppDnsConfig.copy(upstreamServer = v)) }),
    )
    val proxySettings: List<ConfigurationBinding<*>> = listOf(
        ConfigurationBinding(ProxySettings.Enabled,
            { c -> c.pppHttpCompatibilityConfig.enabled },
            { c, v -> c.copy(pppHttpCompatibilityConfig = c.pppHttpCompatibilityConfig.copy(enabled = v)) }),
    )
    val transformerSettings: List<ConfigurationBinding<*>> = listOf(
        ConfigurationBinding(TransformerSettings.Rules,
            { c -> c.pppHttpCompatibilityConfig.elementSimplifier },
            { c, v -> c.copy(pppHttpCompatibilityConfig = c.pppHttpCompatibilityConfig.copy(elementSimplifier = v)) }),
        ConfigurationBinding(TransformerSettings.Script,
            { c -> c.pppHttpCompatibilityConfig.htmlTransformers.scriptRemover },
            { c, v -> c.copy(pppHttpCompatibilityConfig = c.pppHttpCompatibilityConfig.copy(htmlTransformers = c.pppHttpCompatibilityConfig.htmlTransformers.copy(scriptRemover = v))) }),
        ConfigurationBinding(TransformerSettings.Style,
            { c -> c.pppHttpCompatibilityConfig.htmlTransformers.styleRemover },
            { c, v -> c.copy(pppHttpCompatibilityConfig = c.pppHttpCompatibilityConfig.copy(htmlTransformers = c.pppHttpCompatibilityConfig.htmlTransformers.copy(styleRemover = v))) }),
        ConfigurationBinding(TransformerSettings.Attributes,
            { c -> c.pppHttpCompatibilityConfig.htmlTransformers.attributeCleaner },
            { c, v -> c.copy(pppHttpCompatibilityConfig = c.pppHttpCompatibilityConfig.copy(htmlTransformers = c.pppHttpCompatibilityConfig.htmlTransformers.copy(attributeCleaner = v))) }),
        ConfigurationBinding(TransformerSettings.Elements,
            { c -> c.pppHttpCompatibilityConfig.htmlTransformers.elementSimplifier },
            { c, v -> c.copy(pppHttpCompatibilityConfig = c.pppHttpCompatibilityConfig.copy(htmlTransformers = c.pppHttpCompatibilityConfig.htmlTransformers.copy(elementSimplifier = v))) }),
    )
    val logSettings: List<ConfigurationBinding<*>> = LogSettings.Levels.map { (module, key) ->
        ConfigurationBinding(key,
            { c -> c.logLevels.getValue(module) },
            { c, v -> c.copy(logLevels = c.logLevels + (module to v)) },
        )
    } + LogSettings.CaptureLimits.map { (kind, key) ->
        ConfigurationBinding(key,
            { c -> kind.value(c.captureLimits) },
            { c, v -> c.copy(captureLimits = kind.apply(c.captureLimits, v)) },
        )
    }
    val all = serialSettings + modemSettings + pppSettings + dnsSettings + proxySettings + transformerSettings + logSettings
}
