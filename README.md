# Serial Modem Emulator

A small Kotlin/JVM project that makes a modern PC behave like a basic Hayes-compatible modem over a physical RS-232/COM port.

The intended end state is:

```text
old laptop -> serial/null-modem -> Hayes emulator -> PPP server -> IP/NAT -> Internet
```

## Current milestone

The emulator currently:

- opens a serial port at 115200 baud, 8N1
- supports basic Hayes-style AT command handling
- accepts unknown AT initialization commands with `OK`
- answers dial commands (`ATD...` / `ATDT...`) with `CONNECT 115200`
- switches to raw data mode after `CONNECT`
- logs the raw bytes received in data mode in hexadecimal

PPP negotiation and Internet routing are not implemented yet. The raw data logger is intentionally the next diagnostic step: it lets us observe what the old laptop actually sends after the modem connection is established.

## Requirements

- JDK 21
- Gradle
- a serial port or USB-to-RS232 adapter on the modern PC
- a null-modem connection to the old laptop

Serial access uses [jSerialComm](https://github.com/Fazecast/jSerialComm).

## Run

List detected ports:

```powershell
gradle run --args="--list"
```

Start the emulator on a Windows COM port:

```powershell
gradle run --args="--port COM3"
```

Specify another line speed if needed:

```powershell
gradle run --args="--port COM3 --baud 57600"
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
