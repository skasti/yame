# Serial Modem Emulator

A small Kotlin/JVM project that makes a modern computer behave like a basic Hayes-compatible modem over a physical RS-232 serial connection.

The intended end state is:

```text
old laptop -> serial/null-modem -> Hayes emulator -> PPP server -> IP/NAT -> Internet
```

## Current milestone

The emulator currently:

- opens a serial port at 115200 baud, 8N1
- supports basic Hayes-style AT command handling
- accepts unknown AT initialization commands with `OK`
- answers dial commands (`ATD...` / `ATDT...`) with simulated telephone dialing and modem-handshake sounds before `CONNECT 115200`
- generates a Norwegian 425 Hz dial tone, standard DTMF digits, and 425 Hz ringback cadence
- simulates a V.8/V.34-style modem answer, negotiation, line probing and training sequence after pickup
- logs the individual dialing/handshake stages as they become audible
- normalizes formatted dial strings before generating DTMF
- switches to raw data mode after `CONNECT`
- decodes asynchronous PPP framing in connected mode
- handles PPP flag/escape processing, receive ACCM filtering and FCS-16 validation
- logs complete valid PPP frames with their decoded protocol and payload
- logs malformed PPP frames separately for diagnostics

PPP framing is implemented, but PPP protocol negotiation and Internet routing are not. The next milestone is LCP negotiation: replying to the client's LCP Configure-Request, sending YAME's own Configure-Request, and progressing far enough to observe IPCP from the old laptop.

## Telephone and modem tone simulation

Dialing audio lives in the separate `no.skasti.serialmodem.tone` package behind the `TonePlayer` interface; `JavaSoundTonePlayer` is the production implementation. It is blocking by design: `pickupTime` controls how long the simulated remote telephone rings before answering, and the call does not return until the subsequent modem handshake has also completed.

For example:

```kotlin
import no.skasti.serialmodem.tone.JavaSoundTonePlayer
import kotlin.time.Duration.Companion.seconds

JavaSoundTonePlayer().use { player ->
    player.dial(
        number = "+47 345 76 543",
        pickupTime = 2.seconds,
    )
}
```

The default sequence is:

1. 425 Hz dial tone for 500 ms
2. DTMF digits at 95 ms per tone with 95 ms inter-digit spacing
3. Norwegian-style 425 Hz ringback at 1 s on / 4 s off until `pickupTime` has elapsed
4. remote modem answer (`ANSam`-style 2100 Hz tone)
5. simulated V.8 modem-capability negotiation using V.21 frequency pairs
6. V.34-style 1200/2400 Hz phase-2 carriers and 1800 Hz guard tone
7. V.34 L1/L2 multi-tone line probing
8. scrambled QAM-like equalizer training and final parameter/data exchange
9. return from `dial()`, after which the Hayes emulator sends `CONNECT`

The V.8/V.34 handshake is an **auditory simulation**, not a decodable modem waveform. Where practical it uses the actual standardized frequencies and timings (including ANSam modulation and the V.34 line-probing tone set), while capability messages and final training data are synthesized only to reproduce the characteristic sound and delay.

The later training stages deliberately use deterministic pseudo-random QAM-like symbol streams rather than a sequence of clean tones or added white noise. A coarse 4-point constellation is used for the first training section and a denser 16-point constellation for the final exchange, producing the more broadband, scratchy sound associated with high-speed modem training while keeping the preview reproducible from run to run. This approximates the audible character of scrambled V.34 training/data and is not intended to be a bit-accurate TRN or MP waveform.

The default V.34 handshake adds about 6.2 seconds after pickup. Callers that only want the telephone part can disable it explicitly:

```kotlin
import no.skasti.serialmodem.tone.HandshakeProfile
import no.skasti.serialmodem.tone.JavaSoundTonePlayer

JavaSoundTonePlayer().use { player ->
    player.dial(
        number = "5551234",
        pickupTime = 2.seconds,
        handshakeProfile = HandshakeProfile.NONE,
    )
}
```

Callers can optionally receive progress events as the corresponding part of the waveform is actually being played:

```kotlin
JavaSoundTonePlayer().use { player ->
    player.dial(
        number = "5551234",
        pickupTime = 2.seconds,
        onProgress = { progress ->
            println("${progress.step}: ${progress.description}")
        },
    )
}
```

The progress monitor follows the Java Sound output line's rendered frame position, so logging does not split or drain the PCM stream between stages. The callback is invoked from a small playback-monitor thread and should therefore remain lightweight.

