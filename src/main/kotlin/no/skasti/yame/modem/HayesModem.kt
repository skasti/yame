package no.skasti.yame.modem

import no.skasti.yame.ppp.PppHandler
import no.skasti.yame.ppp.RetroPppHandler
import no.skasti.yame.observer.YameEvent
import no.skasti.yame.tone.DialString
import no.skasti.yame.tone.JavaSoundTonePlayer
import no.skasti.yame.tone.TonePlayer
import no.skasti.yame.tone.ToneProgress
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.nio.charset.StandardCharsets

class HayesModem(
    private var output: OutputStream? = null,
    private val baudRate: Int,
    private val config: HayesModemConfig = HayesModemConfig.fromConfigStore(),
    private val tonePlayer: TonePlayer = JavaSoundTonePlayer(),
    private val logger: (String) -> Unit = ::println,
    private val pppLogger: (String) -> Unit = logger,
    private val dnsLogger: (String) -> Unit = pppLogger,
    private val transferLogger: (String) -> Unit = pppLogger,
    private val proxyLogger: (String) -> Unit = pppLogger,
    private val diagnosticSink: (YameEvent) -> Unit = {},
    private val pppHandler: PppHandler = RetroPppHandler(
        disconnectOnTerminate = true,
        subscribeToConfiguration = false,
        logger = pppLogger,
        dnsLogger = dnsLogger,
        transferLogger = transferLogger,
        proxyLogger = proxyLogger,
        diagnosticSink = diagnosticSink,
    ),
    private var setCarrierPresent: (Boolean) -> Unit = { /* no-op */ },
    private val escapeGuardMillis: Long = 1_000,
) : Closeable {
    enum class State {
        COMMAND,
        DIALING,
        CONNECTED,
        /** Modem call is still active, but Hayes AT commands are accepted. */
        ONLINE_COMMAND,
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
    private val escapeScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "yame-modem-escape").apply { isDaemon = true }
    }
    private var escapeTask: ScheduledFuture<*>? = null
    private var escapePluses = 0
    private var lastPppByteAt = System.nanoTime()
    private var commandParseState = CommandParseState.SEEKING_AT
    private var pendingCommandA = 'A'
    private var echo = true
    @Volatile
    private var toneSimulationEnabled = config.toneSimulationEnabled
    private val loginBuffer = StringBuilder()
    private var connectedPhase = ConnectedPhase.PPP
    private var pppStarted = false
    private var pendingLoginUsername: String? = null
    @Volatile
    private var loginCredentials = LoginCredentials(config.username, config.password)
    private var connectedLoginCredentials = loginCredentials

    init {
        require(escapeGuardMillis > 0) { "Escape guard must be positive" }
        output?.let(pppHandler::attachOutput)
        setCarrierPresent(false)
    }

    @Synchronized
    fun receive(bytes: ByteArray) {
        when (state) {
            State.COMMAND, State.ONLINE_COMMAND -> receiveCommands(bytes)
            State.DIALING -> Unit
            State.CONNECTED -> receiveConnected(bytes)
        }
    }

    fun updateLoginCredentials(username: String?, password: String?) {
        val validated = config.copy(username = username, password = password)
        loginCredentials = LoginCredentials(validated.username, validated.password)
    }

    fun updateToneSimulationEnabled(enabled: Boolean) {
        toneSimulationEnabled = enabled
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

                        13, 10 -> { // Accept both CR and LF (Trumpet BYE.CMD sends LF).
                            if (echo) writeRaw("\r")
                            val command = selectCommandCandidate(commandBuffer.toString())
                            commandBuffer.clear()
                            commandParseState = CommandParseState.SEEKING_AT
                            handleCommand(command)
                        }

                        else -> {
                            when {
                                value == 0x7d || value == 0x7e -> {
                                    // PPP framing/escape bytes cannot be part of a Hayes command.
                                    // Drop a false AT candidate and resume looking for a real one.
                                    commandBuffer.clear()
                                    commandParseState = CommandParseState.SEEKING_AT
                                }

                                char.code in 0x20..0x7e -> {
                                    commandBuffer.append(char)
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

    private fun selectCommandCandidate(command: String): String {
        val lower = command.lowercase()
        if (!lower.startsWith("at")) return command

        if (!lower.startsWith("atd")) {
            val laterAt = lower.lastIndexOf("at")
            return if (laterAt > 1) command.substring(laterAt) else command
        }

        // Dial strings are deliberately permissive: DialString.normalize() filters
        // presentation characters that many legacy diallers include. Do not treat an
        // embedded DTMF A/T sequence as a new command merely because a later formatting
        // character would be discarded. Only abandon ATD for a later, unambiguous
        // modem-control command.
        var searchFrom = lower.length - 2
        while (searchFrom > 1) {
            val laterAt = lower.lastIndexOf("at", searchFrom)
            if (laterAt <= 1) break

            val candidate = command.substring(laterAt)
            if (isUnambiguousControlCommand(candidate)) {
                return candidate
            }
            searchFrom = laterAt - 1
        }

        return command
    }

    private fun isUnambiguousControlCommand(command: String): Boolean {
        val upper = command.uppercase()
        return upper == "AT" ||
            upper.startsWith("ATZ") ||
            upper.startsWith("AT&F") ||
            upper.startsWith("ATE0") ||
            upper.startsWith("ATE1") ||
            upper.startsWith("ATH")
    }

    private fun receiveConnected(bytes: ByteArray) {
        when (connectedPhase) {
            ConnectedPhase.LOGIN_USERNAME,
            ConnectedPhase.LOGIN_PASSWORD,
            ConnectedPhase.LOGIN_COMMAND -> receiveLogin(bytes)

            ConnectedPhase.PPP -> receivePpp(bytes)
        }
    }

    private fun receivePpp(bytes: ByteArray) {
        // Silence before and after +++ is mandatory. Buffer only candidate escape
        // bytes; forward ordinary binary PPP traffic unchanged.
        val forward = ByteArrayOutputStream(bytes.size + 3)
        for (index in bytes.indices) {
            val now = System.nanoTime()
            val idle = now - lastPppByteAt >= TimeUnit.MILLISECONDS.toNanos(escapeGuardMillis)
            if (escapePluses == 3 && idle) {
                if (forward.size() > 0 && pppHandler.receive(forward.toByteArray())) {
                    hangup()
                    return
                }
                enterOnlineCommand()
                receiveCommands(bytes.copyOfRange(index, bytes.size))
                return
            }
            lastPppByteAt = now
            val byte = bytes[index]
            if (escapePluses > 0) {
                if (byte == '+'.code.toByte() && escapePluses < 3) {
                    escapePluses++
                    scheduleEscapeCheck()
                } else {
                    repeat(escapePluses) { forward.write('+'.code) }
                    clearEscape()
                    forward.write(byte.toInt())
                }
            } else if (byte == '+'.code.toByte() && idle) {
                escapePluses = 1
                scheduleEscapeCheck()
            } else {
                forward.write(byte.toInt())
            }
        }
        if (forward.size() > 0 && pppHandler.receive(forward.toByteArray())) hangup()
    }

    private fun scheduleEscapeCheck() {
        escapeTask?.cancel(false)
        escapeTask = escapeScheduler.schedule({
            synchronized(this) {
                if (state == State.CONNECTED && connectedPhase == ConnectedPhase.PPP && escapePluses > 0) {
                    val elapsed = System.nanoTime() - lastPppByteAt
                    val guard = TimeUnit.MILLISECONDS.toNanos(escapeGuardMillis)
                    if (elapsed < guard) {
                        scheduleEscapeCheck()
                    } else if (escapePluses == 3) {
                        enterOnlineCommand()
                    } else {
                        val pluses = ByteArray(escapePluses) { '+'.code.toByte() }
                        clearEscape()
                        if (pppHandler.receive(pluses)) hangup()
                    }
                }
            }
        }, escapeGuardMillis + 10, TimeUnit.MILLISECONDS)
    }

    private fun clearEscape() {
        escapeTask?.cancel(false)
        escapeTask = null
        escapePluses = 0
    }

    private fun enterOnlineCommand() {
        clearEscape()
        state = State.ONLINE_COMMAND
        commandBuffer.clear()
        commandParseState = CommandParseState.SEEKING_AT
        logger("MODEM online command mode")
        respond("OK")
    }

    @Synchronized
    fun hangup() = hangup(announceNoCarrier = true)

    private fun hangup(announceNoCarrier: Boolean) {
        if (state != State.CONNECTED && state != State.ONLINE_COMMAND) return
        clearEscape()
        state = State.COMMAND
        connectedPhase = ConnectedPhase.PPP
        pppStarted = false
        commandBuffer.clear()
        commandParseState = CommandParseState.SEEKING_AT
        loginBuffer.clear()
        pendingLoginUsername = null
        setCarrierPresent(false)
        try {
            pppHandler.disconnected()
        } catch (e: Exception) {
            logger("PPP !! Cleanup after hangup failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (announceNoCarrier) respond("NO CARRIER")
    }

    private fun receiveLogin(bytes: ByteArray) {
        var index = 0
        while (index < bytes.size) {
            if (connectedPhase == ConnectedPhase.PPP) {
                receivePpp(bytes.copyOfRange(index, bytes.size))
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

                if (
                    pendingLoginUsername == connectedLoginCredentials.username &&
                    rawLine == connectedLoginCredentials.password
                ) {
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

        clearEscape()
        lastPppByteAt = System.nanoTime()
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
            upper == "ATO" && state == State.ONLINE_COMMAND -> {
                state = State.CONNECTED
                lastPppByteAt = System.nanoTime()
                respond("CONNECT $baudRate")
            }
            upper == "ATZ" || upper.startsWith("ATZ") -> {
                if (state == State.ONLINE_COMMAND) hangup(announceNoCarrier = false)
                reset()
                respond("OK")
            }
            upper == "AT&F" || upper.startsWith("AT&F") -> {
                if (state == State.ONLINE_COMMAND) hangup(announceNoCarrier = false)
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
                if (state == State.ONLINE_COMMAND) hangup(announceNoCarrier = false)
                else reset()
                respond("OK")
            }
            upper.startsWith("ATD") && state == State.ONLINE_COMMAND -> respond("ERROR")
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

        if (toneSimulationEnabled) {
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
        }

        setCarrierPresent(true)
        connect()
    }

    private fun logToneProgress(progress: ToneProgress) {
        logger("TONE [${progress.step.name.lowercase()}] ${progress.description}")
    }

    private fun connect() {
        connectedLoginCredentials = loginCredentials
        state = State.CONNECTED
        connectedPhase = if (connectedLoginCredentials.username == null) {
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

        if (connectedLoginCredentials.username == null) {
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

    @Synchronized
    override fun close() {
        clearEscape()
        escapeScheduler.shutdownNow()
        setCarrierPresent(false)
        try { pppHandler.close() } finally { tonePlayer.close() }
    }

    private data class LoginCredentials(
        val username: String?,
        val password: String?,
    )
}
