package no.skasti.yame.tone

import no.skasti.yame.config.ConfigStore
import no.skasti.yame.logging.DiagnosticMessage
import no.skasti.yame.logging.YameLogManager
import no.skasti.yame.logging.YameLogModule
import no.skasti.yame.logging.diagnosticLogger
import no.skasti.yame.modem.HayesModem
import no.skasti.yame.modem.HayesModemConfig
import no.skasti.yame.observer.YameEvents
import no.skasti.yame.serial.SerialSettings

/** Owns the standalone tone-test modem and its intentional tone-enabled override. */
class ToneTest(
    logManager: YameLogManager,
    config: HayesModemConfig = HayesModemConfig.fromConfigStore(),
    baudRate: Int = ConfigStore.current[SerialSettings.Baud],
) : AutoCloseable {
    private val config = configurationForToneTest(config)
    private val modem = HayesModem(
        baudRate = baudRate,
        config = this.config,
        logger = diagnosticLogger(logManager.logger(YameLogModule.MODEM)),
        pppLogger = logManager.logger(YameLogModule.PPP),
        dnsLogger = logManager.debugLogger(YameLogModule.DNS),
        transferLogger = logManager.debugLogger(YameLogModule.TRANSFERS),
        proxyLogger = logManager.debugLogger(YameLogModule.PROXY),
        diagnosticSink = logManager::eventSink,
    )

    fun run(number: String) {
        println("Tone test. Dialing: $number " +
            "(pickup: ${config.pickupTime}, dial tone: ${config.dialToneTime}, handshake: ${config.handshakeProfile})...")
        modem.dial(number)
        YameEvents.publish(DiagnosticMessage("Tone test complete."))
    }

    override fun close() = modem.close()
}

internal fun configurationForToneTest(configuration: HayesModemConfig): HayesModemConfig =
    configuration.copy(toneSimulationEnabled = true)
