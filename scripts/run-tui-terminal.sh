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

export JAVA_HOME="$java_21_home"
export PATH="$JAVA_HOME/bin:$PATH"

terminal_command='
  cd -- "$1"
  ./build/install/yame/bin/yame --config "$1/yame.ini" --ui tui
  status=$?
  if (( status != 0 )); then
    echo
    echo "YAME avsluttet med feilkode $status."
    read -r -p "Trykk Enter for å lukke terminalen... " _
  fi
  exit "$status"
'
terminal_shell=(bash -lc "$terminal_command" yame-tui "$project_dir")

if command -v kitty >/dev/null 2>&1; then
  kitty --detach \
    --start-as=maximized \
    --directory "$project_dir" \
    --override initial_window_width=120c \
    --override initial_window_height=40c \
    "${terminal_shell[@]}"
elif command -v gnome-terminal >/dev/null 2>&1; then
  gnome-terminal \
    --maximize \
    --working-directory="$project_dir" \
    -- "${terminal_shell[@]}"
else
  echo "Fant verken kitty eller gnome-terminal. Installer en av dem for å starte YAME TUI." >&2
  exit 1
fi
