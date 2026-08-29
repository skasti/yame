#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$(mktemp -d)"
DOS_DRIVE="$WORK_DIR/dos"
PTY_LINK="$WORK_DIR/yame-tty"
SOCAT_LOG="$WORK_DIR/socat.log"
YAME_LOG="$WORK_DIR/yame.log"
DOSBOX_LOG="$WORK_DIR/dosbox.log"
DNS_FIXTURE_LOG="$WORK_DIR/dns-fixture.log"
HTTP_FIXTURE_LOG="$WORK_DIR/http-fixture.log"
DOSBOX_CONF="$WORK_DIR/dosbox.conf"
LS_PPP_ZIP="$WORK_DIR/lsppp.zip"
MTCP_ZIP="$WORK_DIR/mtcp.zip"
PORT=50453
HTTP_PORT=18080
BAUD=19200

LS_PPP_URL="https://www.ibiblio.org/pub/micro/pc-stuff/freedos/files/repositories/1.4/net/lsppp.zip"
LS_PPP_SHA1="5a6a1430aad2616b266be7d149bb3752beea319e"
MTCP_URL="https://www.ibiblio.org/pub/micro/pc-stuff/freedos/files/repositories/1.4/net/mtcp/20250604.0/mtcp.zip"
MTCP_SHA1="c6a319ae44ef49d03616968bd330b9c64cfab167"

DNS_FIXTURE_PID=""
HTTP_FIXTURE_PID=""
SOCAT_PID=""
YAME_PID=""
DOSBOX_PID=""
ORIGINAL_UNPRIVILEGED_PORT_START=""

cleanup() {
    local status=$?

    for pid in "$DOSBOX_PID" "$YAME_PID" "$SOCAT_PID" "$HTTP_FIXTURE_PID" "$DNS_FIXTURE_PID"; do
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
        fi
    done

    if [[ -n "$ORIGINAL_UNPRIVILEGED_PORT_START" ]]; then
        sudo sysctl -w \
            "net.ipv4.ip_unprivileged_port_start=$ORIGINAL_UNPRIVILEGED_PORT_START" \
            >/dev/null || true
    fi

    if [[ $status -ne 0 ]]; then
        echo
        echo "=== YAME log ==="
        cat "$YAME_LOG" 2>/dev/null || true
        echo
        echo "=== DNS fixture log ==="
        cat "$DNS_FIXTURE_LOG" 2>/dev/null || true
        echo
        echo "=== HTTP fixture log ==="
        cat "$HTTP_FIXTURE_LOG" 2>/dev/null || true
        echo
        echo "=== socat log ==="
        cat "$SOCAT_LOG" 2>/dev/null || true
        echo
        echo "=== DOSBox log ==="
        cat "$DOSBOX_LOG" 2>/dev/null || true
        echo
        echo "=== DOS suite outputs ==="
        for result in \
            "$DOS_DRIVE/PPP.OK" \
            "$DOS_DRIVE/DNS.OK" \
            "$DOS_DRIVE/TCP.OK" \
            "$DOS_DRIVE/SUITE.OK" \
            "$DOS_DRIVE/DNS.OUT" \
            "$DOS_DRIVE/TCP.OUT"; do
            echo "--- $(basename "$result") ---"
            cat "$result" 2>/dev/null || echo "<missing>"
        done
    fi

    rm -rf "$WORK_DIR"
    exit "$status"
}
trap cleanup EXIT INT TERM

for command in curl dosbox python3 sha1sum socat xvfb-run; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "Required command not found: $command" >&2
        exit 2
    fi
done

mkdir -p "$DOS_DRIVE"
cp "$ROOT/scripts/dos/YAMETEST.BAT" "$DOS_DRIVE/YAMETEST.BAT"
cp "$ROOT/scripts/dos/MTCP.CFG" "$DOS_DRIVE/MTCP.CFG"

download_and_verify() {
    local url=$1
    local expected_sha1=$2
    local destination=$3

    curl --fail --location --silent --show-error --retry 3 --output "$destination" "$url"
    echo "$expected_sha1  $destination" | sha1sum --check --status
}