Dial strings are normalized before DTMF is generated. A leading `+` is converted to Norway's international access prefix `00`, so `+47 345 76 543` is dialed as `004734576543`. Spaces, dashes, parentheses and other presentation characters are ignored, and a leading Hayes `T` or `P` dial-mode selector is removed.

The modem owns dialing-tone playback and its timing configuration. By default it uses a 500 ms dial tone, a two-second simulated pickup time, and the `v34` handshake profile. These values can be overridden from the command line. Audio failure is treated as cosmetic, so systems without a configured sound device can still use the modem emulator.

## Requirements

- JDK 21
- a USB-to-RS232 adapter
- a null-modem connection to the old laptop

The repository includes the Gradle Wrapper, so a separate Gradle installation is not required. Serial access uses [jSerialComm](https://github.com/Fazecast/jSerialComm).

## Run

The examples below use the Unix-style wrapper command. On Windows, replace `./gradlew` with `gradlew.bat`.

List detected serial ports:

```shell
./gradlew run --args="--list"
```

Start the emulator on a detected serial port:

```shell
./gradlew run --args="--port <port>"
```

Dialing/handshake progress is logged automatically. Modem timing and handshake behavior can be overridden explicitly:

```shell
./gradlew run --args="--port <port> --pickup-time 3s --dial-tone-time 750ms --handshake-profile v34"
```

Specify another line speed if needed:

```shell
./gradlew run --args="--port <port> --baud 57600"
```

## Test tone

Play a complete dialing and default V.34 modem-handshake sequence without opening a serial port:

```shell
./gradlew run --args="--test-tone '+47 345 76 543'"
```

The test uses the same modem configuration path as a real AT dial. The defaults are a 500 ms dial tone, two seconds before pickup, and the `v34` handshake profile. All three can be overridden:

```shell
./gradlew run --args="--test-tone '+47 345 76 543' --pickup-time 3s --dial-tone-time 750ms --handshake-profile v34"
./gradlew run --args="--test-tone '+47 345 76 543' --handshake-profile none"
```

Dialing/handshake progress is always logged. Typical output looks like:

```text
MODEM dialing 004734576543
TONE [dial_tone] 425 Hz dial tone
TONE [dtmf_dialing] DTMF dialing 004734576543
TONE [ringback] 425 Hz ringback; waiting for pickup...
TONE [remote_answered] remote side answered
TONE [v8_ansam] V.8 ANSam answer tone (2100 Hz)
TONE [v8_negotiation] V.8 CM/JM capability negotiation
TONE [v34_phase2] V.34 phase 2 carriers and guard tone
TONE [v34_line_probe_l1] V.34 L1 line probe
TONE [v34_line_probe_l2] V.34 L2 line probe
TONE [v34_training] V.34 scrambled QAM-like equalizer training
TONE [v34_final_exchange] V.34 final parameter/data exchange
TONE [complete] dialing/handshake complete; CONNECT may be returned
MODEM connected
AT => CONNECT 115200
```

`--test-tone` calls the same public `HayesModem.dial()` path used by AT dialing, but discards serial output instead of opening a port. With the `v34` profile, the V.8/V.34 handshake continues after the simulated pickup before the test exits. With `none`, the test exits immediately after pickup.

Run the automated tests with:

```shell
./gradlew test
```

## Expected first test

Configure the old laptop's modem/dial-up connection to use its serial port and dial any number, for example `5551234`.

Typical exchange should look roughly like:

```text
AT <= AT
AT => OK
AT <= AT&F...
AT => OK
AT <= ATDT5551234
MODEM dialing 5551234
# dial/ring/handshake audio plays here
MODEM connected
AT => CONNECT 115200
PPP <= protocol=LCP (0xC021), payload=24 bytes: 01 0B 00 18 01 04 02 40 ...
```

Repeated LCP Configure-Request frames are expected for now because YAME does not yet reply to PPP control protocols. This has been verified against Trumpet Winsock on a real Windows 3.1 laptop. The next milestone is implementing minimal LCP negotiation so the client can advance to IPCP.

## Supported modem behavior

Explicitly handled commands currently include:

- `AT`
- `ATZ`
- `AT&F`
- `ATE0`
- `ATE1`
- `ATH`
- `ATD...`

Other valid-looking `AT...` commands are accepted with `OK` for compatibility with old modem initialization strings.
