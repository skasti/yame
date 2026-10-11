#!/usr/bin/env bash
# Data-directory provisioning shared by the installer and regression tests.

prepare_yame_data_directory() {
  local directory="$1" account="$2" account_group

  # The directory path is installed below root-owned /var/lib. Do not follow
  # an unexpected directory symlink while running as root.
  if [[ -L "$directory" ]]; then
    echo "Refusing symlinked YAME data directory: $directory" >&2
    return 1
  fi

  account_group="$(id -gn "$account")"
  install -d -m 700 -o "$account" -g "$account_group" -- "$directory"

  # SECURITY: Do not chown, chmod, read, or otherwise operate on yame.ini
  # from this root-run installer. The data directory belongs to an
  # unprivileged account which can replace yame.ini with a symlink between
  # any path-based check and use. Existing config must remain untouched.
}
