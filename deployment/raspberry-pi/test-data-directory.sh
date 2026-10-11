#!/usr/bin/env bash
# Root-run regression tests for symlink-based privilege escalation in install.sh.
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "Run this test as root" >&2
  exit 1
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deployment/raspberry-pi/data-directory.sh
source "$script_dir/data-directory.sh"

sandbox="$(mktemp -d)"
trap 'rm -rf -- "$sandbox"' EXIT
account="$(id -nu 65534)"
data_dir="$sandbox/data"
mkdir "$data_dir"
chown "$account:$(id -gn "$account")" "$data_dir"

# Simulate a user-writable data directory with yame.ini as a symlink to a
# sensitive, root-owned file. Running the installer must never chown/chmod it.
protected="$sandbox/privileged-file"
printf 'should-remain-private\n' > "$protected"
chmod 600 "$protected"
ln -s "$protected" "$data_dir/yame.ini"

before="$(stat -c '%u:%g:%a' "$protected")"
prepare_yame_data_directory "$data_dir" "$account"
after="$(stat -c '%u:%g:%a' "$protected")"

[[ "$before" == "$after" ]] || {
  echo "Installer altered the symlink target's owner or mode" >&2
  exit 1
}
[[ -L "$data_dir/yame.ini" ]] || {
  echo "Installer unexpectedly replaced the existing config symlink" >&2
  exit 1
}

# Preserve an existing root-owned regular file without silently changing its
# ownership/permissions; migration must be an explicit administrative action.
rm "$data_dir/yame.ini"
printf 'legacy-config\n' > "$data_dir/yame.ini"
chmod 600 "$data_dir/yame.ini"
regular_before="$(stat -c '%u:%g:%a' "$data_dir/yame.ini")"
prepare_yame_data_directory "$data_dir" "$account"
[[ "$(stat -c '%u:%g:%a' "$data_dir/yame.ini")" == "$regular_before" ]] || {
  echo "Installer unexpectedly modified an existing regular config" >&2
  exit 1
}

# Even the data directory itself must not be a symlink when root assigns
# directory ownership or permissions.
ln -s "$sandbox" "$sandbox/data-link"
if prepare_yame_data_directory "$sandbox/data-link" "$account"; then
  echo "Installer accepted a symlinked data directory" >&2
  exit 1
fi
[[ "$(stat -c '%u:%g:%a' "$protected")" == "$before" ]]

echo "Raspberry Pi data-directory security tests passed."
