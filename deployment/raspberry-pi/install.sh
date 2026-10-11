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
apt-get install -y curl jq python3 util-linux

# Bookworm does not ship OpenJDK 21 in its standard repositories. An already
# installed Java 21 runtime (e.g. Temurin 21) is perfectly usable.
has_java_21() {
  command -v java >/dev/null 2>&1 &&
    java -XshowSettings:properties -version 2>&1 |
      grep -Eq '^[[:space:]]*java\.specification\.version = 21[[:space:]]*$'
}

if ! has_java_21; then
  candidate="$(apt-cache policy openjdk-21-jre-headless | awk '/Candidate:/ { print $2; exit }')"
  if [[ -z "$candidate" || "$candidate" == "(none)" ]]; then
    echo "Java 21 is not installed and this OS does not offer openjdk-21-jre-headless." >&2
    echo "On Raspberry Pi OS Bookworm, install an ARM64 Java 21 runtime (for example Temurin 21)" >&2
    echo "and make it the default 'java', then run the installer again." >&2
    exit 1
  fi
  apt-get install -y openjdk-21-jre-headless
fi

if ! has_java_21; then
  echo "Expected Java 21 as the default 'java' command; found:" >&2
  java -version >&2 || true
  exit 1
fi
java -version

install -d -m 755 /opt/yame/releases
install -d -m 700 -o "$YAME_USER" -g "$(id -gn "$YAME_USER")" /var/lib/yame
# Never overwrite configuration, even when re-running the installer.
if [[ -f /var/lib/yame/yame.ini ]]; then
  chown "$YAME_USER:$(id -gn "$YAME_USER")" /var/lib/yame/yame.ini
  chmod 600 /var/lib/yame/yame.ini
fi
usermod -aG dialout,audio "$YAME_USER"

install -d -m 755 /usr/local/lib/yame
install -m 644 "$SCRIPT_DIR/extract-release.py" /usr/local/lib/yame/extract-release.py
install -m 755 "$SCRIPT_DIR/yame-update" /usr/local/bin/yame-update

# On a fresh installation, establish a usable local release before taking
# over tty1. A network/download failure must leave the login console intact.
if [[ ! -x /opt/yame/current/bin/yame ]]; then
  echo "Downloading the initial YAME release before changing the console..."
  if ! /usr/local/bin/yame-update || [[ ! -x /opt/yame/current/bin/yame ]]; then
    echo "Initial YAME installation failed; leaving tty1 login untouched." >&2
    echo "Check GitHub connectivity and ARM64 release assets, then retry." >&2
    exit 1
  fi
fi

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
