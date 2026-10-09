# Raspberry Pi deployment (HDMI TUI appliance)

These files configure a Raspberry Pi 3 running **Raspberry Pi OS Lite 64-bit**
to download the latest stable YAME release on boot and run the interactive
dashboard directly on the HDMI Linux virtual console (`tty1`). No desktop,
Wayland, X11, Git, Gradle or Kotlin compiler is needed on the Pi.

YAME's Linux ARM64 release ZIP is a Gradle JVM distribution; it does **not**
bundle Java. The installation therefore requires an ARM64 Java 21 runtime.

## Before installing

1. Flash Raspberry Pi OS Lite **64-bit** and enable SSH in Raspberry Pi Imager.
   Verify that you can connect over SSH before disabling the local login
   prompt. SSH is the recovery path if the TUI cannot start.
2. Connect the HDMI screen (tested target: **800x480**), keyboard, speaker and
   USB-to-RS232 adapter. Configure HDMI mode if automatic detection fails.
3. From the Pi's HDMI console, check that `echo "$TERM"` reports `linux`,
   `stty size` reports approximately `30 100`, and `tput colors` reports `8`.
   The Linux virtual console supports fewer colors than a desktop emulator.
4. Check `uname -m` reports `aarch64`. The 32-bit OS is not supported by
   this deployment.
5. Confirm [GitHub Releases](https://github.com/skasti/yame/releases/latest)
   includes a `yame-<version>-linux-arm64.zip` asset. This deployment is
   available only for releases that include that asset.

## Installation

Install from a local checkout of YAME (you can clone/download the repo on
another computer and copy `deployment/raspberry-pi/` to the Pi):

```sh
sudo bash deployment/raspberry-pi/install.sh
```

The installer uses the account invoking `sudo` for the UI service. For a
different existing local account:

```sh
sudo YAME_USER=myuser bash deployment/raspberry-pi/install.sh
```

The installer installs Java 21 and support utilities, adds the account to
`dialout` and `audio`, installs systemd units, disables `getty@tty1`
and starts YAME on `tty1`. It is safe to rerun after modifying deployment
files. It does not overwrite `yame.ini`.

If you have already been running YAME manually, **stop that instance**
before enabling the service; otherwise both processes may try to open the
same serial adapter.

## Boot and update behavior

```text
Boot -> yame-update.service -> yame.service -> HDMI tty1 TUI
```

- `yame-update.service` is a one-shot dependency of `yame.service`. It checks
  GitHub's latest **stable** release each time YAME starts, including on boot.
- Updates are downloaded to a staging directory on the same filesystem and
  unpacked before activation. `/opt/yame/current` is changed via an atomic
  symlink rename, with the prior target saved in `/opt/yame/previous`.
- When the GitHub API, release asset or download is unavailable, the updater
  retains an existing installation and lets the UI start. Initial installation
  needs an internet connection and a published ARM64 asset.
- If GitHub publishes an SHA256 asset digest, the updater verifies it before
  installation. If the release does not include a digest, GitHub HTTPS is
  relied upon. Releases must be trusted; do not run this installer against an
  untrusted fork.
- The updater *does not* automatically restart an already running modem
  connection. There is no background timer in this first version.
- `previous` provides a manual rollback target, **not automatic startup
  health-check rollback**.

The update process can take up to roughly three minutes with a slow network.
YAME still starts without internet if a release was installed previously.

## Files and ownership

```text
/opt/yame/releases/vX.Y.Z/       unpacked version directory
/opt/yame/current               symlink to selected version
/opt/yame/previous              previous selected version (if any)
/var/lib/yame/yame.ini          persistent configuration (private to service user)
/var/lib/yame/logs/             application logs
/usr/local/bin/yame-update      root-run updater
/etc/systemd/system/yame.service
/etc/systemd/system/yame-update.service
```

Configuration and runtime data are intentionally outside the versioned
application directory. With an existing `yame.ini`, copy it into
`/var/lib/yame/` and make sure the configured UI user owns it:

```sh
sudo chown "$(whoami):$(id -gn)" /var/lib/yame/yame.ini
sudo chmod 600 /var/lib/yame/yame.ini
```

The updater runs as root; YAME runs as your configured non-root user with
`dialout` and `audio` supplemental groups. The Java heap is capped at
256 MiB for the Raspberry Pi 3's 1 GiB RAM.

## Useful operations

```sh
systemctl status yame.service yame-update.service
sudo systemctl restart yame.service    # also checks for updates
sudo systemctl stop yame.service
sudo /usr/local/bin/yame-update       # download/update only; does not restart UI
journalctl -u yame.service -b -n 100 --no-pager
journalctl -u yame-update.service -b -n 100 --no-pager
ls -l /opt/yame/current /opt/yame/previous
```

For manual rollback while connected over SSH:

```sh
sudo systemctl stop yame.service
cd /opt/yame
sudo ln -s "$(readlink previous)" .current-rollback
sudo mv -Tf .current-rollback current
# Restarting yame.service triggers an update check again, which may restore
# latest. Temporarily disable the update dependency if troubleshooting a bad
# release, or start the older launcher manually on tty1.
```

To restore the text login console:

```sh
sudo systemctl disable --now yame.service
sudo systemctl enable --now getty@tty1.service
```

## Troubleshooting

- **No YAME screen**: inspect `journalctl -u yame -b` over SSH. Verify
  `/opt/yame/current/bin/yame` exists and Java is installed.
- **`AccessDeniedException: /var/lib/yame/yame.ini`**: correct file and
  directory ownership. The directory must be accessible to the service user
  and config should have mode 600.
- **No serial port**: use `ls /dev/serial/by-id/` when supported by the
  adapter, or `ls -l /dev/ttyUSB*`; choose the port through YAME's palette.
- **No modem audio**: verify the audio output with `aplay -l` and an ALSA
  playback test. Pi 3 supports HDMI and analog audio, depending on settings.
- **Dashboard too tall on first draw**: a known observed initial render
  behavior on the 100x30 Linux console; opening the command palette can cause
  a correct redraw. This deployment PR does not modify TUI rendering.
- **No internet on first boot**: the updater cannot install a release yet.
  Check the update journal; once online, restart `yame.service`.

## Scope

This configuration targets a dedicated Pi displaying YAME on the local HDMI
virtual console. It does not create a desktop app, bundle a JRE, modify YAME
itself, configure a fixed serial adapter rule, or interrupt active sessions to
apply updates in the background.
