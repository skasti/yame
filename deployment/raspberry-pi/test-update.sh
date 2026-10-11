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
  cp "$YAME_TEST_ASSETS/yame-$YAME_TEST_VERSION-linux-arm64.zip" "$output"
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

echo "Raspberry Pi updater integration tests passed."
