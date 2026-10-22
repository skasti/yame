#!/usr/bin/env bash
# Prepare test-time dependencies only; these binaries are not redistributed with YAME.
# LSppp 1.0 is GPL-2.0-or-later, and mTCP 2025-01-10 is GPL-3.0.
set -euo pipefail

# CI hashes this file for its cache key, including every URL/version and digest.
# Each record is "SHA-1 URL". The GitLab archives contain the same program
# versions in different ZIP packaging, so they have separate pinned digests.
# Pin full commits rather than branches or tags for generated GitLab archives.
LS_PPP_SOURCES=(
    "5a6a1430aad2616b266be7d149bb3752beea319e https://www.ibiblio.org/pub/micro/pc-stuff/freedos/files/repositories/1.4/net/lsppp.zip"
    "e72a766d1d81804515d45ec7068bcc869d84ba55 https://gitlab.com/api/v4/projects/FreeDOS%2Fnet%2Flsppp/repository/archive.zip?sha=c3256bc4f77856f79ce1a48158296a19ba460f61"
)
MTCP_SOURCES=(
    "c6a319ae44ef49d03616968bd330b9c64cfab167 https://www.ibiblio.org/pub/micro/pc-stuff/freedos/files/repositories/1.4/net/mtcp/20250604.0/mtcp.zip"
    "b3747b4750bfd3286fcf97c0f9da4bfaa9f1850f https://gitlab.com/api/v4/projects/FreeDOS%2Fnet%2Fmtcp/repository/archive.zip?sha=50ddca68178896cd8b9a21f31cd09acde6eeeeae"
)

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
    local destination=$1
    shift
    local source expected_sha1 url
    local reason="cache miss"

    for source in "$@"; do
        expected_sha1=${source%% *}
        if [[ -f "$destination" ]] && verify_archive "$expected_sha1" "$destination"; then
            echo "Using checksum-verified archive: $destination (SHA-1 $expected_sha1)"
            return
        fi
    done
    if [[ -e "$destination" ]]; then
        reason="invalid cached archive"
        echo "Rejecting invalid cached archive: $destination (no pinned SHA-1 matched)" >&2
        rm -f -- "$destination"
    fi

    for source in "$@"; do
        expected_sha1=${source%% *}
        url=${source#* }
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
download_and_verify "$DOWNLOAD_DIR/lsppp.zip" "${LS_PPP_SOURCES[@]}"
download_and_verify "$DOWNLOAD_DIR/mtcp.zip" "${MTCP_SOURCES[@]}"
