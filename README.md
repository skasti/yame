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
- normalizes formatted dial strings before generating DTMF
- switches to raw data mode after `CONNECT`
- logs the raw bytes received in data mode in hexadecimal

PPP negotiation and Internet routing are not implemented yet. The raw data logger is intentionally the next diagnostic step: it lets us observe what the old laptop actually sends after the modem connection is established.

## Telephone and modem tone simulation

Dialing audio lives in the separate `no.skasti.serialmodem.tone` package. It is blocking by design: `pickupTime` controls how long the simulated remote telephone rings before answering, and the call does not return until the subsequent modem handshake has also completed.

For example:

```kotlin
import no.skasti.serialmodem.tone.tone
import kotlin.time.Duration.Companion.seconds

tone.dial(
    number = "+47 345 76 543",
    pickupTime = 2.seconds,
)
```

The default sequence is:

1. 425 Hz dial tone for 500 ms
2. DTMF digits at 95 ms per tone with 95 ms inter-digit spacing
3. Norwegian-style 425 Hz ringback at 1 s on / 4 s off until `pickupTime` has elapsed
4. remote modem answer (`ANSam`-style 2100 Hz tone)
5. simulated V.8 modem-capability negotiation using V.21 frequency pairs
6. V.34-style 1200/2400 Hz phase-2 carriers and 1800 Hz guard tone
7. V.34 L1/L2 multi-tone line probing
8. simulated equalizer/training and final carrier lock
9. return from `dial()`, after which the Hayes emulator sends `CONNECT`

The V.8/V.34 handshake is an **auditory simulation**, not a decodable modem waveform. Where practical it uses the actual standardized frequencies and timings (including ANSam modulation and the V.34 line-probing tone set), while capability messages and final training data are synthesized only to reproduce the characteristic sound and delay.

The default V.34 handshake adds about 6.2 seconds after pickup. Callers that only want the telephone part can disable it explicitly:

```kotlin
import no.skasti.serialmodem.tone.HandshakeProfile

tone.dial(
    number = "5551234",
    pickupTime = 2.seconds,
    handshakeProfile = HandshakeProfile.NONE,
)
```

Dial strings are normalized before DTMF is generated. A leading `+` is converted to Norway's international access prefix `00`, so `+47 345 76 543` is dialed as `004734576543`. Spaces, dashes, parentheses and other presentation characters are ignored, and a leading Hayes `T` or `P` dial-mode selector is removed.

The normal modem entry point currently uses a simulated pickup time of two seconds. Audio failure is treated as cosmetic, so systems without a configured sound device can still use the modem emulator.

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

Specify another line speed if needed:

```shell
./gradlew run --args="--port <port> --baud 57600"
```

## Test tone

Play a complete dialing and modem-handshake sequence without opening a serial port:

```shell
./gradlew run --args="--test-tone '+47 345 76 543'"
```

The tone test deliberately uses longer dial-tone and pickup timings so the generated cadence is easy to hear. After the simulated pickup, the V.8/V.34 handshake continues before the test exits.

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
DATA <= ... bytes: 7E FF 03 C0 21 ...
```

Once we have the `DATA` output from a real dial attempt, the next milestone is implementing PPP framing and LCP negotiation.

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
