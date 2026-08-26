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
- answers dial commands (`ATD...` / `ATDT...`) with simulated telephone dialing sounds before `CONNECT 115200`
- generates a Norwegian 425 Hz dial tone, standard DTMF digits, and 425 Hz ringback cadence
- switches to raw data mode after `CONNECT`
- logs the raw bytes received in data mode in hexadecimal

PPP negotiation and Internet routing are not implemented yet. The raw data logger is intentionally the next diagnostic step: it lets us observe what the old laptop actually sends after the modem connection is established.

## Telephone tone simulation

Dialing audio lives in the separate `no.skasti.serialmodem.tone` package. It is blocking by design, so the caller does not continue until the simulated remote side picks up.

For example:

```kotlin
import no.skasti.serialmodem.tone.tone
import kotlin.time.Duration.Companion.seconds

tone.dial(
    number = "004734576543",
    pickupTime = 2.seconds,
)
```

The default sequence is:

1. 425 Hz dial tone for 500 ms
2. DTMF digits at 95 ms per tone with 95 ms inter-digit spacing
3. Norwegian-style 425 Hz ringback at 1 s on / 4 s off
4. return from `dial()` when `pickupTime` has elapsed

The normal modem entry point currently uses a simulated pickup time of two seconds. Audio failure is treated as cosmetic, so systems without a configured sound device can still use the modem emulator.

## Requirements

- JDK 21
- Gradle
- a USB-to-RS232 adapter
- a null-modem connection to the old laptop

Serial access uses [jSerialComm](https://github.com/Fazecast/jSerialComm).

## Run

List detected serial ports:

```shell
gradle run --args="--list"
```

Start the emulator on a detected serial port:

```shell
gradle run --args="--port <port>"
```

Specify another line speed if needed:

```shell
gradle run --args="--port <port> --baud 57600"
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
