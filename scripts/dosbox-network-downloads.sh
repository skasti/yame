#!/usr/bin/env bash
# Prepare test-time dependencies only; these binaries are not redistributed with YAME.
# LSppp 1.0 is GPL-2.0-or-later, and mTCP 2025-01-10 is GPL-3.0.
set -euo pipefail

# CI hashes this file for its cache key, including both URLs/versions and digests.
LS_PPP_URLS=(
    "https://www.ibiblio.org/pub/micro/pc-stuff/freedos/files/repositories/1.4/net/lsppp.zip"
    "https://ftp.icm.edu.pl/pub/msdos/freedos/repositories/1.4/net/lsppp/20250410.0/lsppp.zip"
)
LS_PPP_SHA1="5a6a1430aad2616b266be7d149bb3752beea319e"
MTCP_URLS=(
    "https://www.ibiblio.org/pub/micro/pc-stuff/freedos/files/repositories/1.4/net/mtcp/20250604.0/mtcp.zip"
    "https://ftp.icm.edu.pl/pub/msdos/freedos/repositories/1.4/net/mtcp/20250604.0/mtcp.zip"
)
MTCP_SHA1="c6a319ae44ef49d03616968bd330b9c64cfab167"

DOWNLOAD_DIR=${1:?Usage: dosbox-network-downloads.sh DOWNLOAD_DIR}
TEMP_ARCHIVE=""
cleanup() {
    if [[ -n "$TEMP_ARCHIVE" ]]; then
        rm -f -- "$TEMP_ARCHIVE"
    fi
}
trap cleanup EXIT

verify_archive() {
    printf '%s  %s\n' "$1" "$2" | sha1sum --check --status
}

download_and_verify() {
    local expected_sha1=$1
    local destination=$2
    shift 2
    local url
    local reason="cache miss"

    if [[ -f "$destination" ]] && verify_archive "$expected_sha1" "$destination"; then
        echo "Using checksum-verified archive: $destination"
        return
    fi
    if [[ -e "$destination" ]]; then
        reason="invalid cached archive"
        echo "Rejecting invalid cached archive: $destination (expected SHA-1 $expected_sha1)" >&2
        rm -f -- "$destination"
    fi

    for url in "$@"; do
        echo "DOSBox dependency $reason: downloading $url"
        TEMP_ARCHIVE="$(mktemp "$destination.XXXXXX")"
        if ! curl --fail --location --silent --show-error --retry 3 \
            --connect-timeout 10 --max-time 60 --retry-max-time 90 \
            --output "$TEMP_ARCHIVE" "$url"; then
            echo "Upstream download failed: $url" >&2
        elif ! verify_archive "$expected_sha1" "$TEMP_ARCHIVE"; then
            echo "SHA-1 mismatch for $url (expected $expected_sha1); refusing archive" >&2
        else
            # Publish only complete, verified downloads to the reusable cache location.
            mv -- "$TEMP_ARCHIVE" "$destination"
            TEMP_ARCHIVE=""
            echo "Downloaded and checksum-verified archive: $destination (source: $url)"
            return
        fi
        rm -f -- "$TEMP_ARCHIVE"
        TEMP_ARCHIVE=""
    done
    echo "DOSBox dependency setup failed: $reason and all download sources failed for $destination" >&2
    exit 2
}

mkdir -p "$DOWNLOAD_DIR"
download_and_verify "$LS_PPP_SHA1" "$DOWNLOAD_DIR/lsppp.zip" "${LS_PPP_URLS[@]}"
download_and_verify "$MTCP_SHA1" "$DOWNLOAD_DIR/mtcp.zip" "${MTCP_URLS[@]}"