# These are test-time dependencies only; the binaries are not redistributed with YAME.
# LSppp 1.0 is GPL-2.0-or-later, and mTCP 2025-01-10 is GPL-3.0.
download_and_verify "$LS_PPP_URL" "$LS_PPP_SHA1" "$LS_PPP_ZIP"
download_and_verify "$MTCP_URL" "$MTCP_SHA1" "$MTCP_ZIP"

extract_executable() {
    local archive=$1
    local filename=$2
    local destination=$3

    python3 - "$archive" "$filename" "$destination" <<'PY'
import pathlib
import sys
import zipfile

archive, filename, destination = sys.argv[1:]
with zipfile.ZipFile(archive) as source:
    matches = [
        name
        for name in source.namelist()
        if pathlib.PurePosixPath(name.replace("\\", "/")).name.lower() == filename.lower()
    ]
    if len(matches) != 1:
        raise SystemExit(
            f"expected exactly one {filename} in {archive}, found {len(matches)}: {matches}"
        )
    pathlib.Path(destination).write_bytes(source.read(matches[0]))
PY
}

extract_executable "$LS_PPP_ZIP" "LSPPP.EXE" "$DOS_DRIVE/LSPPP.EXE"
extract_executable "$MTCP_ZIP" "DNSTEST.EXE" "$DOS_DRIVE/DNSTEST.EXE"
extract_executable "$MTCP_ZIP" "HTGET.EXE" "$DOS_DRIVE/HTGET.EXE"

"$ROOT/gradlew" --no-daemon installDist >/dev/null
YAME="$ROOT/build/install/serial-modem-emulator/bin/serial-modem-emulator"

cat > "$DOSBOX_CONF" <<EOF
[sdl]
fullscreen=false

[mixer]
nosound=true

[serial]
serial2=nullmodem server:127.0.0.1 port:$PORT transparent:1

[autoexec]
@echo off
mount c "$DOS_DRIVE"
c:
YAMETEST.BAT
EOF

HOST_ADDRESS="$(python3 - <<'PY'
import socket

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
try:
    sock.connect(("192.0.2.1", 9))
    print(sock.getsockname()[0])
finally:
    sock.close()
PY
)"

if [[ -z "$HOST_ADDRESS" || "$HOST_ADDRESS" == "0.0.0.0" ]]; then
    echo "Could not determine the host IPv4 address for the TCP fixture" >&2
    exit 1
fi

# Permit the unprivileged fixture process to bind the standard DNS port.
current_unprivileged_port_start="$(sysctl -n net.ipv4.ip_unprivileged_port_start)"
if [[ "$current_unprivileged_port_start" -gt 53 ]]; then
    ORIGINAL_UNPRIVILEGED_PORT_START="$current_unprivileged_port_start"
    sudo sysctl -w net.ipv4.ip_unprivileged_port_start=53 >/dev/null
fi

python3 "$ROOT/scripts/http-fixture.py" >"$HTTP_FIXTURE_LOG" 2>&1 &
HTTP_FIXTURE_PID=$!

for _ in $(seq 1 50); do
    if grep -Fq "HTTP fixture listening" "$HTTP_FIXTURE_LOG" 2>/dev/null; then
        break
    fi
    if ! kill -0 "$HTTP_FIXTURE_PID" 2>/dev/null; then
        wait "$HTTP_FIXTURE_PID" || true
        HTTP_FIXTURE_PID=""
        echo "HTTP fixture exited before becoming ready" >&2
        exit 1
    fi
    sleep 0.1
done

if ! grep -Fq "HTTP fixture listening" "$HTTP_FIXTURE_LOG" 2>/dev/null; then
    echo "HTTP fixture did not become ready" >&2
    exit 1
fi

YAME_TCP_FIXTURE_ADDRESS="$HOST_ADDRESS" \
    python3 "$ROOT/scripts/dns-fixture.py" >"$DNS_FIXTURE_LOG" 2>&1 &
DNS_FIXTURE_PID=$!

