#!/usr/bin/env python3
"""Diagnostic: real Chrome -> Linux PPP -> serial PTYs -> YAME with its real TUI.

Run on a disposable Ubuntu GitHub Actions runner with socat, pppd, chat and Chrome.
Uses a deterministic page with many images before optional YAME_TEST_URLS.
Does not alter YAME's production behavior or suppress fatal overload handling.
"""
import base64
import getpass
import http.server
import os
from pathlib import Path
import pty
import re
import select
import shutil
import signal
import socket
import struct
import subprocess
import sys
import termios
import threading
import time
import tempfile
from urllib.parse import urlsplit, urlunsplit

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "build/reports/tui-ppp-browser-stress"
REPORT.mkdir(parents=True, exist_ok=True)
WORK = REPORT / "working"
WORK.mkdir(parents=True, exist_ok=True)
YAME = ROOT / "build/install/yame/bin/yame"
BAUD = 115200
NAMESPACE = f"yameci{os.getpid()}"
IMAGE_COUNT = 240
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9b3OZqoAAAAASUVORK5CYII="
)
# A deliberately finite terminal drain, approximating slower physical terminal output.
TERMINAL_BYTES_PER_SECOND = 20000


def log(message):
    print(message, flush=True)
    with (REPORT / "summary.txt").open("a", encoding="utf-8") as file:
        file.write(message + "\n")


class Fixture(http.server.BaseHTTPRequestHandler):
    count = 0
    lock = threading.Lock()

    def do_GET(self):
        with self.lock:
            type(self).count += 1
        if self.path == "/index.html":
            body = (
                "<!doctype html><html><head><title>YAME browser stress</title>"
                '<link rel="stylesheet" href="/style.css"></head><body><h1>YAME</h1>'
                + "".join(f'<img src="/img/{i}.png" width="1" height="1">' for i in range(IMAGE_COUNT))
                + "</body></html>"
            ).encode("utf-8")
            content_type = "text/html; charset=utf-8"
        elif self.path == "/style.css":
            body = b"body { background: #fff; }"
            content_type = "text/css"
        elif re.fullmatch(r"/img/\d+\.png", self.path):
            body = PNG
            content_type = "image/png"
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        with (REPORT / "fixture.log").open("a", encoding="utf-8") as file:
            file.write((format % args) + "\n")


class TerminalProcess:
    """Run YAME in a real controlling PTY; capture bounded/slow terminal output."""

    def __init__(self, command):
        master, slave = pty.openpty()
        self.master = master
        # Large console dimensions to reproduce expensive, real TUI frame rendering.
        import fcntl
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 55, 180, 0, 0))
        self.stderr = (REPORT / "yame-stderr.log").open("wb")
        env = dict(os.environ, TERM="xterm-256color", COLUMNS="180", LINES="55")
        def become_tty_session():
            import fcntl
            os.setsid()
            fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

        self.process = subprocess.Popen(
            command, stdin=slave, stdout=slave, stderr=self.stderr,
            cwd=WORK, env=env, preexec_fn=become_tty_session,
        )
        os.close(slave)
        self.done = threading.Event()
        self.reader = threading.Thread(target=self.drain, daemon=True)
        self.reader.start()

    def drain(self):
        try:
            with (REPORT / "tui-output.ansi").open("wb") as output:
                while not self.done.is_set():
                    readable, _, _ = select.select([self.master], [], [], 0.2)
                    if not readable:
                        if self.process.poll() is not None:
                            break
                        continue
                    try:
                        chunk = os.read(self.master, 4096)
                    except OSError:
                        break
                    if not chunk:
                        break
                    output.write(chunk)
                    output.flush()
                    time.sleep(len(chunk) / TERMINAL_BYTES_PER_SECOND)
        finally:
            os.close(self.master)

    def alive(self):
        return self.process.poll() is None

    def stop(self):
        if self.alive():
            os.killpg(self.process.pid, signal.SIGTERM)
            try:
                self.process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid, signal.SIGKILL)
                self.process.wait()
        self.done.set()
        self.reader.join(timeout=3)
        self.stderr.close()


def find_command(name):
    """Find installed programs, including privileged pppd (root:dip, mode 4754).

    pppd is executed by root through sudo/ip netns exec, so checking whether
    the unprivileged CI runner can execute it gives a false 'missing' result.
    """
    found = shutil.which(name)
    if found:
        return found
    for directory in ("/usr/sbin", "/sbin", "/usr/bin", "/bin"):
        candidate = Path(directory) / name
        if candidate.is_file():
            return str(candidate)
    raise RuntimeError(f"Required tool missing: {name}")


