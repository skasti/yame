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

# Reject unsupported changes to the account that owns YAME runtime state
# before the root installer touches packages, file ownership, or systemd.
# The drop-in is root-managed and is the installation's service-account record.
verify_yame_service_account() {
  local requested="$1" dropin="$2" data_directory="$3"
  local configured owner_uid requested_uid

  if [[ -L "$dropin" ]]; then
    echo "Refusing symlinked YAME service-account configuration: $dropin" >&2
    return 1
  fi
  if [[ -e "$dropin" ]]; then
    if [[ ! -f "$dropin" ]]; then
      echo "Invalid YAME service-account configuration: $dropin" >&2
      return 1
    fi
    configured="$(sed -n 's/^User=//p' "$dropin")"
    # A missing/ambiguous User setting must not silently reset the account.
    if [[ -z "$configured" || "$configured" == *$'\n'* ]]; then
      echo "Cannot determine existing YAME service account from $dropin" >&2
      return 1
    fi
    if [[ "$configured" != "$requested" ]]; then
      echo "YAME is already configured for user '$configured', not '$requested'." >&2
      echo "Changing the service account is unsupported; migrate runtime data manually." >&2
      return 1
    fi
  fi

  # Also protect preexisting data when an older/manual installation has no
  # user.conf record. Do not inspect, follow or change the config file itself.
  if [[ -L "$data_directory" ]]; then
    echo "Refusing symlinked YAME data directory: $data_directory" >&2
    return 1
  fi
  if [[ -d "$data_directory" ]] &&
    { [[ -e "$dropin" ]] || [[ -e "$data_directory/yame.ini" ]] || [[ -L "$data_directory/yame.ini" ]]; }; then
    requested_uid="$(id -u "$requested")"
    owner_uid="$(stat -c %u -- "$data_directory")"
    if [[ "$owner_uid" != "$requested_uid" ]]; then
      echo "YAME runtime data directory belongs to UID $owner_uid, not user '$requested'." >&2
      echo "Changing the service account requires a separate, explicit data migration." >&2
      return 1
    fi
  fi
}
