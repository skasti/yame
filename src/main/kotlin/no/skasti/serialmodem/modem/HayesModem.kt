package no.skasti.serialmodem.modem

import no.skasti.serialmodem.tone.DialString
import no.skasti.serialmodem.tone.JavaSoundTonePlayer
import no.skasti.serialmodem.tone.TonePlayer
import no.skasti.serialmodem.tone.ToneProgress
import java.io.Closeable
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class HayesModem(
    private var output: OutputStream? = null,
    private val baudRate: Int,
    private val config: HayesModemConfig = HayesModemConfig(),
    private val onData: (ByteArray) -> Unit = {},
    private val tonePlayer: TonePlayer = JavaSoundTonePlayer(),
    private val logger: (String) -> Unit = ::println,
) : Closeable {
    enum class State {
        COMMAND,
        DIALING,
        CONNECTED,
    }

    var state: State = State.COMMAND
        private set

    private val commandBuffer = StringBuilder()
    private var echo = true

    fun receive(bytes: ByteArray) {
        when (state) {
            State.COMMAND -> receiveCommands(bytes)
            State.DIALING -> Unit
            State.CONNECTED -> onData(bytes)
        }
    }

    private fun receiveCommands(bytes: ByteArray) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            val char = value.toChar()

            when (value) {
                8, 127 -> { // backspace / delete
                    if (commandBuffer.isNotEmpty()) {
                        commandBuffer.deleteCharAt(commandBuffer.lastIndex)
                        if (echo) writeRaw("\b \b")
                    }
                }

                13 -> { // carriage return terminates an AT command
                    if (echo) writeRaw("\r")
                    val command = commandBuffer.toString()
                    commandBuffer.clear()
                    handleCommand(command)
                }

                10 -> {
                    // Ignore LF. Most modem software terminates commands with CR.
                    if (echo) writeRaw("\n")
                }

                else -> {
                    if (char.code in 0x20..0x7e) {
                        commandBuffer.append(char)
                        if (echo) output?.write(byteArrayOf(byte))
                    }
                }
            }
        }
        output?.flush()
    }

    fun attachOutput(output: OutputStream) {
        check(this.output == null) { "Modem output is already attached" }
        this.output = output
    }

    private fun handleCommand(rawCommand: String) {
        val command = rawCommand.trim()
        if (command.isEmpty()) return

        logger("AT <= $command")
        val upper = command.uppercase()

        if (!upper.startsWith("AT")) {
            respond("ERROR")
            return
        }

        when {
            upper == "AT" -> respond("OK")
            upper == "ATZ" || upper.startsWith("ATZ") -> {
                reset()
                respond("OK")
            }
            upper == "AT&F" || upper.startsWith("AT&F") -> {
                reset()
                respond("OK")
            }
            upper.startsWith("ATE0") -> {
                echo = false
                respond("OK")
            }
            upper.startsWith("ATE1") -> {
                echo = true
                respond("OK")
            }
            upper.startsWith("ATH") -> {
                state = State.COMMAND
                respond("OK")
            }
            upper.startsWith("ATD") -> dial(command.substring(3))
            else -> {
                // Old modem drivers often send long initialization strings.
                // For the first milestone we accept unknown AT commands rather
                // than failing initialization unnecessarily.
                logger("AT .. accepting unsupported command: $command")
                respond("OK")
            }
        }
    }

    fun dial(dialString: String) {
        val number = DialString.normalize(dialString)

        if (number.isEmpty()) {
            respond("NO DIALTONE")
            return
        }

        state = State.DIALING
        logger("MODEM dialing $number")

        try {
            tonePlayer.dial(
                number = number,
                pickupTime = config.pickupTime,
                dialToneTime = config.dialToneTime,
                handshakeProfile = config.handshakeProfile,
                onProgress = ::logToneProgress,
            )
        } catch (e: Exception) {
            // Audio is cosmetic for a real modem connection: a missing or
            // unconfigured audio device must not prevent the link itself.
            logger("AUDIO !! Could not play dialing tones: ${e.message}")
        }

        connect()
    }

    private fun logToneProgress(progress: ToneProgress) {
        logger("TONE [${progress.step.name.lowercase()}] ${progress.description}")
    }

    private fun connect() {
        state = State.CONNECTED
        logger("MODEM connected")
        respond("CONNECT $baudRate")
    }

    private fun reset() {
        state = State.COMMAND
        echo = true
    }

    private fun respond(result: String) {
        if (writeRaw("\r\n$result\r\n")) {
            logger("AT => $result")
        }
    }

    private fun writeRaw(value: String): Boolean {
        output?.let {
            it.write(value.toByteArray(StandardCharsets.US_ASCII))
            it.flush()
        } ?: return false
        return true
    }

    override fun close() {
        tonePlayer.close()
    }
}
