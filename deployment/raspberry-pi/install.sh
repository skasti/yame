#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "Run with sudo: sudo bash deployment/raspberry-pi/install.sh" >&2
  exit 1
fi
if [[ "$(uname -m)" != "aarch64" ]]; then
  echo "Requires a 64-bit (aarch64) Raspberry Pi OS installation" >&2
  exit 1
fi

YAME_USER="${YAME_USER:-${SUDO_USER:-}}"
if [[ -z "$YAME_USER" || "$YAME_USER" == root ]] || ! id "$YAME_USER" >/dev/null 2>&1; then
  echo "Specify the existing non-root console user with YAME_USER=<username>" >&2
  exit 1
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
echo "Installing YAME for $YAME_USER (tty1)..."

apt-get update
apt-get install -y openjdk-21-jre-headless curl jq unzip util-linux
java -version

install -d -m 755 /opt/yame/releases
install -d -m 700 -o "$YAME_USER" -g "$(id -gn "$YAME_USER")" /var/lib/yame
# Never overwrite configuration, even when re-running the installer.
if [[ -f /var/lib/yame/yame.ini ]]; then
  chown "$YAME_USER:$(id -gn "$YAME_USER")" /var/lib/yame/yame.ini
  chmod 600 /var/lib/yame/yame.ini
fi
usermod -aG dialout,audio "$YAME_USER"

install -m 755 "$SCRIPT_DIR/yame-update" /usr/local/bin/yame-update
install -m 644 "$SCRIPT_DIR/yame-update.service" /etc/systemd/system/yame-update.service
install -m 644 "$SCRIPT_DIR/yame.service" /etc/systemd/system/yame.service

# Set the systemd service account without embedding a Pi-specific username.
mkdir -p /etc/systemd/system/yame.service.d
printf '[Service]\nUser=%s\n' "$YAME_USER" > /etc/systemd/system/yame.service.d/user.conf

systemctl daemon-reload
# Disabling the login prompt frees tty1 for YAME; SSH is the recovery path.
systemctl disable --now getty@tty1.service
systemctl enable --now yame.service

echo "Installed YAME services. Check with: systemctl status yame.service"
echo "Logs: journalctl -u yame-update.service -u yame.service -b"
