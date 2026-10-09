# Raspberry Pi 3 (64-bit)

YAME publishes a Linux ARM64 ZIP asset alongside its standard JVM ZIP for each
new release. The ARM64 archive is built and smoke-tested on a native GitHub
Actions ARM64 runner. It **does not bundle Java**.

## Requirements

- Raspberry Pi 3 with a **64-bit** Raspberry Pi OS Lite installation
- Java 21 runtime (install from the OS repository when available)
- USB-to-RS232 adapter and (if required) a null-modem cable
- HDMI display, for example 800x480
- Audio device for simulated modem tones

Verify architecture and Java:

```sh
uname -m        # aarch64
java -version   # 21
```

## Download and launch

Download the `yame-<version>-linux-arm64.zip` file from
[GitHub Releases](https://github.com/skasti/yame/releases/latest).
Unpack the ZIP and run the launcher inside its `bin` directory:

```sh
unzip yame-*-linux-arm64.zip
cd yame-*/
./bin/yame --list   # show serial ports
./bin/yame          # launch interactive dashboard on local tty1
```

Run on the Pi's HDMI console to use the interactive TUI. The Linux virtual
console typically reports `TERM=linux` and has limited colors; the display
should be tested at 100 columns by 30 rows for an 800x480 screen.

By default, YAME stores `yame.ini` in the working directory; use
`--config /path/to/yame.ini` if you prefer a fixed configuration location.

This release asset is a ZIP of the standard Gradle JVM distribution, produced
and CLI-smoke-tested on Linux ARM64. It is not a self-updating appliance image;
systemd startup and automatic release downloads are separate follow-up work.