for _ in $(seq 1 50); do
    if grep -Fq "DNS fixture listening" "$DNS_FIXTURE_LOG" 2>/dev/null; then
        break
    fi
    if ! kill -0 "$DNS_FIXTURE_PID" 2>/dev/null; then
        wait "$DNS_FIXTURE_PID" || true
        DNS_FIXTURE_PID=""
        echo "DNS fixture exited before becoming ready" >&2
        exit 1
    fi
    sleep 0.1
done

if ! grep -Fq "DNS fixture listening" "$DNS_FIXTURE_LOG" 2>/dev/null; then
    echo "DNS fixture did not become ready" >&2
    exit 1
fi

socat -d -d PTY,link="$PTY_LINK",raw,echo=0 TCP-LISTEN:$PORT,reuseaddr >"$SOCAT_LOG" 2>&1 &
SOCAT_PID=$!

for _ in $(seq 1 100); do
    [[ -e "$PTY_LINK" ]] && break
    if ! kill -0 "$SOCAT_PID" 2>/dev/null; then
        wait "$SOCAT_PID" || true
        SOCAT_PID=""
        echo "socat exited before creating the pseudo-terminal" >&2
        exit 1
    fi
    sleep 0.05
done

if [[ ! -e "$PTY_LINK" ]]; then
    echo "socat did not create the pseudo-terminal" >&2
    exit 1
fi

SERIAL_PORT="$(readlink -f "$PTY_LINK")"

"$YAME" \
    --port "$SERIAL_PORT" \
    --baud "$BAUD" \
    --pickup-time 0ms \
    --dial-tone-time 0ms \
    --handshake-profile none \
    --subnet 10.64.0.0/30 \
    --dns-upstream 127.0.0.1 \
    >"$YAME_LOG" 2>&1 &
YAME_PID=$!

for _ in $(seq 1 100); do
    if grep -Fq "Serial modem emulator ready" "$YAME_LOG" 2>/dev/null; then
        break
    fi
    if ! kill -0 "$YAME_PID" 2>/dev/null; then
        wait "$YAME_PID" || true
        YAME_PID=""
        echo "YAME exited before becoming ready" >&2
        exit 1
    fi
    sleep 0.05
done

if ! grep -Fq "Serial modem emulator ready" "$YAME_LOG" 2>/dev/null; then
    echo "YAME did not become ready" >&2
    exit 1
fi

xvfb-run -a dosbox -conf "$DOSBOX_CONF" >"$DOSBOX_LOG" 2>&1 &
DOSBOX_PID=$!

deadline=$((SECONDS + 75))
while [[ $SECONDS -lt $deadline ]]; do
    if [[ -f "$DOS_DRIVE/SUITE.OK" ]]; then
        result="$(tr -d '\r\n' < "$DOS_DRIVE/SUITE.OK")"
        if [[ "$result" == "OK" ]]; then
            break
        fi
        if [[ "$result" == "FAIL" ]]; then
            echo "DOS PPP/DNS/TCP compatibility suite reported a failure" >&2
            exit 1
        fi
    fi

    if ! kill -0 "$YAME_PID" 2>/dev/null; then
        wait "$YAME_PID" || true
        YAME_PID=""
        echo "YAME exited before the compatibility suite completed" >&2
        exit 1
    fi

    if ! kill -0 "$SOCAT_PID" 2>/dev/null; then
        wait "$SOCAT_PID" || true
        SOCAT_PID=""
        echo "socat exited before the compatibility suite completed" >&2
        exit 1
    fi

    if ! kill -0 "$DNS_FIXTURE_PID" 2>/dev/null; then
        wait "$DNS_FIXTURE_PID" || true
        DNS_FIXTURE_PID=""
        echo "DNS fixture exited before the compatibility suite completed" >&2
        exit 1
    fi

    if ! kill -0 "$HTTP_FIXTURE_PID" 2>/dev/null; then
        wait "$HTTP_FIXTURE_PID" || true
        HTTP_FIXTURE_PID=""
        echo "HTTP fixture exited before the compatibility suite completed" >&2
        exit 1
    fi

    if ! kill -0 "$DOSBOX_PID" 2>/dev/null; then
        wait "$DOSBOX_PID" || true
        DOSBOX_PID=""
        echo "DOSBox exited before writing the compatibility-suite result" >&2
        exit 1
    fi

    sleep 0.1
