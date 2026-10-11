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

# A root-managed systemd user.conf is authoritative on reinstall. Testing
# both users must never change data ownership or the existing configuration.
recorded="$sandbox/user.conf"
printf '[Service]\nUser=%s\n' "$account" > "$recorded"
user_conf_before="$(sha256sum "$recorded")"
data_before="$(stat -c '%u:%g:%a' "$data_dir")"
verify_yame_service_account "$account" "$recorded" "$data_dir"
if verify_yame_service_account root "$recorded" "$data_dir"; then
  echo "Installer accepted a change to an existing service account" >&2
  exit 1
fi
[[ "$(sha256sum "$recorded")" == "$user_conf_before" ]]
[[ "$(stat -c '%u:%g:%a' "$data_dir")" == "$data_before" ]]
[[ "$(stat -c '%u:%g:%a' "$data_dir/yame.ini")" == "$regular_before" ]]

# A missing/ambiguous or symlinked service-account drop-in is not permission
# to overwrite unknown service state.
printf '[Service]\n' > "$recorded"
if verify_yame_service_account "$account" "$recorded" "$data_dir"; then
  echo "Installer accepted a missing User setting" >&2
  exit 1
fi
printf '[Service]\nUser=%s\nUser=root\n' "$account" > "$recorded"
if verify_yame_service_account "$account" "$recorded" "$data_dir"; then
  echo "Installer accepted conflicting User settings" >&2
  exit 1
fi
rm "$recorded"
ln -s "$protected" "$recorded"
if verify_yame_service_account "$account" "$recorded" "$data_dir"; then
  echo "Installer accepted symlinked service-account configuration" >&2
  exit 1
fi
rm "$recorded"

# An older installation might have data but no systemd drop-in. It must also
# reject accidental reassignment of an existing owner's data.
verify_yame_service_account "$account" "$recorded" "$data_dir"
if verify_yame_service_account root "$recorded" "$data_dir"; then
  echo "Installer accepted reassignment of legacy runtime state" >&2
  exit 1
fi
[[ "$(stat -c '%u:%g:%a' "$data_dir")" == "$data_before" ]]

# A genuinely fresh install has no account record and no runtime directory.
verify_yame_service_account "$account" "$recorded" "$sandbox/fresh-runtime"

echo "Raspberry Pi data-directory security tests passed."