def start(command, log_name, *, sudo=False):
    actual = ["sudo", "-n", *command] if sudo else command
    output = (REPORT / log_name).open("wb")
    proc = subprocess.Popen(
        actual, stdout=output, stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    return proc, output


def stop(proc, output):
    if proc.poll() is None:
        os.killpg(proc.pid, signal.SIGTERM)
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait()
    output.close()


def command(*args, input=None, timeout=15):
    return subprocess.run(
        list(args), input=input, text=True, capture_output=True,
        timeout=timeout, check=True,
    )


def wait_for(condition, description, timeout=25):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        if condition():
            return
        time.sleep(0.2)
    raise RuntimeError(f"Timed out waiting for {description}")


def host_address():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.connect(("192.0.2.1", 9))
        return sock.getsockname()[0]


def upstream_dns():
    for line in Path("/etc/resolv.conf").read_text().splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[0] == "nameserver":
            try:
                socket.inet_aton(parts[1])
                return parts[1]
            except OSError:
                continue
    raise RuntimeError("No IPv4 resolver for YAME's upstream DNS proxy")


def site_urls():
    seen = set()
    for raw in os.environ.get("YAME_TEST_URLS", "").splitlines():
        value = raw.strip()
        if not value or value.startswith("#"):
            continue
        parsed = urlsplit(value)
        if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname:
            log(f"SKIP invalid TEST_URLS entry: {value!r}")
            continue
        if parsed.username or parsed.password:
            log("SKIP TEST_URLS URL with embedded credentials")
            continue
        # The legacy client visits port 80; YAME follows HTTPS upstream.
        if parsed.scheme.lower() == "https":
            if parsed.port not in (None, 443):
                log(f"SKIP nonstandard HTTPS port: {parsed.hostname}")
                continue
            parsed = parsed._replace(scheme="http", netloc=parsed.hostname)
        page = urlunsplit(parsed)
        if page not in seen:
            seen.add(page)
            yield page


def capture_tui_snapshot():
    """Persist the admission-failure queue histogram, never event payloads."""
    path = REPORT / "yame-stderr.log"
    if not path.is_file():
        return None
    diagnostics = path.read_text(encoding="utf-8", errors="replace")
    match = re.search(r"TUI queue admission timed out \([^\r\n]*\)", diagnostics)
    if match:
        snapshot = match.group(0)
        (REPORT / "tui-queue-snapshot.txt").write_text(snapshot + "\n", encoding="utf-8")
        return snapshot
    return None


def check_tui_overload(stage):
    """Surface the actual fatal rather than misreporting a failed page load."""
    snapshot = capture_tui_snapshot()
    if snapshot:
        raise RuntimeError(f"REPRODUCED TUI OVERLOAD during {stage}: {snapshot}")


def http_preflight(namespace, url, user):
    """Distinguish PPP/routing failure from a Chromium launch/navigation problem."""
    probe = (
        "import sys, urllib.request; "
        "opener = urllib.request.build_opener(urllib.request.ProxyHandler({})); "
        "response = opener.open(sys.argv[1], timeout=15); "
        "data = response.read(); "
        "print(f'HTTP {response.status}; bytes={len(data)}; html={b\"<html\" in data.lower()}')"
    )
    result = subprocess.run(
        ["sudo", "-n", "ip", "netns", "exec", namespace,
         "runuser", "-u", user, "--", sys.executable, "-c", probe, url],
        capture_output=True, text=True, timeout=25,
    )
    (REPORT / "http-preflight.stdout").write_text(result.stdout[:100000], encoding="utf-8")
    (REPORT / "http-preflight.stderr").write_text(result.stderr[:100000], encoding="utf-8")
    log(f"PPP HTTP preflight: exit={result.returncode}; "
        f"origin requests={Fixture.count}; {result.stdout.strip() or result.stderr.strip()[:600]}")
    check_tui_overload("PPP HTTP preflight")
    if result.returncode != 0 or "html=True" not in result.stdout:
        raise RuntimeError("PPP HTTP preflight failed before Chromium; "
                           "check routing, YAME proxy and fixture-forwarder logs")


def chrome_binary():
    """Prefer the exact Chrome binary installed by setup-chrome in CI."""
    configured = os.environ.get("YAME_CHROME_BINARY", "").strip()
    if configured:
        binary = Path(configured)
        if not binary.is_file() or not os.access(binary, os.X_OK):
            raise RuntimeError(f"Configured Chrome is missing or not executable: {binary}")
        return str(binary.resolve())
    # Permit standalone local runs without the GitHub Actions setup step.
    available = shutil.which("google-chrome") or shutil.which("chromium")
    if not available:
        raise RuntimeError("No Chrome/Chromium available (set YAME_CHROME_BINARY)")
    return available


def browser(namespace, url, label, user):
    # Keep Chrome caches, cookies and other browsing profile data out of CI artifacts.
    profile = Path(tempfile.mkdtemp(prefix=f"yame-chrome-{label}-"))
    prefix = (["sudo", "-n", "ip", "netns", "exec", namespace,
               "runuser", "-u", user, "--"] if namespace else [])
    cmd = prefix + [
        "env", "-u", "DBUS_SESSION_BUS_ADDRESS",
        chrome_binary(),
        "--headless=new", "--no-sandbox", "--disable-setuid-sandbox",
        "--disable-dev-shm-usage", "--disable-gpu", "--disable-background-networking",
        "--disable-component-update", "--disable-default-apps",
        "--disable-extensions", "--disable-sync", "--no-first-run",
        "--disable-quic", "--no-proxy-server",
        "--disable-features=HttpsUpgrades,HttpsFirstModeV2",
        "--blink-settings=scriptEnabled=false",
        f"--user-data-dir={profile}",
        "--dump-dom", url,
    ]
    try:
        result = subprocess.run(cmd, text=True, capture_output=True, timeout=65)
        (REPORT / f"chrome-{label}.stdout").write_text(result.stdout[:100000], encoding="utf-8")
        (REPORT / f"chrome-{label}.stderr").write_text(result.stderr[:100000], encoding="utf-8")
        log(f"{label}: browser exit={result.returncode}; DOM bytes={len(result.stdout)}; "
            f"stderr bytes={len(result.stderr)}; URL={url}")
        return result.returncode == 0 and "<html" in result.stdout.lower()
    except subprocess.TimeoutExpired:
        log(f"{label}: browser timeout at {url}")
        return False
    finally:
        shutil.rmtree(profile, ignore_errors=True)


def main():
    if not YAME.is_file():
        raise RuntimeError(f"Missing packaged distribution: {YAME}")
    chrome = chrome_binary()
    version = subprocess.run([chrome, "--version"], capture_output=True, text=True, timeout=10)
    log(f"Chrome for testing: {chrome}; version={version.stdout.strip() or version.stderr.strip()}; "
        f"exit={version.returncode}")
    if version.returncode != 0:
        raise RuntimeError("Configured Chrome executable failed its version check")
    programs = {item: find_command(item) for item in
                ("socat", "pppd", "chat", "ip", "sudo", "runuser")}
    log("PPP executables: pppd=" + programs["pppd"] + ", chat=" + programs["chat"])

    fixture = http.server.ThreadingHTTPServer(("127.0.0.1", 18081), Fixture)
    worker = threading.Thread(target=fixture.serve_forever, daemon=True)
    worker.start()
    children = []
    terminal = None
    namespace_created = False
    try:
        address = host_address()
        dns = upstream_dns()
        host_pty = WORK / "yame-serial"
        client_pty = WORK / "client-serial"
        bridge, out = start([
            "socat", "-d", "-d",
            f"PTY,link={host_pty},raw,echo=0",
            f"PTY,link={client_pty},raw,echo=0",
        ], "serial-bridge.log")
        children.append((bridge, out))
        wait_for(lambda: host_pty.is_symlink() and client_pty.is_symlink(),
                 "virtual serial PTYs")
        command("sudo", "-n", "ip", "netns", "add", NAMESPACE)
        namespace_created = True
        command("sudo", "-n", "ip", "netns", "exec", NAMESPACE,
                "ip", "link", "set", "lo", "up")
        command("sudo", "-n", "mkdir", "-p", f"/etc/netns/{NAMESPACE}")
        command("sudo", "-n", "tee", f"/etc/netns/{NAMESPACE}/resolv.conf",
                input="nameserver 10.64.0.1\n")
        forwarder, out = start([
            "socat", f"TCP-LISTEN:80,bind={address},reuseaddr,fork",
            "TCP:127.0.0.1:18081",
        ], "fixture-forwarder.log", sudo=True)
        children.append((forwarder, out))

        terminal = TerminalProcess([
            str(YAME), "--ui", "tui", "--config", str(WORK / "yame.ini"),
            "--log-dir", str(REPORT / "yame-logs"),
            "--port", os.path.realpath(host_pty), "--baud", str(BAUD),
            "--pickup-time", "0ms", "--dial-tone-time", "0ms",
            "--handshake-profile", "none", "--subnet", "10.64.0.0/30",
            "--dns-upstream", dns, "--http-https-proxy",
        ])
        wait_for(lambda: not terminal.alive() or
                 (REPORT / "tui-output.ansi").exists() and
                 b"YAME ready" in (REPORT / "tui-output.ansi").read_bytes(),
                 "YAME to open the serial port", timeout=30)
        if not terminal.alive():
            raise RuntimeError("YAME exited before PPP negotiation")

        pppd, out = start([
            programs["ip"], "netns", "exec", NAMESPACE, programs["pppd"], os.path.realpath(client_pty),
            str(BAUD), "local", "noauth", "noipdefault", "noccp", "novj", "noipv6",
            "defaultroute", "ipcp-accept-local", "ipcp-accept-remote",
            "nodetach", "debug", "10.64.0.2:10.64.0.1",
            "connect", f"{programs['chat']} -v -t 15 '' ATD1 CONNECT",
        ], "pppd.log", sudo=True)
        children.append((pppd, out))
        wait_for(lambda: pppd.poll() is not None or
                 "inet 10.64.0.2" in subprocess.run(
                     ["sudo", "-n", "ip", "netns", "exec", NAMESPACE,
                      "ip", "-4", "addr", "show", "dev", "ppp0"],
                     capture_output=True, text=True,
                 ).stdout, "PPP IPCP", timeout=35)
        if pppd.poll() is not None:
            raise RuntimeError("pppd exited before IPCP completed")
        log(f"PPP opened at configured {BAUD} baud (PTYs do not enforce physical line pacing)")

        user = getpass.getuser()
        http_preflight(NAMESPACE, f"http://{address}/index.html", user)
        # The CI host and network namespace share their filesystem. A file://
        # smoke page isolates Chromium startup from every network dependency.
        smoke_file = WORK / "chromium-smoke.html"
        smoke_file.write_text(
            "<!doctype html><html><head><title>YAME smoke</title></head>"
            "<body>YAME SMOKE</body></html>",
            encoding="utf-8",
        )
        smoke_url = smoke_file.resolve().as_uri()
        if not browser(None, smoke_url, "host-smoke", user):
            raise RuntimeError("Chromium cannot render a local HTML file on the CI host")
        if not browser(NAMESPACE, smoke_url, "namespace-smoke", user):
            raise RuntimeError("Chromium cannot render a local HTML file inside the PPP network namespace")
        baseline = browser(NAMESPACE, f"http://{address}/index.html", "fixture", user)
        log(f"Deterministic image page: requests observed by origin={Fixture.count}")
        check_tui_overload("deterministic browser load")
        if not terminal.alive():
            raise RuntimeError(f"YAME exited during deterministic browser load ({terminal.process.returncode})")
        # Treat an inability to exercise the complete path as setup failure,
        # never silently report a passing stress test.
        if not baseline or Fixture.count < 30:
            raise RuntimeError("Browser failed to fetch HTML and embedded resources through PPP")

        for i, url in enumerate(site_urls(), start=1):
            browser(NAMESPACE, url, f"external-{i}", user)
            time.sleep(2)
            check_tui_overload(f"external URL {i}")
            if not terminal.alive():
                raise RuntimeError(f"YAME exited during external URL {i} ({terminal.process.returncode})")
        time.sleep(3)
        check_tui_overload("post-load idle")
        if not terminal.alive():
            raise RuntimeError(f"YAME exited after browser load ({terminal.process.returncode})")
        log("PASS: YAME remained running after PPP/browser traffic; TUI queue did not fail in this run.")
        log("A pass does not prove that Raspberry Pi HDMI rendering cannot saturate its queue.")
    finally:
        if terminal:
            capture_tui_snapshot()
            terminal.stop()
        for proc, output in reversed(children):
            stop(proc, output)
        if namespace_created:
            subprocess.run(["sudo", "-n", "ip", "netns", "del", NAMESPACE], check=False)
            subprocess.run(["sudo", "-n", "rm", "-rf", f"/etc/netns/{NAMESPACE}"], check=False)
        fixture.shutdown()
        fixture.server_close()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        log(f"FAIL / INCONCLUSIVE: {type(exc).__name__}: {exc}")
        raise
