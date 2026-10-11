#!/usr/bin/env bash
# Self-contained, offline integration tests for the root-run updater.
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "Run these tests as root (sudo bash deployment/raspberry-pi/test-update.sh)" >&2
  exit 1
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
sandbox="$(mktemp -d)"
trap 'rm -rf -- "$sandbox"' EXIT
mkdir -p "$sandbox/mock" "$sandbox/assets"
export YAME_BASE="$sandbox/install"
export YAME_EXTRACTOR="$script_dir/extract-release.py"
export YAME_TEST_ASSETS="$sandbox/assets"
export PATH="$sandbox/mock:$PATH"

cat > "$sandbox/mock/curl" <<'MOCK_CURL'
#!/usr/bin/env bash
set -euo pipefail
url="${*: -1}"
if [[ "$url" == "https://api.github.com/repos/skasti/yame/releases/latest" ]]; then
  if [[ "${YAME_TEST_OFFLINE:-0}" == 1 ]]; then
    exit 22
  fi
  version="$YAME_TEST_VERSION"
  asset="yame-$version-linux-arm64.zip"
  archive="$YAME_TEST_ASSETS/$asset"
  digest="$(sha256sum "$archive" | cut -d ' ' -f 1)"
  if [[ "${YAME_TEST_BAD_DIGEST:-0}" == 1 ]]; then
    digest="$(printf '%064d' 0)"
  fi
  printf '{"tag_name":"v%s","assets":[{"name":"%s","browser_download_url":"https://github.com/skasti/yame/releases/download/v%s/%s","digest":"sha256:%s"}]}\n' \
    "$version" "$asset" "$version" "$asset" "$digest"
else
  output=""
  while (( $# )); do
    if [[ "$1" == "-o" ]]; then
      output="$2"
      shift 2
    else
      shift
    fi
  done
  [[ -n "$output" ]] || exit 1
  max_size=67108864
  archive="$YAME_TEST_ASSETS/yame-$YAME_TEST_VERSION-linux-arm64.zip"
  if (( $(stat -c %s "$archive") > max_size )); then
    # Emulate curl --max-filesize rejecting an excessive stream.
    exit 63
  fi
  cp "$archive" "$output"
fi
MOCK_CURL
chmod +x "$sandbox/mock/curl"

make_fixture() {
  local version="$1" package="$sandbox/package"
  rm -rf "$package"
  mkdir -p "$package/yame-$version/bin" "$package/yame-$version/lib"
  printf '#!/bin/sh\nexit 0\n' > "$package/yame-$version/bin/yame"
  chmod +x "$package/yame-$version/bin/yame"
  printf 'test library\n' > "$package/yame-$version/lib/test.txt"
  (cd "$package" && zip -qr "$YAME_TEST_ASSETS/yame-$version-linux-arm64.zip" "yame-$version")
}

assert_target() {
  local link="$1" expected="$2"
  [[ "$(readlink -f "$YAME_BASE/$link")" == "$YAME_BASE/releases/v$expected" ]] || {
    echo "Unexpected $link target: $(readlink "$YAME_BASE/$link" || true)" >&2
    exit 1
  }
}

for version in 1.0.0 1.1.0 1.2.0 1.3.0; do
  make_fixture "$version"
done

export YAME_TEST_VERSION=1.0.0
bash "$script_dir/yame-update"
assert_target current 1.0.0

# Emulate a power failure after the creation of both old fixed-name links
# and an incomplete downloaded staging directory.
ln -s "releases/v1.0.0" "$YAME_BASE/.current-next"
ln -s "releases/v1.0.0" "$YAME_BASE/.previous-next"
mkdir -p "$YAME_BASE/.staging.abandoned"
printf 'incomplete download' > "$YAME_BASE/.staging.abandoned/release.zip"

export YAME_TEST_VERSION=1.1.0
bash "$script_dir/yame-update"
assert_target current 1.1.0
assert_target previous 1.0.0
[[ ! -e "$YAME_BASE/.staging.abandoned" ]]

export YAME_TEST_VERSION=1.2.0
bash "$script_dir/yame-update"
assert_target current 1.2.0
assert_target previous 1.1.0
[[ ! -e "$YAME_BASE/releases/v1.0.0" ]]

# No changes or disk growth when the release is already current.
bash "$script_dir/yame-update"

# The last installed version must remain available without internet.
export YAME_TEST_OFFLINE=1
bash "$script_dir/yame-update"
unset YAME_TEST_OFFLINE
assert_target current 1.2.0

# Verify a bad digest doesn't replace current/previous.
export YAME_TEST_VERSION=1.3.0 YAME_TEST_BAD_DIGEST=1
bash "$script_dir/yame-update"
unset YAME_TEST_BAD_DIGEST
assert_target current 1.2.0
assert_target previous 1.1.0
[[ ! -e "$YAME_BASE/releases/v1.3.0" ]]

# Root-run extraction must reject highly compressed ZIP bombs before writing
# unbounded data to the SD card. Generate a 257 MiB archive of zeros that
# compresses to a fraction of that size without allocating a huge byte array.
python3 - "$YAME_TEST_ASSETS/yame-1.3.0-linux-arm64.zip" <<'PY_BOMB'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1], "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as zf:
    with zf.open("yame-1.3.0/lib/bomb.bin", "w") as out:
        for _ in range(257):
            out.write(b"\0" * (1024 * 1024))
PY_BOMB
bash "$script_dir/yame-update"
assert_target current 1.2.0
assert_target previous 1.1.0

# Reject path traversal even if metadata reports a harmless total size.
python3 - "$YAME_TEST_ASSETS/yame-1.3.0-linux-arm64.zip" <<'PY_TRAVERSAL'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1], "w") as zf:
    zf.writestr("yame-1.3.0/bin/yame", "#!/bin/sh\nexit 0\n")
    zf.writestr("yame-1.3.0/lib/example.jar", "test")
    zf.writestr("yame-1.3.0/../../escaped", "danger")
PY_TRAVERSAL
bash "$script_dir/yame-update"
assert_target current 1.2.0
assert_target previous 1.1.0
[[ ! -e "$YAME_BASE/escaped" ]]
[[ ! -e "$sandbox/escaped" ]]

# Reject archives containing more than 4096 entries.
python3 - "$YAME_TEST_ASSETS/yame-1.3.0-linux-arm64.zip" <<'PY_ENTRIES'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1], "w", compression=zipfile.ZIP_STORED) as zf:
    for index in range(4097):
        zf.writestr(f"yame-1.3.0/lib/part-{index}.jar", "x")
PY_ENTRIES
bash "$script_dir/yame-update"
assert_target current 1.2.0

# Reject an oversized download before extraction.
truncate -s $((65 * 1024 * 1024)) "$YAME_TEST_ASSETS/yame-1.3.0-linux-arm64.zip"
bash "$script_dir/yame-update"
assert_target current 1.2.0
assert_target previous 1.1.0
[[ ! -e "$YAME_BASE/releases/v1.3.0" ]]

# Without a previous install, failure must propagate to the caller so
# install.sh can leave tty1 untouched.
rm "$YAME_BASE/current"
export YAME_TEST_OFFLINE=1
if bash "$script_dir/yame-update"; then
  echo "Fresh installation without internet unexpectedly succeeded" >&2
  exit 1
fi
unset YAME_TEST_OFFLINE

echo "Raspberry Pi updater integration tests passed."
