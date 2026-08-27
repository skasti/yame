package no.skasti.serialmodem.modem

import no.skasti.serialmodem.ppp.PppHandler
import no.skasti.serialmodem.ppp.RetroPppHandler
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
    private val tonePlayer: TonePlayer = JavaSoundTonePlayer(),
    private val logger: (String) -> Unit = ::println,
    private val pppHandler: PppHandler = RetroPppHandler(logger),
    private var setCarrierPresent: (Boolean) -> Unit = { /* no-op */ }
) : Closeable {
    enum class State {
        COMMAND,
        DIALING,
        CONNECTED,
    }

    var state: State = State.COMMAND
        private set

    private enum class CommandParseState {
        SEEKING_AT,
        SAW_A,
        READING_COMMAND,
    }

    private enum class ConnectedPhase {
        LOGIN_USERNAME,
        LOGIN_PASSWORD,
        LOGIN_COMMAND,
        PPP,
    }

    private val commandBuffer = StringBuilder()
    private var commandParseState = CommandParseState.SEEKING_AT
    private var pendingCommandA = 'A'
    private var echo = true
    private val loginBuffer = StringBuilder()
    private var connectedPhase = ConnectedPhase.PPP
    private var pppStarted = false
    private var pendingLoginUsername: String? = null

    init {
        output?.let(pppHandler::attachOutput)
        setCarrierPresent(false)
    }

    fun receive(bytes: ByteArray) {
        when (state) {
            State.COMMAND -> receiveCommands(bytes)
            State.DIALING -> Unit
            State.CONNECTED -> receiveConnected(bytes)
        }
    }

    private fun receiveCommands(bytes: ByteArray) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            val char = value.toChar()

            when (commandParseState) {
                CommandParseState.SEEKING_AT -> {
                    if (char == 'A' || char == 'a') {
                        pendingCommandA = char
                        commandParseState = CommandParseState.SAW_A
                    }
                }

                CommandParseState.SAW_A -> {
                    when {
                        char == 'T' || char == 't' -> {
                            commandBuffer.clear()
                            commandBuffer.append(pendingCommandA).append(char)
                            commandParseState = CommandParseState.READING_COMMAND
                            if (echo) writeRaw("$pendingCommandA$char")
                        }

                        char == 'A' || char == 'a' -> {
                            pendingCommandA = char
                            // Stay synchronized on the newest possible AT prefix.
                        }

                        else -> {
                            commandParseState = CommandParseState.SEEKING_AT
                        }
                    }
                }

                CommandParseState.READING_COMMAND -> {
                    when (value) {
                        8, 127 -> { // backspace / delete
                            if (commandBuffer.length > 2) {
                                commandBuffer.deleteCharAt(commandBuffer.lastIndex)
                                if (echo) writeRaw("\b \b")
                            }
                        }

                        13 -> { // carriage return terminates an AT command
                            if (echo) writeRaw("\r")
                            val command = commandBuffer.toString()
                            commandBuffer.clear()
                            commandParseState = CommandParseState.SEEKING_AT
                            handleCommand(command)
                        }

                        10 -> Unit // Ignore LF. Most modem software terminates commands with CR.

                        else -> {
                            when {
                                value == 0x7d || value == 0x7e -> {
                                    // PPP framing/escape bytes cannot be part of a Hayes command.
                                    // Drop a false AT candidate and resume looking for a real one.
                                    commandBuffer.clear()
                                    commandParseState = CommandParseState.SEEKING_AT
                                }

                                char.code in 0x20..0x7e -> {
                                    val previous = commandBuffer.lastOrNull()
                                    val startsNewAtPrefix =
                                        (char == 'T' || char == 't') &&
                                            (previous == 'A' || previous == 'a') &&
                                            commandBuffer.length > 2 &&
                                            !isPlausibleDialCandidate(commandBuffer.toString())

                                    if (startsNewAtPrefix) {
                                        commandBuffer.clear()
                                        commandBuffer.append(previous).append(char)
                                    } else {
                                        commandBuffer.append(char)
                                        resynchronizeInvalidDialCandidate()
                                    }

                                    if (echo) output?.write(byteArrayOf(byte))
                                }

                                else -> {
                                    // Binary data cannot be part of a Hayes command.
                                    commandBuffer.clear()
                                    commandParseState = CommandParseState.SEEKING_AT
                                }
                            }
                        }
                    }
                }
            }
        }
        output?.flush()
    }

    private fun isPlausibleDialCandidate(command: String): Boolean {
        if (!command.startsWith("ATD", ignoreCase = true)) return false

        val dialArgument = command.drop(3)
        return dialArgument.all { char ->
            char.isDigit() ||
                char.uppercaseChar() in "ABCDTP" ||
                char in "*#+-() ."
        }
    }

    private fun resynchronizeInvalidDialCandidate() {
        val command = commandBuffer.toString()
        if (!command.startsWith("ATD", ignoreCase = true) || isPlausibleDialCandidate(command)) {
            return
        }

        val laterAt = command.lowercase().lastIndexOf("at")
        if (laterAt > 1) {
            commandBuffer.clear()
            commandBuffer.append(command.substring(laterAt))
        }
    }

    private fun receiveConnected(bytes: ByteArray) {
        when (connectedPhase) {
            ConnectedPhase.LOGIN_USERNAME,
            ConnectedPhase.LOGIN_PASSWORD,
            ConnectedPhase.LOGIN_COMMAND -> receiveLogin(bytes)

            ConnectedPhase.PPP -> pppHandler.receive(bytes)
        }
    }

    private fun receiveLogin(bytes: ByteArray) {
        var index = 0
        while (index < bytes.size) {
            if (connectedPhase == ConnectedPhase.PPP) {
                pppHandler.receive(bytes.copyOfRange(index, bytes.size))
                return
            }

            val value = bytes[index].toInt() and 0xff
            val char = value.toChar()

            when (value) {
                8, 127 -> {
                    if (loginBuffer.isNotEmpty()) {
                        loginBuffer.deleteCharAt(loginBuffer.lastIndex)
                    }
                }

                13 -> {
                    val line = loginBuffer.toString()
                    loginBuffer.clear()
                    handleLoginLine(line)
                }

                10 -> Unit

                else -> {
                    if (char.code in 0x20..0x7e) {
                        loginBuffer.append(char)
                    }
                }
            }

            index++
        }
    }

    private fun handleLoginLine(rawLine: String) {
        when (connectedPhase) {
            ConnectedPhase.LOGIN_USERNAME -> {
                if (rawLine.isEmpty()) {
                    writeRaw("\r\nUsername: ")
                    return
                }

                logger("LOGIN <= username received")
                pendingLoginUsername = rawLine
                connectedPhase = ConnectedPhase.LOGIN_PASSWORD
                writeRaw("\r\nPassword: ")
            }

            ConnectedPhase.LOGIN_PASSWORD -> {
                logger("LOGIN <= password received")

                if (pendingLoginUsername == config.username && rawLine == config.password) {
                    pendingLoginUsername = null
                    connectedPhase = ConnectedPhase.LOGIN_COMMAND
                    logger("LOGIN authentication accepted")
                    writeRaw("\r\n> ")
                } else {
                    pendingLoginUsername = null
                    connectedPhase = ConnectedPhase.LOGIN_USERNAME
                    logger("LOGIN authentication rejected")
                    writeRaw("\r\nLogin incorrect\r\nUsername: ")
                }
            }

            ConnectedPhase.LOGIN_COMMAND -> {
                val command = rawLine.trim()
                if (command.isNotEmpty()) {
                    logger("LOGIN <= command: ${command.take(128)}")
                }

                if (isPppCommand(command)) {
                    writeRaw("\r\nPPP.\r\n")
                    startPpp()
                } else {
                    if (command.isNotEmpty()) {
                        logger("LOGIN .. ignoring unrecognized terminal command")
                    }
                    writeRaw("\r\n> ")
                }
            }

            ConnectedPhase.PPP -> Unit
        }
    }

    private fun isPppCommand(value: String): Boolean {
        val command = value.trim().lowercase()
        return command == "p" ||
            command == "ppp" ||
            command == "%p" ||
            command == "%ppp" ||
            command.startsWith("p ") ||
            command.startsWith("ppp ") ||
            command.startsWith("%p ") ||
            command.startsWith("%ppp ")
    }

    private fun startPpp() {
        if (pppStarted) return

        loginBuffer.clear()
        pendingLoginUsername = null
        connectedPhase = ConnectedPhase.PPP
        pppStarted = true
        logger("PPP data mode active")
        if (output != null) {
            pppHandler.connected()
        }
    }

    fun attachOutput(output: OutputStream) {
        check(this.output == null) { "Modem output is already attached" }
        pppHandler.attachOutput(output)
        this.output = output
    }

    fun attachCarrierPresent(setCarrierPresent: (Boolean) -> Unit) {
        this.setCarrierPresent = setCarrierPresent
        setCarrierPresent(false)
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
                reset()
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
        setCarrierPresent(false)
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

        setCarrierPresent(true)
        connect()
    }

    private fun logToneProgress(progress: ToneProgress) {
        logger("TONE [${progress.step.name.lowercase()}] ${progress.description}")
    }

    private fun connect() {
        state = State.CONNECTED
        connectedPhase = if (config.username == null) {
            ConnectedPhase.PPP
        } else {
            ConnectedPhase.LOGIN_USERNAME
        }
        pppStarted = false

        logger("MODEM connected")
        respond("CONNECT $baudRate")

        if (output == null) {
            connectedPhase = ConnectedPhase.PPP
            return
        }

        if (config.username == null) {
            logger("LOGIN disabled; PPP data mode ready")
            startPpp()
            return
        }

        loginBuffer.clear()
        pendingLoginUsername = null
        logger("LOGIN => Username prompt")
        writeRaw("\r\nUsername: ")
    }

    private fun reset() {
        state = State.COMMAND
        connectedPhase = ConnectedPhase.PPP
        pppStarted = false
        echo = true
        loginBuffer.clear()
        pendingLoginUsername = null
        setCarrierPresent(false)
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
        pppHandler.close()
        tonePlayer.close()
    }
}