done

if [[ ! -f "$DOS_DRIVE/SUITE.OK" ]] ||
    [[ "$(tr -d '\r\n' < "$DOS_DRIVE/SUITE.OK")" != "OK" ]]; then
    echo "Timed out waiting for the DOS PPP/DNS/TCP compatibility suite" >&2
    exit 1
fi

if [[ "$(tr -d '\r\n' < "$DOS_DRIVE/PPP.OK")" != "OK" ]]; then
    echo "LSppp did not establish the PPP session" >&2
    exit 1
fi

if [[ "$(tr -d '\r\n' < "$DOS_DRIVE/DNS.OK")" != "OK" ]]; then
    echo "mTCP DNSTEST reported a failure" >&2
    exit 1
fi

if [[ "$(tr -d '\r\n' < "$DOS_DRIVE/TCP.OK")" != "OK" ]]; then
    echo "mTCP HTGet reported a TCP/HTTP failure" >&2
    exit 1
fi

if ! grep -Fq "203.0.113.42" "$DOS_DRIVE/DNS.OUT"; then
    echo "mTCP did not receive the deterministic DNS answer" >&2
    exit 1
fi

if ! grep -Fq "YAME TCP integration OK" "$DOS_DRIVE/TCP.OUT"; then
    echo "mTCP did not receive the deterministic HTTP payload through YAME" >&2
    exit 1
fi

if ! grep -Fq "MODEM dialing 1" "$YAME_LOG"; then
    echo "YAME did not observe the DOS dial command" >&2
    exit 1
fi

if ! grep -Fq "AT => CONNECT $BAUD" "$YAME_LOG"; then
    echo "YAME did not return CONNECT to LSppp" >&2
    exit 1
fi

if ! grep -Fq "LCP open" "$YAME_LOG"; then
    echo "PPP LCP did not open" >&2
    exit 1
fi

if ! grep -Fq "IPCP open: local=10.64.0.1 peer=10.64.0.2" "$YAME_LOG"; then
    echo "PPP IPCP did not negotiate the expected addresses" >&2
    exit 1
fi

if ! grep -Fq "DNS <= 10.64.0.2:" "$YAME_LOG"; then
    echo "YAME did not proxy the DOS DNS request" >&2
    exit 1
fi

if ! grep -Fq "query ci.yame.test type=1 class=1" "$DNS_FIXTURE_LOG"; then
    echo "DNS fixture did not receive the expected deterministic DNS query" >&2
    exit 1
fi

if ! grep -Fq "query tcp.yame.test type=1 class=1" "$DNS_FIXTURE_LOG"; then
    echo "DNS fixture did not resolve the TCP fixture hostname" >&2
    exit 1
fi

if ! grep -Fq "request GET /test.txt " "$HTTP_FIXTURE_LOG"; then
    echo "HTTP fixture did not receive mTCP's request" >&2
    exit 1
fi

if ! grep -Fq "TCP <= SYN 10.64.0.2:" "$YAME_LOG"; then
    echo "YAME did not observe the DOS TCP SYN" >&2
    exit 1
fi

if ! grep -Fq "TCP host connected $HOST_ADDRESS:$HTTP_PORT" "$YAME_LOG"; then
    echo "YAME did not open the host TCP socket for the DOS flow" >&2
    exit 1
fi

if ! grep -Fq "TCP open 10.64.0.2:" "$YAME_LOG"; then
    echo "YAME TCP state machine did not complete the three-way handshake" >&2
    exit 1
fi

echo "DOSBox network compatibility suite passed:"
echo "  - LSppp dialed YAME and received CONNECT $BAUD"
echo "  - PPP LCP and IPCP opened with 10.64.0.1 <-> 10.64.0.2"
echo "  - mTCP DNSTEST resolved ci.yame.test through YAME's DNS proxy"
echo "  - the deterministic upstream answer 203.0.113.42 reached DOS"
echo "  - mTCP HTGet connected through YAME's host TCP proxy"
echo "  - the HTTP fixture response reached DOS over the emulated TCP flow"
