#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

is_java_21() {
  local candidate="$1"
  [[ -x "$candidate/bin/java" ]] &&
    "$candidate/bin/java" -version 2>&1 | grep -qE 'version "21([.]|")'
}

java_21_home=""
if is_java_21 "${JAVA_HOME:-}"; then
  java_21_home="$JAVA_HOME"
else
  current_java="$(command -v java || true)"
  if [[ -n "$current_java" ]]; then
    current_java_home="$(dirname -- "$(dirname -- "$(readlink -f -- "$current_java")")")"
    if is_java_21 "$current_java_home"; then
      java_21_home="$current_java_home"
    fi
  fi
fi

if [[ -z "$java_21_home" ]]; then
  for candidate in "$HOME"/.gradle/jdks/*; do
    if is_java_21 "$candidate"; then
      java_21_home="$candidate"
      break
    fi
  done
fi

if [[ -z "$java_21_home" ]]; then
  echo "YAME krever JDK 21. Installer JDK 21 eller sett JAVA_HOME før du starter." >&2
  exit 1
fi

if ! command -v gnome-terminal >/dev/null 2>&1; then
  echo "Fant ikke gnome-terminal. Installer GNOME Terminal for å starte YAME TUI." >&2
  exit 1
fi

export JAVA_HOME="$java_21_home"
export PATH="$JAVA_HOME/bin:$PATH"

gnome-terminal --working-directory="$project_dir" -- bash -lc '
  cd -- "$1"
  ./build/install/yame/bin/yame --ui tui
  status=$?
  echo
  echo "YAME avsluttet med status $status."
  read -r -p "Trykk Enter for å lukke terminalen... " _
  exit "$status"
' yame-tui "$project_dir"
